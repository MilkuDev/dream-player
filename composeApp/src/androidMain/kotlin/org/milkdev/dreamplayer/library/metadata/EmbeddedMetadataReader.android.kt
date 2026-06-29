package org.milkdev.dreamplayer.library.metadata

import androidx.core.net.toUri
import org.milkdev.dreamplayer.app.applicationContext
import org.milkdev.dreamplayer.library.RawTrackData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

actual object EmbeddedMetadataReader {
    actual suspend fun read(rawTrack: RawTrackData): EmbeddedMetadata? {
        val path = rawTrack.path.takeIf { it.isNotBlank() } ?: return null
        val tagBytes = runCatching {
            openMetadataStream(path)?.use { it.readBytes(MAX_TAG_SCAN_BYTES) }
        }.getOrNull() ?: return null

        val parsed = when {
            tagBytes.startsWith(FLAC_SIGNATURE) -> tagBytes.parseFlacEmbeddedMetadata()
            tagBytes.startsWithId3() -> tagBytes.parseMp3EmbeddedMetadata()
            else -> EmbeddedMetadata(tagFingerprint = tagBytes.take(MAX_FALLBACK_HASH_BYTES).toByteArray().sha256())
        }

        return parsed.copy(
            tagFingerprint = "${rawTrack.fileSize}:${rawTrack.lastModified}:${parsed.tagFingerprint}",
        )
    }
}

private fun openMetadataStream(path: String): InputStream? {
    return if (path.startsWith("content://", ignoreCase = true)) {
        applicationContext.contentResolver.openInputStream(path.toUri())
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
    val version = this[3].unsigned()
    val tagSize = synchsafeInt(6)
    if (version !in SUPPORTED_ID3_VERSIONS || tagSize <= 0 || ID3_HEADER_SIZE + tagSize > size) {
        return EmbeddedMetadata(tagFingerprint = take(MAX_FALLBACK_HASH_BYTES).toByteArray().sha256())
    }
    val tag = copyOfRange(ID3_HEADER_SIZE, ID3_HEADER_SIZE + tagSize)
    return tag.parseId3TextFrames(version).toEmbeddedMetadata(tag.sha256())
}

private fun ByteArray.parseFlacComments(): Map<String, String> {
    var offset = 0
    val vendorSize = littleEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return emptyMap()
    offset = vendorSize

    val commentCount = littleEndianUnsignedInt(offset) ?: return emptyMap()
    if (commentCount > MAX_COMMENT_COUNT) return emptyMap()
    offset += INT_BYTES

    val comments = mutableMapOf<String, String>()
    repeat(commentCount.toInt()) {
        val commentEnd = littleEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return comments
        val comment = decodeToString(offset + INT_BYTES, commentEnd)
        offset = commentEnd

        val separatorIndex = comment.indexOf('=')
        if (separatorIndex <= 0) return@repeat
        val key = comment.substring(0, separatorIndex).lowercase()
        val value = comment.substring(separatorIndex + 1).trim()
        if (value.isNotEmpty()) comments.putIfAbsent(key, value)
    }
    return comments
}

private fun ByteArray.parseId3TextFrames(version: Int): Map<String, String> {
    val frames = mutableMapOf<String, String>()
    var offset = 0
    while (offset < size) {
        val frameHeaderSize = if (version == 2) ID3_V2_FRAME_HEADER_SIZE else ID3_V3_FRAME_HEADER_SIZE
        if (offset + frameHeaderSize > size || this[offset] == 0.toByte()) return frames

        val frameIdLength = if (version == 2) 3 else 4
        val frameId = decodeToString(offset, offset + frameIdLength)
        val frameSize = when (version) {
            2 -> {
                bigEndianInt(offset + 3, 3)
            }
            4 -> {
                synchsafeInt(offset + 4)
            }
            else -> {
                bigEndianInt(offset + 4, 4)
            }
        }
        val frameEnd = offset.toLong() + frameHeaderSize + frameSize
        if (frameSize <= 0 || frameEnd > size) return frames

        val payload = copyOfRange(offset + frameHeaderSize, frameEnd.toInt())
        when {
            frameId == "TXXX" -> payload.parseId3UserTextFrame()?.let { (key, value) ->
                frames.putIfAbsent(key.lowercase(), value)
            }
            frameId.startsWith("T") -> payload.parseId3TextFrame()?.let { value ->
                frames.putIfAbsent(frameId.lowercase(), value)
            }
        }
        offset = frameEnd.toInt()
    }
    return frames
}

private fun ByteArray.parseId3UserTextFrame(): Pair<String, String>? {
    if (isEmpty()) return null
    val encoding = this[0].unsigned()
    val descriptionEnd = encodedTextEnd(1, encoding)
    if (descriptionEnd < 0) return null
    val description = decodeId3Text(1, descriptionEnd, encoding).trim()
    val valueStart = descriptionEnd + encodedTerminatorSize(encoding)
    if (description.isBlank() || valueStart >= size) return null
    val value = decodeId3Text(valueStart, size, encoding).trim().trim('\u0000')
    return description to value
}

private fun ByteArray.parseId3TextFrame(): String? {
    if (isEmpty()) return null
    val encoding = this[0].unsigned()
    return decodeId3Text(1, size, encoding).trim().trim('\u0000').takeIf { it.isNotBlank() }
}

private fun Map<String, String>.toEmbeddedMetadata(fingerprint: String): EmbeddedMetadata {
    return EmbeddedMetadata(
        recordingMbid = firstMusicBrainzId(valueFor("musicbrainz_trackid", "musicbrainz track id")),
        releaseMbid = firstMusicBrainzId(valueFor("musicbrainz_albumid", "musicbrainz album id")),
        releaseGroupMbid = firstMusicBrainzId(valueFor("musicbrainz_releasegroupid", "musicbrainz release group id")),
        artistMbids = splitMusicBrainzIds(valueFor("musicbrainz_artistid", "musicbrainz artist id")),
        albumArtistMbids = splitMusicBrainzIds(valueFor("musicbrainz_albumartistid", "musicbrainz album artist id")),
        year = parseEmbeddedYear(valueFor("date", "year", "tdrc", "tyer")),
        genres = splitEmbeddedGenres(valueFor("genre", "tcon")),
        tagFingerprint = fingerprint,
    )
}

private fun Map<String, String>.valueFor(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key -> this[key.lowercase()]?.takeIf(String::isNotBlank) }
}

private fun ByteArray.decodeId3Text(start: Int, end: Int, encoding: Int): String {
    val charset = when (encoding) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }
    return String(this, start, (end - start).coerceAtLeast(0), charset)
}

private fun ByteArray.encodedTextEnd(startIndex: Int, encoding: Int): Int {
    if (encoding != UTF_16_ENCODING && encoding != UTF_16_BE_ENCODING) {
        return indexOfZero(startIndex)
    }
    var index = startIndex
    while (index + 1 < size) {
        if (this[index] == 0.toByte() && this[index + 1] == 0.toByte()) return index
        index += 2
    }
    return -1
}

private fun encodedTerminatorSize(encoding: Int): Int {
    return if (encoding == UTF_16_ENCODING || encoding == UTF_16_BE_ENCODING) 2 else 1
}

private fun ByteArray.startsWithId3(): Boolean {
    return size >= 3 && this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() &&
        this[2] == '3'.code.toByte()
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

private fun ByteArray.indexOfZero(startIndex: Int): Int {
    return (startIndex until size).firstOrNull { index -> this[index] == 0.toByte() } ?: -1
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
private const val ID3_V2_FRAME_HEADER_SIZE = 6
private const val ID3_V3_FRAME_HEADER_SIZE = 10
private const val FLAC_METADATA_BLOCK_HEADER_SIZE = 4
private const val FLAC_LAST_METADATA_BLOCK_FLAG = 0x80
private const val FLAC_METADATA_BLOCK_TYPE_MASK = 0x7F
private const val FLAC_VORBIS_COMMENT_BLOCK_TYPE = 4
private const val INT_BYTES = 4
private const val BITS_PER_BYTE = 8
private const val SYNCHSAFE_INTEGER_BYTES = 4
private const val SYNCHSAFE_BITS_PER_BYTE = 7
private const val SYNCHSAFE_BYTE_MASK = 0x7F
private const val UTF_16_ENCODING = 1
private const val UTF_16_BE_ENCODING = 2
private const val MAX_TAG_SCAN_BYTES = 4 * 1024 * 1024
private const val MAX_FALLBACK_HASH_BYTES = 64 * 1024
private const val MAX_COMMENT_COUNT = 4096L
private val SUPPORTED_ID3_VERSIONS = 2..4
private val FLAC_SIGNATURE = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
