@file:Suppress("SpellCheckingInspection")

package org.milkdev.dreamplayer.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.milkdev.dreamplayer.audio.readJvmAudioFileFormat
import org.milkdev.dreamplayer.database.applicationDirectory
import org.milkdev.dreamplayer.library.metadata.parseId3Tag
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
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
            val sidecarCache = mutableMapOf<File, List<File>?>()
            val systemAudioFiles = targetDirectory
                .walkTopDown()
                .maxDepth(RECURSIVE_MAX_DEPTH)
                .onEnter { dir -> !dir.name.startsWith(".") }
                .filter { file ->
                    file.isFile &&
                        file.extension.lowercase() in AUDIO_FILE_EXTENSIONS
                }
                .sortedBy { it.absolutePath.lowercase() }
                .toList()
            systemAudioFiles.forEach { singleFile ->
                val metadata = singleFile.readMetadata(logger, sidecarCache)
                if (metadata != null) {
                    emit(
                        RawTrackData(
                            path = singleFile.absolutePath,
                            title = metadata.title ?: singleFile.nameWithoutExtension,
                            artist = metadata.artist,
                            album = metadata.album,
                            albumArtist = metadata.albumArtist,
                            durationMs = metadata.durationMs ?: 0L,
                            fileSize = singleFile.length(),
                            lastModified = singleFile.lastModified(),
                            albumArtUri = metadata.albumArtUri,
                            albumArtSource = metadata.albumArtSource,
                            albumArtworkKey = metadata.albumArtUri,
                            localArtworkState = if (metadata.albumArtUri.isNullOrBlank()) {
                                LocalArtworkState.MISSING
                            } else {
                                LocalArtworkState.PRESENT
                            },
                        )
                    )
                }
            }
        }
    }

    override fun observeChanges(): Flow<Unit> = emptyFlow()

    override suspend fun probeArtwork(candidate: ArtworkProbeCandidate): ArtworkProbeResult {
        var sawUnknown = false
        val allUris = candidate.albumUris + candidate.sampleTrackUris

        for (uri in allUris) {
            val file = uri.toFileOrNull() ?: continue
            val isFileExist = runCatching { file.isFile }.getOrElse {
                sawUnknown = true
                false
            }
            if (isFileExist && file.extension.lowercase() in IMAGE_FILE_EXTENSIONS) {
                return ArtworkProbeResult(
                    requestId = candidate.requestId,
                    state = LocalArtworkState.PRESENT,
                    artworkUri = file.toURI().toString(),
                )
            }
        }

        val sidecarCache = mutableMapOf<File, List<File>?>()
        for (trackUri in candidate.sampleTrackUris) {
            val trackFile = trackUri.toFileOrNull() ?: continue
            val parent = trackFile.parentFile
            if (parent != null) {
                val canReadParent = runCatching { parent.exists() && parent.canRead() }.getOrElse {
                    sawUnknown = true
                    false
                }
                if (!canReadParent) {
                    sawUnknown = true
                    continue
                }
            }

            val sidecar = trackFile.findSidecarAlbumArt(sidecarCache)
            if (sidecar != null) {
                return ArtworkProbeResult(
                    requestId = candidate.requestId,
                    state = LocalArtworkState.PRESENT,
                    artworkUri = sidecar.toURI().toString(),
                )
            }
        }

        return ArtworkProbeResult(
            requestId = candidate.requestId,
            state = if (sawUnknown) LocalArtworkState.UNKNOWN else LocalArtworkState.MISSING,
        )
    }
}

private fun File.readMetadata(
    logger: Logger,
    sidecarCache: MutableMap<File, List<File>?>? = null,
): TrackMetadata? {
    val audioFileFormat = readAudioFileFormat(logger) ?: return null
    val properties: Map<String, Any?> = audioFileFormat.properties()
    val flacMetadata = readFlacMetadata()
    val embeddedMp3Metadata = readEmbeddedMp3Metadata(logger)
    val albumArt = findAlbumArt(
        embeddedAlbumArt = flacMetadata?.albumArt ?: embeddedMp3Metadata?.albumArt,
        sidecarCache = sidecarCache,
    )

    return TrackMetadata(
        title = properties.textValue("title"),
        artist = properties.textValue("author", "artist") ?: flacMetadata?.artist,
        album = properties.textValue("album") ?: flacMetadata?.album,
        albumArtist = properties.textValue("albumartist", "album artist", "albumArtist", "band")
            ?: flacMetadata?.albumArtist
            ?: embeddedMp3Metadata?.albumArtist,
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

private fun File.findAlbumArt(
    embeddedAlbumArt: EmbeddedAlbumArt?,
    sidecarCache: MutableMap<File, List<File>?>? = null,
): AlbumArtReference? {
    embeddedAlbumArt?.cacheFor(this)?.toURI()?.toString()?.let { uri ->
        return AlbumArtReference(uri = uri, source = CoverSource.EMBEDDED)
    }

    return findSidecarAlbumArt(sidecarCache)?.toURI()?.toString()?.let { uri ->
        AlbumArtReference(uri = uri, source = CoverSource.LOCAL_CACHE)
    }
}

private fun File.readEmbeddedMp3Metadata(logger: Logger): EmbeddedMp3Metadata? {
    if (!extension.equals("mp3", ignoreCase = true)) return null

    return runCatching {
        RandomAccessFile(this, "r").use { file ->
            val header = ByteArray(ID3_HEADER_SIZE)
            file.readFully(header)
            if (!header.startsWithId3()) return@use null

            val tagSize = header.synchsafeInt(6)
            if (tagSize !in 1..MAX_ID3_TAG_SIZE || tagSize > file.length() - ID3_HEADER_SIZE) {
                return@use null
            }

            val tag = ByteArray(tagSize)
            file.readFully(tag)
            val parsedTag = parseId3Tag(
                header = header,
                body = tag,
                maxPictureBytes = MAX_ALBUM_ART_SIZE,
            ) ?: return@use null
            if (parsedTag.issues.isNotEmpty()) {
                logger.log(
                    Level.FINE,
                    "ID3 metadata parse issues: ${
                        parsedTag.issues.joinToString(",") { issue -> issue.name.lowercase() }
                    }",
                )
            }
            EmbeddedMp3Metadata(
                albumArt = parsedTag.preferredPicture()?.let { picture ->
                    EmbeddedAlbumArt(
                        bytes = picture.bytes,
                        mimeType = picture.mimeType,
                        pictureType = picture.pictureType,
                    )
                },
                albumArtist = parsedTag.values["tpe2"]?.firstOrNull(),
            )
        }
    }.onFailure { error ->
        logger.log(Level.FINE, "Unable to parse embedded MP3 metadata", error)
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
            var albumArtist: String? = null
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
                            albumArtist = albumArtist ?: comments["albumartist"]
                                ?: comments["album artist"]
                                ?: comments["album_artist"]
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

            FlacMetadata(artist, album, albumArtist, albumArt)
        }
    }.getOrNull()
}

private fun ByteArray.parseFlacComments(): Map<String, String> {
    var offset = 0
    val vendorSize = littleEndianUnsignedInt(offset)?.regionEnd(offset + INT_BYTES, size) ?: return emptyMap()
    offset = vendorSize  // TODO: something

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

private fun EmbeddedAlbumArt.cacheFor(sourceFile: File): File? {
    val cacheDirectory = File(applicationDirectory, ALBUM_ART_CACHE_DIRECTORY)
    val cacheFile = File(cacheDirectory, "${sourceFile.albumArtCacheKey()}.${fileExtension()}")

    return runCatching {
        if (!cacheFile.isFile || cacheFile.length() != bytes.size.toLong()) {
            cacheDirectory.mkdirs()
            val tempFile = File(cacheDirectory, "${cacheFile.name}.tmp")
            tempFile.writeBytes(bytes)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            if (!tempFile.renameTo(cacheFile)) {
                cacheFile.writeBytes(bytes)
                tempFile.delete()
            }
        }
        cacheFile
    }.getOrNull()
}

private fun File.findSidecarAlbumArt(cache: MutableMap<File, List<File>?>? = null): File? {
    val parent = parentFile ?: return null
    val candidates = if (cache != null && cache.containsKey(parent)) {
        cache[parent] ?: return null
    } else {
        val listResult = runCatching { parent.listFiles() }.getOrNull()
        if (listResult == null) {
            cache?.put(parent, null)
            return null
        }
        listResult.filter { file ->
            file.isFile && file.extension.lowercase() in IMAGE_FILE_EXTENSIONS
        }.also { filtered ->
            cache?.put(parent, filtered)
        }
    }
    if (candidates.isEmpty()) return null
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
    val albumArtist: String?,
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

private data class EmbeddedMp3Metadata(
    val albumArt: EmbeddedAlbumArt?,
    val albumArtist: String?,
)

private data class FlacMetadata(
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val albumArt: EmbeddedAlbumArt?
)

private const val MICROSECONDS_PER_MILLISECOND = 1_000L
private const val MILLISECONDS_PER_SECOND = 1_000L
private const val ID3_HEADER_SIZE = 10
private const val FRONT_COVER_PICTURE_TYPE = 3
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
private val AUDIO_FILE_EXTENSIONS = setOf("flac", "mp3", "wav")
private val IMAGE_FILE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
private val SIDECAR_ALBUM_ART_NAMES = listOf("cover", "folder", "albumart", "front")
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
private val GIF_SIGNATURE = byteArrayOf(0x47, 0x49, 0x46)
private val WEBP_RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
private val FLAC_SIGNATURE = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
private const val RECURSIVE_MAX_DEPTH = 8

private fun String.toFileOrNull(): File? {
    if (isBlank()) return null
    return runCatching {
        if (startsWith("file:", ignoreCase = true)) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
}
