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

    /**
     * Checks whether the platform media provider can produce artwork for one
     * logical library album. Implementations must treat transient/provider
     * failures as [LocalArtworkState.UNKNOWN], not as a missing cover.
     */
    suspend fun probeArtwork(candidate: ArtworkProbeCandidate): ArtworkProbeResult
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
    val albumArtworkKey: String? = null,
    val localArtworkState: LocalArtworkState = LocalArtworkState.UNKNOWN,
)

enum class LocalArtworkState {
    UNKNOWN,
    PRESENT,
    MISSING,
}

data class ArtworkProbeCandidate(
    val requestId: Long,
    val albumUris: List<String>,
    val sampleTrackUris: List<String>,
)

data class ArtworkProbeResult(
    val requestId: Long,
    val state: LocalArtworkState,
    val artworkUri: String? = null,
)
