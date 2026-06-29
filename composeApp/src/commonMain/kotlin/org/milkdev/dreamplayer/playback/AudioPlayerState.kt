package org.milkdev.dreamplayer.playback

data class AudioPlayerState(
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    val totalDurationMs: Long = 0L,
    val queue: PlaybackQueueSnapshot = EmptyPlaybackQueueSnapshot,
)

val EmptyPlaybackQueueSnapshot = PlaybackQueueSnapshot(
    queueVersion = 0L,
    trackIds = LongArray(0),
    currentIndex = -1,
    currentTrackId = null,
)
