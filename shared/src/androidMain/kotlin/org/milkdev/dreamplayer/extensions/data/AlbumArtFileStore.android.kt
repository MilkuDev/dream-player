package org.milkdev.dreamplayer.extensions.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.app.applicationContext
import java.io.File
import kotlin.text.iterator

actual fun createAlbumArtFileStore(): AlbumArtFileStore {
    return AndroidAlbumArtFileStore(File(applicationContext.filesDir, "album-art/remote"))
}

private class AndroidAlbumArtFileStore(
    private val directory: File,
) : AlbumArtFileStore {
    override suspend fun saveRemoteAlbumArt(
        albumId: Long,
        sourceUrl: String,
        bytes: ByteArray,
        contentType: String?,
    ): String = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val finalFile = File(
            directory,
            "$albumId-${sourceUrl.stableHash()}.${contentType.fileExtension(sourceUrl)}")
        val tempFile = File(directory, "${finalFile.name}.tmp")
        tempFile.writeBytes(bytes)
        if (finalFile.exists()) {
            finalFile.delete()
        }
        check(tempFile.renameTo(finalFile)) {
            "Unable to move album art cache file into place"
        }
        finalFile.toURI().toString()
    }
}

private fun String?.fileExtension(sourceUrl: String): String {
    return when {
        this?.contains("png", ignoreCase = true) == true -> "png"
        this?.contains("webp", ignoreCase = true) == true -> "webp"
        sourceUrl.substringBefore('?').endsWith(".png", ignoreCase = true) -> "png"
        sourceUrl.substringBefore('?').endsWith(".webp", ignoreCase = true) -> "webp"
        else -> "jpg"
    }
}

private fun String.stableHash(): String {
    var hash = -3750763034362895579L
    for (character in this) {
        hash = (hash xor character.code.toLong()) * 1099511628211L
    }
    return hash.toULong().toString(16)
}
