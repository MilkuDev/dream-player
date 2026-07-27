@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.flow.StateFlow

expect object AudioPlayer {
    val state: StateFlow<AudioPlayerState>
    val playbackTimeSource: PlaybackTimeSource
    fun prepare(snapshot: PlaybackSnapshot, startPositionMs: Long = 0L)
    fun play(snapshot: PlaybackSnapshot, startPositionMs: Long = 0L)
    fun updateQueue(snapshot: PlaybackSnapshot)
    fun moveQueueItem(fromIndex: Int, toIndex: Int, snapshot: PlaybackSnapshot)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setRepeatMode(mode: PlaybackRepeatMode)
}
