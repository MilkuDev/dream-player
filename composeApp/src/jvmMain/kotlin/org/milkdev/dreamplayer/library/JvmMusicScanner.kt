package org.milkdev.dreamplayer.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.milkdev.dreamplayer.audio.readJvmAudioFileFormat
import org.milkdev.org.milkdev.dreamplayer.library.CoverSource
import org.milkdev.org.milkdev.dreamplayer.library.MusicScanner
import org.milkdev.org.milkdev.dreamplayer.library.RawTrackData
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioSystem

class JvmMusicScanner : MusicScanner {
    private val logger = Logger.getLogger(JvmMusicScanner::class.java.name)

    override fun scan(): Flow<RawTrackData> = flow {
        val targetDirectory = File(System.getProperty("user.home"), "Music")
        if (targetDirectory.exists() && targetDirectory.isDirectory) {
            val systemAudioFiles = targetDirectory.listFiles { _, title ->
                title.substringAfterLast('.', missingDelimiterValue = "").lowercase() in AUDIO_FILE_EXTENSIONS
            }
            systemAudioFiles.orEmpty().sortedBy { it.name.lowercase() }.forEach { singleFile ->
                val metadata = singleFile.readMetadata(logger)
                if (metadata != null) {
                    emit(
                        RawTrackData(
                            path = singleFile.absolutePath,
                            title = metadata.title ?: singleFile.nameWithoutExtension,
                            artist = metadata.artist,
                            album = metadata.album,
                            durationMs = metadata.durationMs ?: 0L,
                            fileSize = singleFile.length(),
                            lastModified = singleFile.lastModified(),
                            albumArtUri = metadata.albumArtUri,
                            albumArtSource = metadata.albumArtSource,
                        )
                    )
                }
            }
        }
    }

    override fun observeChanges(): Flow<Unit> = flow {
        // TODO Not implemented for desktop yet
    }
}

private fun File.readMetadata(logger: Logger): TrackMetadata? {
    val audioFileFormat = readAudioFileFormat(logger) ?: return null
    val properties: Map<String, Any?> = audioFileFormat.properties()
    val flacMetadata = readFlacMetadata()
    val albumArt = findAlbumArt(flacMetadata?.albumArt ?: embeddedMp3AlbumArt())

    return TrackMetadata(
        title = properties.textValue("title"),
        artist = properties.textValue("author", "artist") ?: flacMetadata?.artist,
        album = properties.textValue("album") ?: flacMetadata?.album,
        durationMs = properties.durationMs() ?: audioFileFormat.durationMs(),
        albumArtUri = albumArt?.uri,
        albumArtSource = albumArt?.source ?: CoverSource.NONE,
        extra = properties.map { it.key to it.value.toString() }.toMap(),
    )
}

private fun File.readAudioFileFormat(logger: Logger): AudioFileFormat? {
    return runCatching {
        readJvmAudioFileFormat()
    }.onFailure { error ->
        logger.log(Level.WARNING, "Skipping unsupported audio file: $absolutePath", error)
    }.getOrNull()
}

private fun File.findAlbumArt(embeddedAlbumArt: EmbeddedAlbumArt?): AlbumArtReference? {
    embeddedAlbumArt?.cacheFor(this)?.toURI()?.toString()?.let { uri ->
        return AlbumArtReference(uri = uri, source = CoverSource.EMBEDDED)
    }

    return findSidecarAlbumArt()?.toURI()?.toString()?.let { uri ->
        AlbumArtReference(uri = uri, source = CoverSource.LOCAL_CACHE)
    }
}

private fun File.embeddedMp3AlbumArt(): EmbeddedAlbumArt? {
    if (!extension.equals("mp3", ignoreCase = true)) return null

    return runCatching {
        RandomAccessFile(this, "r").use { file ->
            val header = ByteArray(ID3_HEADER_SIZE)
            file.readFully(header)
            if (!header.startsWithId3()) return@use null

            val version = header[3].unsigned()
            if (version !in SUPPORTED_ID3_VERSIONS) return@use null

            val tagSize = header.synchsafeInt(6)
            if (tagSize !in 1..MAX_ID3_TAG_SIZE || tagSize > file.length() - ID3_HEADER_SIZE) {
                return@use null
            }

            val tag = ByteArray(tagSize)
            file.readFully(tag)
            tag.findAlbumArt(version, header[5].unsigned())
        }
    }.getOrNull()
}

private fun File.readFlacMetadata(): FlacMetadata? {
    if (!extension.equals("flac", ignoreCase = true)) return null

    return runCatching {
        RandomAccessFile(this, "r").use { file ->
            val signature = ByteArray(FLAC_SIGNATURE.size)
            file.readFully(signature)
            if (!signature.contentEquals(FLAC_SIGNATURE)) return@use null

            var artist: String? = null
            var album: String? = null
            var albumArt: EmbeddedAlbumArt? = null
            var hasMoreBlocks = true

            while (hasMoreBlocks && file.filePointer + FLAC_METADATA_BLOCK_HEADER_SIZE <= file.length()) {
                val typeAndFlags = file.readUnsignedByte()
                hasMoreBlocks = typeAndFlags and FLAC_LAST_METADATA_BLOCK_FLAG == 0
                val blockType = typeAndFlags and FLAC_METADATA_BLOCK_TYPE_MASK
                val blockSize = file.readUnsignedMedium()
                val blockEnd = file.filePointer + blockSize
                if (blockEnd > file.length()) break

                when (blockType) {
                    FLAC_VORBIS_COMMENT_BLOCK_TYPE -> {
                        if (blockSize <= MAX_FLAC_COMMENT_BLOCK_SIZE) {
                            val comments = ByteArray(blockSize).also(file::readFully).parseFlacComments()
                            artist = artist ?: comments["artist"]
                            album = album ?: comments["album"]
                        }
                    }

                    FLAC_PICTURE_BLOCK_TYPE -> {
                        if (blockSize <= MAX_FLAC_PICTURE_BLOCK_SIZE) {
                            val picture = ByteArray(blockSize).also(file::readFully).parseFlacPicture()
                            if (albumArt == null || picture?.pictureType == FRONT_COVER_PICTURE_TYPE) {
                                albumArt = picture ?: albumArt
                            }
                        }
                    }
                }

                file.seek(blockEnd)
            }

            FlacMetadata(artist, album, albumArt)
        }
    }.getOrNull()
}

private fun ByteArray.parseFlacComments(): Map<String, String> {
    var offset = 0
    val vendorSize = littleEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return emptyMap()
    offset = vendorSize

    val commentCount = littleEndianUnsignedInt(offset) ?: return emptyMap()
    if (commentCount > MAX_FLAC_COMMENT_COUNT) return emptyMap()
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
        if (value.isNotEmpty()) {
            comments.putIfAbsent(key, value)
        }
    }

    return comments
}

private fun ByteArray.parseFlacPicture(): EmbeddedAlbumArt? {
    var offset = 0
    val pictureType = bigEndianUnsignedInt(offset)?.toInt() ?: return null
    offset += INT_BYTES

    val mimeEnd = bigEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return null
    val mimeType = decodeToString(offset + INT_BYTES, mimeEnd)
    if (mimeType == FLAC_LINKED_PICTURE_MIME_TYPE) return null
    offset = mimeEnd

    val descriptionEnd = bigEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return null
    offset = descriptionEnd

    if (offset + FLAC_PICTURE_DIMENSIONS_SIZE > size) return null
    offset += FLAC_PICTURE_DIMENSIONS_SIZE

    val imageEnd = bigEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return null
    val imageBytes = copyOfRange(offset + INT_BYTES, imageEnd)
    if (imageBytes.isEmpty() || imageBytes.size > MAX_ALBUM_ART_SIZE) return null
    return EmbeddedAlbumArt(imageBytes, mimeType, pictureType)
}

private fun ByteArray.findAlbumArt(version: Int, headerFlags: Int): EmbeddedAlbumArt? {
    var offset = extendedHeaderSize(version, headerFlags)
    var fallbackArt: EmbeddedAlbumArt? = null

    while (offset < size) {
        val frameHeaderSize = if (version == 2) ID3_V2_FRAME_HEADER_SIZE else ID3_V3_FRAME_HEADER_SIZE
        if (offset + frameHeaderSize > size || this[offset] == 0.toByte()) return fallbackArt

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
        if (frameSize <= 0 || frameEnd > size) return fallbackArt

        if (frameId == "APIC" || frameId == "PIC") {
            val payload = copyOfRange(offset + frameHeaderSize, frameEnd.toInt())
            val art = payload.parsePictureFrame(frameId)
            if (art?.pictureType == FRONT_COVER_PICTURE_TYPE) return art
            if (fallbackArt == null) fallbackArt = art
        }

        offset = frameEnd.toInt()
    }

    return fallbackArt
}

private fun ByteArray.extendedHeaderSize(version: Int, headerFlags: Int): Int {
    if (headerFlags and EXTENDED_HEADER_FLAG == 0 || size < 4) return 0

    val declaredSize = if (version == 4) synchsafeInt(0) else bigEndianInt(0, 4) + 4
    return declaredSize.coerceIn(0, size)
}

private fun ByteArray.parsePictureFrame(frameId: String): EmbeddedAlbumArt? {
    if (isEmpty()) return null

    val encoding = this[0].unsigned()
    var offset = 1
    val mimeType = if (frameId == "PIC") {
        if (size < offset + 3) return null
        decodeToString(offset, offset + 3).toPictureMimeType().also { offset += 3 }
    } else {
        val mimeEnd = indexOfZero(offset)
        if (mimeEnd < 0) return null
        decodeToString(offset, mimeEnd).also { offset = mimeEnd + 1 }
    }

    if (offset >= size) return null
    val pictureType = this[offset++].unsigned()
    offset = skipEncodedText(offset, encoding)
    if (offset >= size) return null

    val imageBytes = copyOfRange(offset, size)
    if (imageBytes.isEmpty() || imageBytes.size > MAX_ALBUM_ART_SIZE) return null
    return EmbeddedAlbumArt(imageBytes, mimeType, pictureType)
}

private fun ByteArray.skipEncodedText(startIndex: Int, encoding: Int): Int {
    if (encoding != UTF_16_ENCODING && encoding != UTF_16_BE_ENCODING) {
        val endIndex = indexOfZero(startIndex)
        return if (endIndex < 0) size else endIndex + 1
    }

    var index = startIndex
    while (index + 1 < size) {
        if (this[index] == 0.toByte() && this[index + 1] == 0.toByte()) return index + 2
        index += 2
    }
    return size
}

private fun EmbeddedAlbumArt.cacheFor(sourceFile: File): File? {
    val cacheDirectory = File(System.getProperty("java.io.tmpdir"), ALBUM_ART_CACHE_DIRECTORY)
    val cacheFile = File(cacheDirectory, "${sourceFile.albumArtCacheKey()}.${fileExtension()}")

    return runCatching {
        if (!cacheFile.isFile || cacheFile.length() != bytes.size.toLong()) {
            cacheDirectory.mkdirs()
            cacheFile.writeBytes(bytes)
        }
        cacheFile
    }.getOrNull()
}

private fun File.findSidecarAlbumArt(): File? {
    val candidates = parentFile?.listFiles { file ->
        file.isFile && file.extension.lowercase() in IMAGE_FILE_EXTENSIONS
    }.orEmpty()
    val trackName = nameWithoutExtension

    return candidates.firstOrNull { it.nameWithoutExtension.equals(trackName, ignoreCase = true) }
        ?: SIDECAR_ALBUM_ART_NAMES.firstNotNullOfOrNull { preferredName ->
            candidates.firstOrNull { it.nameWithoutExtension.equals(preferredName, ignoreCase = true) }
        }
}

private fun File.albumArtCacheKey(): String {
    val sourceIdentity = "$absolutePath:${length()}:${lastModified()}"
    return MessageDigest.getInstance("SHA-256")
        .digest(sourceIdentity.toByteArray())
        .take(CACHE_KEY_BYTES)
        .joinToString("") { byte -> "%02x".format(byte.unsigned()) }
}

private fun EmbeddedAlbumArt.fileExtension(): String {
    return when {
        mimeType.equals("image/png", ignoreCase = true) -> "png"
        mimeType.equals("image/gif", ignoreCase = true) -> "gif"
        mimeType.equals("image/webp", ignoreCase = true) -> "webp"
        bytes.startsWith(PNG_SIGNATURE) -> "png"
        bytes.startsWith(GIF_SIGNATURE) -> "gif"
        bytes.startsWith(WEBP_RIFF_SIGNATURE) -> "webp"
        else -> "jpg"
    }
}

private fun String.toPictureMimeType(): String {
    return when {
        equals("PNG", ignoreCase = true) -> "image/png"
        equals("GIF", ignoreCase = true) -> "image/gif"
        equals("JPG", ignoreCase = true) -> "image/jpeg"
        else -> this
    }
}

private fun ByteArray.startsWithId3(): Boolean {
    return size >= 3 && this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() &&
        this[2] == '3'.code.toByte()
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}

private fun ByteArray.indexOfZero(startIndex: Int): Int {
    return (startIndex until size).firstOrNull { index -> this[index] == 0.toByte() } ?: -1
}

private fun ByteArray.synchsafeInt(startIndex: Int): Int {
    if (startIndex < 0 || startIndex + SYNCHSAFE_INTEGER_BYTES > size) return -1
    return (startIndex until startIndex + SYNCHSAFE_INTEGER_BYTES).fold(0) { value, index ->
        (value shl SYNCHSAFE_BITS_PER_BYTE) or (this[index].unsigned() and SYNCHSAFE_BYTE_MASK)
    }
}

private fun ByteArray.bigEndianInt(
    startIndex: Int,
    byteCount: Int,
    sanitize: (Int) -> Int = { it }
): Int {
    if (startIndex < 0 || startIndex + byteCount > size) return -1
    return (startIndex until startIndex + byteCount).fold(0) { value, index ->
        (value shl BITS_PER_BYTE) or sanitize(this[index].unsigned())
    }
}

private fun ByteArray.bigEndianUnsignedInt(startIndex: Int): Long? {
    if (startIndex < 0 || startIndex + INT_BYTES > size) return null
    return (startIndex until startIndex + INT_BYTES).fold(0L) { value, index ->
        (value shl BITS_PER_BYTE) or this[index].unsigned().toLong()
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

private fun RandomAccessFile.readUnsignedMedium(): Int {
    return (readUnsignedByte() shl (BITS_PER_BYTE * 2)) or
        (readUnsignedByte() shl BITS_PER_BYTE) or
        readUnsignedByte()
}

private fun Byte.unsigned(): Int = toInt() and UNSIGNED_BYTE_MASK

private fun Map<String, Any?>.textValue(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        get(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun Map<String, Any?>.durationMs(): Long? {
    val durationMicros = (get("duration") as? Number)?.toLong()
        ?: get("duration")?.toString()?.toLongOrNull()
    return durationMicros?.takeIf { it >= 0L }?.div(MICROSECONDS_PER_MILLISECOND)
}

private fun AudioFileFormat.durationMs(): Long? {
    val frameRate = format.frameRate
    if (frameLength == AudioSystem.NOT_SPECIFIED || frameRate <= 0f) return null
    return (frameLength * MILLISECONDS_PER_SECOND / frameRate).toLong()
}

private data class TrackMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val albumArtUri: String?,
    val albumArtSource: CoverSource,
    /**
     * For example:
     * - mp3.framerate.fps = 41.666668
     * - mp3.channels = 2
     * - mp3.vbr = false
     * - ...
     */
    val extra: Map<String, String>,
)

private data class AlbumArtReference(
    val uri: String,
    val source: CoverSource,
)

private class EmbeddedAlbumArt(
    val bytes: ByteArray,
    val mimeType: String,
    val pictureType: Int
)

private data class FlacMetadata(
    val artist: String?,
    val album: String?,
    val albumArt: EmbeddedAlbumArt?
)

private const val MICROSECONDS_PER_MILLISECOND = 1_000L
private const val MILLISECONDS_PER_SECOND = 1_000L
private const val ID3_HEADER_SIZE = 10
private const val ID3_V2_FRAME_HEADER_SIZE = 6
private const val ID3_V3_FRAME_HEADER_SIZE = 10
private const val EXTENDED_HEADER_FLAG = 0x40
private const val FRONT_COVER_PICTURE_TYPE = 3
private const val UTF_16_ENCODING = 1
private const val UTF_16_BE_ENCODING = 2
private const val SYNCHSAFE_BYTE_MASK = 0x7F
private const val SYNCHSAFE_INTEGER_BYTES = 4
private const val SYNCHSAFE_BITS_PER_BYTE = 7
private const val UNSIGNED_BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val INT_BYTES = 4
private const val MAX_ID3_TAG_SIZE = 32 * 1024 * 1024
private const val MAX_ALBUM_ART_SIZE = 16 * 1024 * 1024
private const val FLAC_METADATA_BLOCK_HEADER_SIZE = 4
private const val FLAC_LAST_METADATA_BLOCK_FLAG = 0x80
private const val FLAC_METADATA_BLOCK_TYPE_MASK = 0x7F
private const val FLAC_VORBIS_COMMENT_BLOCK_TYPE = 4
private const val FLAC_PICTURE_BLOCK_TYPE = 6
private const val FLAC_PICTURE_DIMENSIONS_SIZE = 4 * INT_BYTES
private const val MAX_FLAC_COMMENT_BLOCK_SIZE = 1024 * 1024
private const val MAX_FLAC_PICTURE_BLOCK_SIZE = MAX_ALBUM_ART_SIZE
private const val MAX_FLAC_COMMENT_COUNT = 4096L
private const val FLAC_LINKED_PICTURE_MIME_TYPE = "-->"
private const val CACHE_KEY_BYTES = 12
private const val ALBUM_ART_CACHE_DIRECTORY = "dreamplayer/album-art"
private val SUPPORTED_ID3_VERSIONS = 2..4
private val AUDIO_FILE_EXTENSIONS = setOf("flac", "mp3", "wav")
private val IMAGE_FILE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
private val SIDECAR_ALBUM_ART_NAMES = listOf("cover", "folder", "albumart", "front")
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
private val GIF_SIGNATURE = byteArrayOf(0x47, 0x49, 0x46)
private val WEBP_RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
private val FLAC_SIGNATURE = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
