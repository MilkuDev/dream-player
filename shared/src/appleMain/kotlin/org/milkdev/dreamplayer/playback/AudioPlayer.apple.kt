@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.flow.StateFlow

actual object AudioPlayer {
    actual val state: StateFlow<AudioPlayerState>
        get() = TODO("Not yet implemented")

    actual fun play(snapshot: PlaybackSnapshot) {
    }

    actual fun updateQueue(snapshot: PlaybackSnapshot) {
    }

    actual fun moveQueueItem(
        fromIndex: Int,
        toIndex: Int,
        snapshot: PlaybackSnapshot
    ) {
    }

    actual fun pause() {
    }

    actual fun resume() {
    }

    actual fun stop() {
    }

    actual fun seekTo(positionMs: Long) {
    }

    actual fun skipToPrevious() {
    }

    actual fun skipToNext() {
    }

    actual fun skipToQueueIndex(index: Int) {
    }

    actual fun setRepeatMode(mode: PlaybackRepeatMode) {
    }

    actual fun getCurrentPosition(): Long {
        TODO("Not yet implemented")
    }
}