package org.milkdev.dreamplayer.extensions.data

interface AlbumArtFileStore {
    suspend fun saveRemoteAlbumArt(
        albumId: Long,
        sourceUrl: String,
        bytes: ByteArray,
        contentType: String?,
    ): String
}

expect fun createAlbumArtFileStore(): AlbumArtFileStore
