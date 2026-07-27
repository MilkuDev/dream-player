@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "SpellCheckingInspection")

package org.milkdev.dreamplayer.library.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.library.RawTrackData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

actual object EmbeddedMetadataReader {
    actual suspend fun read(rawTrack: RawTrackData): EmbeddedMetadata? = withContext(Dispatchers.IO) {
        val file = rawTrack.path.takeIf { it.isNotBlank() }?.let(::File) ?: return@withContext null
        if (!file.isFile) return@withContext null

        val parsed = when (file.extension.lowercase()) {
            "flac" -> file.readFlacEmbeddedMetadata()
            "mp3" -> file.readMp3EmbeddedMetadata()
            "m4a", "mp4" -> file.readMp4EmbeddedMetadata()
            else -> null
        } ?: file.fallbackEmbeddedMetadata() ?: return@withContext null

        parsed.copy(
            tagFingerprint = "${file.length()}:${file.lastModified()}:${parsed.tagFingerprint}",
        )
    }
}

private fun File.readFlacEmbeddedMetadata(): EmbeddedMetadata? {
    return runCatching {
        RandomAccessFile(this, "r").use { file ->
            val signature = ByteArray(FLAC_SIGNATURE.size)
            file.readFully(signature)
            if (!signature.contentEquals(FLAC_SIGNATURE)) return@use null

            var hasMoreBlocks = true
            while (hasMoreBlocks && file.filePointer + FLAC_METADATA_BLOCK_HEADER_SIZE <= file.length()) {
                val typeAndFlags = file.readUnsignedByte()
                hasMoreBlocks = typeAndFlags and FLAC_LAST_METADATA_BLOCK_FLAG == 0
                val blockType = typeAndFlags and FLAC_METADATA_BLOCK_TYPE_MASK
                val blockSize = file.readUnsignedMedium()
                val blockEnd = file.filePointer + blockSize
                if (blockEnd > file.length()) return@use null

                if (blockType == FLAC_VORBIS_COMMENT_BLOCK_TYPE && blockSize <= MAX_TAG_BLOCK_SIZE) {
                    val block = ByteArray(blockSize).also(file::readFully)
                    return@use block.parseFlacComments().toEmbeddedMetadata(block.sha256())
                }
                file.seek(blockEnd)
            }
            null
        }
    }.getOrNull()
}

private fun File.readMp3EmbeddedMetadata(): EmbeddedMetadata? {
    return runCatching {
        RandomAccessFile(this, "r").use { file ->
            val header = ByteArray(ID3_HEADER_SIZE)
            file.readFully(header)
            if (!header.startsWithId3()) return@use null

            val tagSize = header.synchsafeInt(6)
            if (tagSize !in 1..MAX_TAG_BLOCK_SIZE || tagSize > file.length() - ID3_HEADER_SIZE) {
                return@use null
            }

            val tag = ByteArray(tagSize).also(file::readFully)
            val parsedTag = parseId3Tag(header, tag) ?: return@use EmbeddedMetadata(
                tagFingerprint = tag.sha256()
            )
            parsedTag.logIssues()
            parsedTag.toEmbeddedMetadata(tag.sha256())
        }
    }.getOrNull()
}

private fun File.readMp4EmbeddedMetadata(): EmbeddedMetadata? {
    return runCatching {
        RandomAccessFile(this, "r").use { file ->
            val parsedTag = parseMp4Tag(
                fileSize = file.length(),
                reader = Mp4RandomAccessReader { offset, byteCount ->
                    file.readExact(offset, byteCount)
                },
            ) ?: return@use null
            parsedTag.logIssues()
            parsedTag.toEmbeddedMetadata(parsedTag.fingerprintMaterial.sha256())
        }
    }.getOrNull()
}

private fun File.fallbackEmbeddedMetadata(): EmbeddedMetadata? {
    return runCatching {
        inputStream().use { input ->
            val sample = input.readBytes(MAX_FALLBACK_HASH_BYTES)
            EmbeddedMetadata(tagFingerprint = sample.sha256())
        }
    }.getOrNull()
}

private fun ByteArray.parseFlacComments(): Map<String, List<String>> {
    var offset = 0
    val vendorSize = littleEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return emptyMap()
    offset = vendorSize

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

private fun RandomAccessFile.readExact(offset: Long, byteCount: Int): ByteArray? {
    if (offset < 0L || byteCount < 0 || byteCount.toLong() > length() - offset) return null
    seek(offset)
    return ByteArray(byteCount).also(::readFully)
}

private fun RandomAccessFile.readUnsignedMedium(): Int {
    return (readUnsignedByte() shl (BITS_PER_BYTE * 2)) or
        (readUnsignedByte() shl BITS_PER_BYTE) or
        readUnsignedByte()
}

private fun ByteArray.startsWithId3(): Boolean {
    return size >= 3 && this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() &&
        this[2] == '3'.code.toByte()
}

private fun ByteArray.synchsafeInt(startIndex: Int): Int {
    if (startIndex < 0 || startIndex + SYNCHSAFE_INTEGER_BYTES > size) return -1
    return (startIndex until startIndex + SYNCHSAFE_INTEGER_BYTES).fold(0) { value, index ->
        (value shl SYNCHSAFE_BITS_PER_BYTE) or (this[index].unsigned() and SYNCHSAFE_BYTE_MASK)
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
private const val FLAC_METADATA_BLOCK_HEADER_SIZE = 4
private const val FLAC_LAST_METADATA_BLOCK_FLAG = 0x80
private const val FLAC_METADATA_BLOCK_TYPE_MASK = 0x7F
private const val FLAC_VORBIS_COMMENT_BLOCK_TYPE = 4
private const val INT_BYTES = 4
private const val BITS_PER_BYTE = 8
private const val SYNCHSAFE_INTEGER_BYTES = 4
private const val SYNCHSAFE_BITS_PER_BYTE = 7
private const val SYNCHSAFE_BYTE_MASK = 0x7F
private const val MAX_TAG_BLOCK_SIZE = 4 * 1024 * 1024
private const val MAX_FALLBACK_HASH_BYTES = 64 * 1024
private const val MAX_COMMENT_COUNT = 4096L
private val FLAC_SIGNATURE = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
