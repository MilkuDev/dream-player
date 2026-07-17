package org.milkdev.dreamplayer.playback

data class PlaybackTimeSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
)
