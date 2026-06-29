package org.milkdev.dreamplayer.library

import kotlinx.coroutines.flow.Flow

interface MusicScanner {
    /**
     * Performs a full scan and emits tracks as they are found.
     */
    fun scan(): Flow<RawTrackData>

    /**
     * Emits an event whenever the system music library changes.
     */
    fun observeChanges(): Flow<Unit>
}

data class RawTrackData(
    val path: String,
    val mediaStoreId: Long? = null,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val fileSize: Long,
    val lastModified: Long,
    val albumArtUri: String? = null,
    val albumArtSource: CoverSource = CoverSource.NONE,
)
