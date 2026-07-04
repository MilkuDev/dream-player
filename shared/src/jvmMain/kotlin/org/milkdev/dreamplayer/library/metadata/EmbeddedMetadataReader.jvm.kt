@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.library.metadata

import org.milkdev.dreamplayer.library.RawTrackData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

actual object EmbeddedMetadataReader {
    actual suspend fun read(rawTrack: RawTrackData): EmbeddedMetadata? {
        val file = rawTrack.path.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (!file.isFile) return null

        val parsed = when (file.extension.lowercase()) {
            "flac" -> file.readFlacEmbeddedMetadata()
            "mp3" -> file.readMp3EmbeddedMetadata()
            "m4a", "mp4" -> file.readMp4EmbeddedMetadata()
            else -> null
        } ?: file.fallbackEmbeddedMetadata() ?: return null

        return parsed.copy(
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

            val version = header[3].unsigned()
            if (version !in SUPPORTED_ID3_VERSIONS) return@use null

            val tagSize = header.synchsafeInt(6)
            if (tagSize !in 1..MAX_TAG_BLOCK_SIZE || tagSize > file.length() - ID3_HEADER_SIZE) {
                return@use null
            }

            val tag = ByteArray(tagSize).also(file::readFully)
            tag.parseId3TextFrames(version).toEmbeddedMetadata(tag.sha256())
        }
    }.getOrNull()
}

private fun File.readMp4EmbeddedMetadata(): EmbeddedMetadata? {
    return runCatching {
        if (length() > MAX_MP4_SCAN_SIZE) return@runCatching null
        val bytes = readBytes()
        val ilstOffset = bytes.indexOfAscii("ilst")
        if (ilstOffset < 0) return@runCatching null
        val windowStart = (ilstOffset - MP4_TAG_WINDOW).coerceAtLeast(0)
        val windowEnd = (ilstOffset + MP4_TAG_WINDOW).coerceAtMost(bytes.size)
        val window = bytes.copyOfRange(windowStart, windowEnd)
        window.parseMp4FreeformTags().toEmbeddedMetadata(window.sha256())
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
    val descriptionEnd = encodedTextEnd(startIndex = 1, encoding = encoding)
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

private fun ByteArray.parseMp4FreeformTags(): Map<String, String> {
    val text = decodeToString()
    return buildMap {
        MP4_FREEFORM_KEYS.forEach { key ->
            val index = text.indexOf(key, ignoreCase = true)
            if (index >= 0) {
                val value = text.drop(index + key.length)
                    .take(MP4_VALUE_SCAN_LENGTH)
                    .filter { it.code in 32..126 }
                    .trim()
                if (value.isNotBlank()) put(key.lowercase(), value)
            }
        }
    }
}

private fun Map<String, String>.toEmbeddedMetadata(fingerprint: String): EmbeddedMetadata {
    return EmbeddedMetadata(
        recordingMbid = firstMusicBrainzId(
            valueFor(
                "musicbrainz_trackid",
                "musicbrainz track id",
                "ufid",
                "txxx:musicbrainz track id"
            )
        ),
        releaseMbid = firstMusicBrainzId(valueFor("musicbrainz_albumid", "musicbrainz album id")),
        releaseGroupMbid = firstMusicBrainzId(
            valueFor(
                "musicbrainz_releasegroupid",
                "musicbrainz release group id"
            )
        ),
        artistMbids = splitMusicBrainzIds(
            valueFor(
                "musicbrainz_artistid",
                "musicbrainz artist id"
            )
        ),
        albumArtistMbids = splitMusicBrainzIds(
            valueFor(
                "musicbrainz_albumartistid",
                "musicbrainz album artist id"
            )
        ),
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

private fun ByteArray.indexOfAscii(value: String): Int {
    val pattern = value.encodeToByteArray()
    return indices.firstOrNull { index ->
        index + pattern.size <= size && pattern.indices.all { offset -> this[index + offset] == pattern[offset] }
    } ?: -1
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
private const val MAX_TAG_BLOCK_SIZE = 4 * 1024 * 1024
private const val MAX_FALLBACK_HASH_BYTES = 64 * 1024
private const val MAX_COMMENT_COUNT = 4096L
private const val MAX_MP4_SCAN_SIZE = 20L * 1024 * 1024
private const val MP4_TAG_WINDOW = 512 * 1024
private const val MP4_VALUE_SCAN_LENGTH = 96
private val SUPPORTED_ID3_VERSIONS = 2..4
private val FLAC_SIGNATURE = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
private val MP4_FREEFORM_KEYS = listOf(
    "musicbrainz_trackid",
    "musicbrainz_albumid",
    "musicbrainz_releasegroupid",
    "musicbrainz_artistid",
    "musicbrainz_albumartistid",
    "genre",
    "date",
)
