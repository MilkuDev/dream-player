@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.flow.StateFlow
import org.milkdev.dreamplayer.diagnostics.PlaybackTrace
import org.milkdev.dreamplayer.diagnostics.TraceCategory

actual object AudioPlayer {
    private var nextSessionId: Int = 1
    private var currentSessionId: Int = 0
    actual val state: StateFlow<AudioPlayerState>
        get() = TODO("Not yet implemented")

    actual val playbackTimeSource: PlaybackTimeSource = object : PlaybackTimeSource {
        override fun snapshot(): PlaybackTimeSnapshot {
            TODO("Not yet implemented")
        }
    }

    actual fun play(snapshot: PlaybackSnapshot, startPositionMs: Long) {
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

    actual fun setRepeatMode(mode: PlaybackRepeatMode) {
    }
}