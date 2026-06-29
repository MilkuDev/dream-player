@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.flow.StateFlow

expect object AudioPlayer {
    val state: StateFlow<AudioPlayerState>
    fun play(snapshot: PlaybackSnapshot)
    fun updateQueue(snapshot: PlaybackSnapshot)
    fun moveQueueItem(fromIndex: Int, toIndex: Int, snapshot: PlaybackSnapshot)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun skipToPrevious()
    fun skipToNext()
    fun skipToQueueIndex(index: Int)
    fun setRepeatMode(mode: PlaybackRepeatMode)
    fun getCurrentPosition(): Long
    // fun release()
}
