package org.milkdev.dreamplayer.playback

enum class TrackAvailability {
    AVAILABLE,
    NEEDS_RESOLVE,
    MISSING,
}

data class PlaybackQueueSnapshot(
    val queueVersion: Long,
    val trackIds: LongArray,
    val currentIndex: Int,
    val currentTrackId: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PlaybackQueueSnapshot

        if (queueVersion != other.queueVersion) return false
        if (currentIndex != other.currentIndex) return false
        if (currentTrackId != other.currentTrackId) return false
        if (!trackIds.contentEquals(other.trackIds)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = queueVersion.hashCode()
        result = 31 * result + currentIndex
        result = 31 * result + (currentTrackId?.hashCode() ?: 0)
        result = 31 * result + trackIds.contentHashCode()
        return result
    }
}

data class PlaybackItemRef(
    val trackId: Long,
    val uri: String,
    val availability: TrackAvailability,
    val contentVersion: Long,
)

data class TrackPlaybackMetadata(
    val title: String,
    val artistName: String,
    val albumName: String,
    val durationMs: Long,
    val albumArtUri: String?,
)

data class ResolvedPlaybackItem(
    val ref: PlaybackItemRef,
    val metadata: TrackPlaybackMetadata,
) {
    val trackId: Long get() = ref.trackId
}

data class PlaybackSnapshot(
    val queue: PlaybackQueueSnapshot,
    val items: List<ResolvedPlaybackItem>,
)

fun PlaybackSnapshot.matchesQueue(snapshot: PlaybackQueueSnapshot): Boolean {
    return queue.queueVersion == snapshot.queueVersion &&
        queue.currentTrackId == snapshot.currentTrackId &&
        queue.currentIndex == snapshot.currentIndex &&
        queue.trackIds.contentEquals(snapshot.trackIds)
}
