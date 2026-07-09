package org.milkdev.dreamplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.core.graphics.scale

private val imageDecodeSemaphore = Semaphore(permits = 4)
private val imageCacheSizeKb = ((Runtime.getRuntime().maxMemory() / 1024) / 8)
    .coerceIn(4 * 1024L, 32 * 1024L)
    .toInt()
private val imageCache = object : LruCache<String, Bitmap>(imageCacheSizeKb) {
    override fun sizeOf(key: String, value: Bitmap): Int {
        return (value.byteCount / 1024).coerceAtLeast(1)
    }
}
private const val MaxFailedImageCacheEntries = 4096
private val failedImageCache = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
        return size > MaxFailedImageCacheEntries
    }
}

@Composable
actual fun TrackImage(
    uri: String?,
    modifier: Modifier,
    contentDescription: String?,
    fallbackIcon: DrawableResource,
    maxDecodeSizePx: Int,
    loadUncached: Boolean,
) {
    val context = LocalContext.current
    val cacheDecodeSizePx = remember(maxDecodeSizePx) {
        maxDecodeSizePx.normalizedArtworkDecodeSize()
    }
    val cacheKey = remember(uri, cacheDecodeSizePx) {
        uri?.let { "${cacheDecodeSizePx}:$it" }
    }
    val cachedImageBitmap = remember(cacheKey) {
        cacheKey?.let(::getCachedBitmap)?.asImageBitmap()
    }

    val loadUncachedState = rememberUpdatedState(loadUncached)

    val imageBitmap by produceState<ImageBitmap?>(cachedImageBitmap, cacheKey) {

        if (uri == null || cacheKey == null) {
            value = null
            return@produceState
        }

        getCachedBitmap(cacheKey)?.let {
            value = it.asImageBitmap()
            return@produceState
        }

        loadCachedThumbnailBitmap(context, cacheKey)?.let {
            value = it
            return@produceState
        }

        snapshotFlow { loadUncachedState.value }.first { isUncachedAllowed -> isUncachedAllowed }

        if (isFailedImage(cacheKey)) {
            value = null
            return@produceState
        }

        value = loadImageBitmap(
            context = context,
            maxDecodeSizePx = cacheDecodeSizePx,
            uri = uri.toUri(),
            cacheKey = cacheKey,
        )
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

private suspend fun loadCachedThumbnailBitmap(
    context: Context,
    cacheKey: String,
): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        try {
            imageDecodeSemaphore.withPermit {
                getCachedBitmap(cacheKey)?.let {
                    return@withPermit it.asImageBitmap()
                }

                val cacheFile = TrackImageDiskCache.get(context, cacheKey)
                    ?: return@withPermit null
                ensureActive()

                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                ensureActive()

                if (bitmap != null) {
                    putCachedBitmap(cacheKey, bitmap)
                    bitmap.asImageBitmap()
                } else {
                    TrackImageDiskCache.remove(context, cacheKey)
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TrackImageDiskCache.remove(context, cacheKey)
            null
        }
    }
}

private fun getCachedBitmap(cacheKey: String): Bitmap? {
    return synchronized(imageCache) {
        imageCache.get(cacheKey)
    }
}

private fun putCachedBitmap(cacheKey: String, bitmap: Bitmap) {
    synchronized(imageCache) {
        imageCache.put(cacheKey, bitmap)
    }
    clearFailedImage(cacheKey)
}

private fun isFailedImage(cacheKey: String): Boolean {
    return synchronized(failedImageCache) {
        cacheKey in failedImageCache
    }
}

private fun putFailedImage(cacheKey: String) {
    synchronized(failedImageCache) {
        failedImageCache[cacheKey] = true
    }
}

private fun clearFailedImage(cacheKey: String) {
    synchronized(failedImageCache) {
        failedImageCache.remove(cacheKey)
    }
}

private suspend fun loadImageBitmap(
    context: Context,
    maxDecodeSizePx: Int,
    uri: Uri,
    cacheKey: String,
): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        try {
            imageDecodeSemaphore.withPermit {
                getCachedBitmap(cacheKey)?.let {
                    return@withPermit it.asImageBitmap()
                }

                loadSystemThumbnail(context, uri, maxDecodeSizePx)?.let { thumbnail ->
                    ensureActive()
                    val scaledThumbnail = thumbnail.scaleDownTo(maxDecodeSizePx)
                    putCachedBitmap(cacheKey, scaledThumbnail)
                    TrackImageDiskCache.put(context, cacheKey, scaledThumbnail)
                    return@withPermit scaledThumbnail.asImageBitmap()
                }

                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                ensureActive()

                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOptions)
                }

                ensureActive()

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(boundsOptions, maxDecodeSizePx)
                }

                ensureActive()

                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                }

                ensureActive()

                val scaledBitmap = bitmap?.scaleDownTo(maxDecodeSizePx)

                if (scaledBitmap != null) {
                    putCachedBitmap(cacheKey, scaledBitmap)
                    TrackImageDiskCache.put(context, cacheKey, scaledBitmap)
                } else {
                    putFailedImage(cacheKey)
                }

                scaledBitmap?.asImageBitmap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            putFailedImage(cacheKey)
            null
        }
    }
}

private fun loadSystemThumbnail(context: Context, uri: Uri, maxDecodeSizePx: Int): Bitmap? {
    if (!uri.scheme.equals("content", ignoreCase = true)) return null

    val targetSize = maxDecodeSizePx.coerceAtLeast(1)
    return runCatching {
        context.contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
    }.getOrNull()
}

private object TrackImageDiskCache {
    private const val DIRECTORY_NAME = "album-art/thumbnails"
    private const val MAX_BYTES = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 8192
    private const val TRIM_INTERVAL = 32
    private var putsSinceTrim = 0

    @Synchronized
    fun get(context: Context, cacheKey: String): File? {
        val file = fileFor(context, cacheKey)
        if (!file.isFile) return null

        file.setLastModified(System.currentTimeMillis())
        return file
    }

    @Synchronized
    fun put(context: Context, cacheKey: String, bitmap: Bitmap) {
        val directory = directory(context)
        directory.mkdirs()

        val finalFile = fileFor(directory, cacheKey)
        val tempFile = File(directory, "${finalFile.name}.${System.nanoTime()}.tmp")

        runCatching {
            FileOutputStream(tempFile).use { output ->
                val format = if (bitmap.hasAlpha()) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                val quality = if (format == Bitmap.CompressFormat.PNG) {
                    100
                } else {
                    ThumbnailJpegQuality
                }
                check(bitmap.compress(format, quality, output)) {
                    "Unable to encode artwork thumbnail"
                }
            }

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
            trimToSize(directory)
        }
    }

    @Synchronized
    fun remove(context: Context, cacheKey: String) {
        fileFor(context, cacheKey).delete()
    }

    private fun directory(context: Context): File {
        return File(context.applicationContext.cacheDir, DIRECTORY_NAME)
    }

    private fun fileFor(context: Context, cacheKey: String): File {
        return fileFor(directory(context), cacheKey)
    }

    private fun fileFor(directory: File, cacheKey: String): File {
        return File(directory, "${cacheKey.stableCacheHash()}.img")
    }

    private fun trimToSize(directory: File) {
        val cachedFiles = directory.listFiles { file ->
            file.isFile && file.name.endsWith(".img", ignoreCase = true)
        }.orEmpty()

        var currentBytes = cachedFiles.sumOf { it.length() }
        var currentCount = cachedFiles.size
        if (currentBytes <= MAX_BYTES && currentCount <= MAX_ENTRIES) return

        cachedFiles
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (currentBytes <= MAX_BYTES && currentCount <= MAX_ENTRIES) return

                val fileSize = file.length()
                if (file.delete()) {
                    currentBytes -= fileSize
                    currentCount -= 1
                }
            }
    }
}

private fun Bitmap.scaleDownTo(maxDecodeSizePx: Int): Bitmap {
    val largestDimension = max(width, height)
    if (maxDecodeSizePx !in 1..<largestDimension) return this

    val scale = maxDecodeSizePx.toFloat() / largestDimension
    val scaledBitmap = this.scale(
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
    )
    if (scaledBitmap !== this) {
        recycle()
    }
    return scaledBitmap
}

private fun calculateInSampleSize(options: BitmapFactory.Options, maxDecodeSizePx: Int): Int {
    val largestDimension = max(options.outWidth, options.outHeight)
    if (maxDecodeSizePx <= 0 || largestDimension <= 0) return 1

    var sampleSize = 1

    while (largestDimension / (sampleSize * 2) >= maxDecodeSizePx) {
        sampleSize *= 2
    }

    return sampleSize
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

private const val ThumbnailJpegQuality = 86
private const val StableHashSeed = -3750763034362895579L
private const val StableHashPrime = 1099511628211L
