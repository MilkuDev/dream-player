package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.SamplingMode
import org.milkdev.dreamplayer.database.applicationDirectory
import java.io.File
import java.net.URI
import kotlin.math.max
import kotlin.math.roundToInt

private val imageDecodeSemaphore = Semaphore(permits = 2)
private val thumbnailDiskCache = TrackImageDiskCache(
    directory = File(applicationDirectory, "album-art/thumbnails"),
    maxBytes = 256L * 1024L * 1024L,
    maxEntries = 8192,
)

@Composable
actual fun TrackImage(
    uri: String?,
    modifier: Modifier,
    contentDescription: String?,
    fallbackIcon: DrawableResource,
    maxDecodeSizePx: Int,
    loadUncached: Boolean,
) {
    val cacheDecodeSizePx = remember(maxDecodeSizePx) {
        maxDecodeSizePx.normalizedArtworkDecodeSize()
    }
    val cacheKey = remember(uri, cacheDecodeSizePx) {
        uri?.let { "${cacheDecodeSizePx}:$it" }
    }
    val cachedImageBitmap = remember(cacheKey) {
        cacheKey?.let { TrackImageCache.get(it) }
    }
    val imageBitmap by produceState<ImageBitmap?>(cachedImageBitmap, cacheKey, loadUncached) {

        if (uri == null || cacheKey == null) {
            value = null
            return@produceState
        }

        TrackImageCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }

        loadCachedThumbnailBitmap(cacheKey)?.let {
            value = it
            return@produceState
        }

        if (!loadUncached || TrackImageCache.isFailed(cacheKey)) {
            value = null
            return@produceState
        }

        value = loadImageBitmap(uri, cacheDecodeSizePx, cacheKey)
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(fallbackIcon),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

private object TrackImageCache {
    private val MaxBytes = ((Runtime.getRuntime().maxMemory() / 10L)
        .coerceIn(48L * 1024L * 1024L, 128L * 1024L * 1024L))
    private const val MAX_FAILED_ENTRIES = 4096

    private data class Entry(
        val image: ImageBitmap,
        val bytes: Long,
    )

    private val entries = LinkedHashMap<String, Entry>(0, 0.75f, true)
    private val failedEntries = LinkedHashSet<String>()
    private var currentBytes = 0L

    @Synchronized
    fun get(cacheKey: String): ImageBitmap? {
        return entries[cacheKey]?.image
    }

    @Synchronized
    fun put(cacheKey: String, image: ImageBitmap) {
        entries.remove(cacheKey)?.let { currentBytes -= it.bytes }

        val entry = Entry(
            image = image,
            bytes = image.estimatedByteSize(),
        )
        entries[cacheKey] = entry
        currentBytes += entry.bytes
        failedEntries.remove(cacheKey)

        trimToSize()
    }

    @Synchronized
    fun isFailed(cacheKey: String): Boolean {
        return cacheKey in failedEntries
    }

    @Synchronized
    fun putFailed(cacheKey: String) {
        if (failedEntries.add(cacheKey) && failedEntries.size > MAX_FAILED_ENTRIES) {
            val iterator = failedEntries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun trimToSize() {
        val iterator = entries.entries.iterator()
        while (currentBytes > MaxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= eldest.value.bytes
            iterator.remove()
        }
    }
}

private suspend fun loadCachedThumbnailBitmap(cacheKey: String): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        imageDecodeSemaphore.withPermit {
            TrackImageCache.get(cacheKey)?.let { return@withPermit it }

            val cacheFile = thumbnailDiskCache.get(cacheKey) ?: return@withPermit null
            try {
                ensureActive()
                val thumbnailImage = SkiaImage.makeFromEncoded(cacheFile.readBytes())
                thumbnailImage.use { thumbnailImage ->
                    ensureActive()
                    val imageBitmap = thumbnailImage.toComposeImageBitmap()
                    TrackImageCache.put(cacheKey, imageBitmap)
                    imageBitmap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                thumbnailDiskCache.remove(cacheKey)
                null
            }
        }
    }
}

private suspend fun loadImageBitmap(
    uri: String,
    maxDecodeSizePx: Int,
    cacheKey: String,
): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        imageDecodeSemaphore.withPermit {
            TrackImageCache.get(cacheKey)?.let { return@withPermit it }

            try {
                ensureActive()

                val sourceImage = SkiaImage.makeFromEncoded(uri.toImageFile().readBytes())
                val scaledImage = sourceImage.scaleDownTo(maxDecodeSizePx)

                try {
                    ensureActive()
                    val imageBitmap = scaledImage.toComposeImageBitmap()
                    ensureActive()
                    TrackImageCache.put(cacheKey, imageBitmap)
                    scaledImage.encodeThumbnailBytes()?.let { bytes ->
                        thumbnailDiskCache.put(cacheKey, bytes)
                    }
                    imageBitmap
                } finally {
                    if (scaledImage !== sourceImage) {
                        scaledImage.close()
                    } else {
                        sourceImage.close()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TrackImageCache.putFailed(cacheKey)
                null
            }
        }
    }
}

private class TrackImageDiskCache(
    private val directory: File,
    private val maxBytes: Long,
    private val maxEntries: Int,
) {
    private var putsSinceTrim = 0

    @Synchronized
    fun get(cacheKey: String): File? {
        val file = fileFor(cacheKey)
        if (!file.isFile) return null

        file.setLastModified(System.currentTimeMillis())
        return file
    }

    @Synchronized
    fun put(cacheKey: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return

        directory.mkdirs()
        val finalFile = fileFor(cacheKey)
        val tempFile = File(directory, "${finalFile.name}.${System.nanoTime()}.tmp")

        runCatching {
            tempFile.writeBytes(bytes)
            if (finalFile.exists()) {
                finalFile.delete()
            }
            check(tempFile.renameTo(finalFile)) {
                "Unable to move artwork thumbnail into cache"
            }
            finalFile.setLastModified(System.currentTimeMillis())
        }.onFailure {
            tempFile.delete()
        }.getOrElse {
            return
        }

        putsSinceTrim += 1
        if (putsSinceTrim >= TRIM_INTERVAL) {
            putsSinceTrim = 0
            trimToSize()
        }
    }

    @Synchronized
    fun remove(cacheKey: String) {
        fileFor(cacheKey).delete()
    }

    private fun fileFor(cacheKey: String): File {
        return File(directory, "${cacheKey.stableCacheHash()}.img")
    }

    private fun trimToSize() {
        val cachedFiles = directory.listFiles { file ->
            file.isFile && file.name.endsWith(".img", ignoreCase = true)
        }.orEmpty()

        var currentBytes = cachedFiles.sumOf { it.length() }
        var currentCount = cachedFiles.size
        if (currentBytes <= maxBytes && currentCount <= maxEntries) return

        cachedFiles
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (currentBytes <= maxBytes && currentCount <= maxEntries) return

                val fileSize = file.length()
                if (file.delete()) {
                    currentBytes -= fileSize
                    currentCount -= 1
                }
            }
    }

    private companion object {
        const val TRIM_INTERVAL = 32
    }
}

private fun SkiaImage.scaleDownTo(maxDecodeSizePx: Int): SkiaImage {
    val largestDimension = max(width, height)
    if (maxDecodeSizePx !in 1..<largestDimension) return this

    val scale = maxDecodeSizePx.toFloat() / largestDimension
    return use {
        Bitmap().use { resizedBitmap ->
            check(
                resizedBitmap.allocN32Pixels(
                    width = (width * scale).roundToInt().coerceAtLeast(1),
                    height = (height * scale).roundToInt().coerceAtLeast(1)
                )
            )
            checkNotNull(resizedBitmap.peekPixels()).use { pixels ->
                check(scalePixels(pixels, SamplingMode.LINEAR, false))
            }
            SkiaImage.makeFromBitmap(resizedBitmap)
        }
    }
}

private fun SkiaImage.encodeThumbnailBytes(): ByteArray? {
    val jpegData = encodeToData(EncodedImageFormat.JPEG, ThumbnailJpegQuality)
    jpegData?.use { jpegData ->
        return jpegData.bytes
    }

    val pngData = encodeToData(EncodedImageFormat.PNG, 100) ?: return null
    return pngData.use { pngData ->
        pngData.bytes
    }
}

private fun ImageBitmap.estimatedByteSize(): Long {
    return width.toLong() * height.toLong() * 4L
}

private fun Int.normalizedArtworkDecodeSize(): Int {
    return when {
        this <= 0 -> this
        this <= 192 -> 192
        this <= 320 -> 320
        this <= 512 -> 512
        else -> this
    }
}

private fun String.stableCacheHash(): String {
    var hash = StableHashSeed
    for (character in this) {
        hash = (hash xor character.code.toLong()) * StableHashPrime
    }
    return hash.toULong().toString(16)
}

private fun String.toImageFile(): File {
    val parsedUri = runCatching { URI(this) }.getOrNull()
    return if (parsedUri != null && "file".equals(parsedUri.scheme, ignoreCase = true)) {
        File(parsedUri)
    } else {
        File(this)
    }
}

private const val ThumbnailJpegQuality = 86
private const val StableHashSeed = -3750763034362895579L
private const val StableHashPrime = 1099511628211L
