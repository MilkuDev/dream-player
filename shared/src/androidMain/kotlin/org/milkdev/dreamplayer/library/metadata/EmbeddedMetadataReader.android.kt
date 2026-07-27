@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "SpellCheckingInspection")

package org.milkdev.dreamplayer.library.metadata

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.app.applicationContext
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.library.RawTrackData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

actual object EmbeddedMetadataReader {
    actual suspend fun read(rawTrack: RawTrackData): EmbeddedMetadata? = withContext(Dispatchers.IO) {
        val path = rawTrack.path.takeIf { it.isNotBlank() } ?: return@withContext null
        val probeBytes = readMetadataBytes(path, FORMAT_PROBE_BYTES) ?: return@withContext null

        val parsed = when {
            probeBytes.startsWith(FLAC_SIGNATURE) -> {
                readMetadataBytes(path, MAX_TAG_SCAN_BYTES)?.parseFlacEmbeddedMetadata()
            }

            probeBytes.startsWithId3() -> {
                readMetadataBytes(path, MAX_TAG_SCAN_BYTES)?.parseMp3EmbeddedMetadata()
            }

            probeBytes.looksLikeMp4() -> readMp4EmbeddedMetadata(path)

            else -> EmbeddedMetadata(
                tagFingerprint = (
                    readMetadataBytes(path, MAX_FALLBACK_HASH_BYTES)
                        ?: probeBytes
                    ).sha256()
            )
        } ?: EmbeddedMetadata(tagFingerprint = probeBytes.sha256())

        parsed.copy(
            tagFingerprint = "${rawTrack.fileSize}:${rawTrack.lastModified}:${parsed.tagFingerprint}",
        )
    }
}

private fun readMetadataBytes(path: String, limit: Int): ByteArray? {
    return runCatching {
        openMetadataStream(path)?.use { input -> input.readBytes(limit) }
    }.getOrNull()
}

@SuppressLint("UseKtx")
private fun openMetadataStream(path: String): InputStream? {
    return if (path.startsWith("content://", ignoreCase = true)) {
        applicationContext.contentResolver.openInputStream(android.net.Uri.parse(path))
    } else {
        File(path).takeIf { it.isFile }?.let(::FileInputStream)
    }
}

private fun ByteArray.parseFlacEmbeddedMetadata(): EmbeddedMetadata {
    var offset = FLAC_SIGNATURE.size
    while (offset + FLAC_METADATA_BLOCK_HEADER_SIZE <= size) {
        val typeAndFlags = this[offset++].unsigned()
        val hasMoreBlocks = typeAndFlags and FLAC_LAST_METADATA_BLOCK_FLAG == 0
        val blockType = typeAndFlags and FLAC_METADATA_BLOCK_TYPE_MASK
        val blockSize = bigEndianInt(offset, 3)
        offset += 3
        val blockEnd = offset + blockSize
        if (blockSize <= 0 || blockEnd > size) break

        if (blockType == FLAC_VORBIS_COMMENT_BLOCK_TYPE) {
            val block = copyOfRange(offset, blockEnd)
            return block.parseFlacComments().toEmbeddedMetadata(block.sha256())
        }

        offset = blockEnd
        if (!hasMoreBlocks) break
    }
    return EmbeddedMetadata(tagFingerprint = take(MAX_FALLBACK_HASH_BYTES).toByteArray().sha256())
}

private fun ByteArray.parseMp3EmbeddedMetadata(): EmbeddedMetadata {
    val tagSize = synchsafeInt(6)
    if (tagSize <= 0 || ID3_HEADER_SIZE + tagSize > size) {
        return EmbeddedMetadata(
            tagFingerprint = take(MAX_FALLBACK_HASH_BYTES).toByteArray().sha256()
        )
    }
    val header = copyOfRange(0, ID3_HEADER_SIZE)
    val tag = copyOfRange(ID3_HEADER_SIZE, ID3_HEADER_SIZE + tagSize)
    val parsedTag = parseId3Tag(header, tag) ?: return EmbeddedMetadata(
        tagFingerprint = tag.sha256()
    )
    parsedTag.logIssues()
    return parsedTag.toEmbeddedMetadata(tag.sha256())
}

private fun readMp4EmbeddedMetadata(path: String): EmbeddedMetadata? {
    val parsedTag = runCatching {
        if (path.startsWith("content://", ignoreCase = true)) {
            readContentMp4Tag(path)
        } else {
            val file = File(path)
            if (!file.isFile) return@runCatching null
            RandomAccessFile(file, "r").use { randomAccessFile ->
                randomAccessFile.channel.readMp4Tag()
            }
        }
    }.getOrNull()

    if (parsedTag == null) {
        AppDebugLog.log("embedded_mp4_parse_failed")
        return null
    }
    parsedTag.logIssues()
    return parsedTag.toEmbeddedMetadata(parsedTag.fingerprintMaterial.sha256())
}

private fun readContentMp4Tag(path: String): Mp4Tag? {
    val descriptor = applicationContext.contentResolver.openFileDescriptor(
        android.net.Uri.parse(path),
        "r",
    ) ?: return null
    val declaredSize = descriptor.statSize
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        val channel = input.channel
        val fileSize = declaredSize.takeIf { it > 0L } ?: channel.size()
        channel.readMp4Tag(fileSize)
    }
}

private fun FileChannel.readMp4Tag(fileSize: Long = size()): Mp4Tag? {
    if (fileSize <= 0L) return null
    return parseMp4Tag(
        fileSize = fileSize,
        reader = Mp4RandomAccessReader { offset, byteCount ->
            readExact(offset, byteCount)
        },
    )
}

private fun FileChannel.readExact(offset: Long, byteCount: Int): ByteArray? {
    if (offset < 0L || byteCount < 0) return null
    val buffer = ByteBuffer.allocate(byteCount)
    var position = offset
    while (buffer.hasRemaining()) {
        val read = read(buffer, position)
        if (read <= 0) return null
        position += read
    }
    return buffer.array()
}

private fun ByteArray.parseFlacComments(): Map<String, List<String>> {
    var offset = 0
    val vendorSize = littleEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return emptyMap()
    offset = vendorSize // TODO: something

    val commentCount = littleEndianUnsignedInt(offset) ?: return emptyMap()
    if (commentCount > MAX_COMMENT_COUNT) return emptyMap()
    offset += INT_BYTES

    val comments = linkedMapOf<String, MutableList<String>>()
    repeat(commentCount.toInt()) {
        val commentEnd = littleEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return comments
        val comment = decodeToString(offset + INT_BYTES, commentEnd)
        offset = commentEnd

        val separatorIndex = comment.indexOf('=')
        if (separatorIndex <= 0) return@repeat
        val key = comment.substring(0, separatorIndex).lowercase()
        val value = comment.substring(separatorIndex + 1).trim()
        if (value.isNotEmpty()) comments.getOrPut(key) { mutableListOf() }.add(value)
    }
    return comments.mapValues { (_, values) -> values.toList() }
}

private fun Id3Tag.logIssues() {
    if (issues.isEmpty()) return
    AppDebugLog.log(
        "embedded_id3_parse issues=${issues.joinToString(",") { issue -> issue.name.lowercase() }}"
    )
}

private fun Mp4Tag.logIssues() {
    if (issues.isEmpty()) return
    AppDebugLog.log(
        "embedded_mp4_parse issues=${issues.joinToString(",") { issue -> issue.name.lowercase() }}"
    )
}

private fun ByteArray.startsWithId3(): Boolean {
    return size >= 3 && this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() &&
        this[2] == '3'.code.toByte()
}

private fun ByteArray.looksLikeMp4(): Boolean {
    return size >= MP4_FILE_TYPE_OFFSET + MP4_ATOM_TYPE_SIZE &&
        this[MP4_FILE_TYPE_OFFSET] == 'f'.code.toByte() &&
        this[MP4_FILE_TYPE_OFFSET + 1] == 't'.code.toByte() &&
        this[MP4_FILE_TYPE_OFFSET + 2] == 'y'.code.toByte() &&
        this[MP4_FILE_TYPE_OFFSET + 3] == 'p'.code.toByte()
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}

private fun ByteArray.synchsafeInt(startIndex: Int): Int {
    if (startIndex < 0 || startIndex + SYNCHSAFE_INTEGER_BYTES > size) return -1
    return (startIndex until startIndex + SYNCHSAFE_INTEGER_BYTES).fold(0) { value, index ->
        (value shl SYNCHSAFE_BITS_PER_BYTE) or (this[index].unsigned() and SYNCHSAFE_BYTE_MASK)
    }
}

private fun ByteArray.bigEndianInt(startIndex: Int, byteCount: Int): Int {
    if (startIndex < 0 || startIndex + byteCount > size) return -1
    return (startIndex until startIndex + byteCount).fold(0) { value, index ->
        (value shl BITS_PER_BYTE) or this[index].unsigned()
    }
}

private fun ByteArray.littleEndianUnsignedInt(startIndex: Int): Long? {
    if (startIndex < 0 || startIndex + INT_BYTES > size) return null
    return (startIndex until startIndex + INT_BYTES).foldIndexed(0L) { byteIndex, value, index ->
        value or (this[index].unsigned().toLong() shl (byteIndex * BITS_PER_BYTE))
    }
}

private fun Long.regionEnd(startIndex: Int, totalSize: Int): Int? {
    if (this < 0L || startIndex < 0) return null
    return (startIndex.toLong() + this).takeIf { it <= totalSize }?.toInt()
}

private fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.unsigned()) }
}

private fun InputStream.readBytes(limit: Int): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

private fun Byte.unsigned(): Int = toInt() and 0xFF

private const val ID3_HEADER_SIZE = 10
private const val MP4_FILE_TYPE_OFFSET = 4
private const val MP4_ATOM_TYPE_SIZE = 4
private const val FLAC_METADATA_BLOCK_HEADER_SIZE = 4
private const val FLAC_LAST_METADATA_BLOCK_FLAG = 0x80
private const val FLAC_METADATA_BLOCK_TYPE_MASK = 0x7F
private const val FLAC_VORBIS_COMMENT_BLOCK_TYPE = 4
private const val INT_BYTES = 4
private const val BITS_PER_BYTE = 8
private const val SYNCHSAFE_INTEGER_BYTES = 4
private const val SYNCHSAFE_BITS_PER_BYTE = 7
private const val SYNCHSAFE_BYTE_MASK = 0x7F
private const val MAX_TAG_SCAN_BYTES = 4 * 1024 * 1024
private const val MAX_FALLBACK_HASH_BYTES = 64 * 1024
private const val FORMAT_PROBE_BYTES = 16
private const val MAX_COMMENT_COUNT = 4096L
private val FLAC_SIGNATURE = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
