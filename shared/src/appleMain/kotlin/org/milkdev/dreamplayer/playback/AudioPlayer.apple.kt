@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object AudioPlayer {
    private val _state = MutableStateFlow(AudioPlayerState())
    private var playbackSnapshot: PlaybackSnapshot? = null
    private var preparedPositionMs: Long = 0L

    actual val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    actual val playbackTimeSource: PlaybackTimeSource = object : PlaybackTimeSource {
        override fun snapshot(): PlaybackTimeSnapshot {
            val currentState = _state.value
            return PlaybackTimeSnapshot(
                positionMs = preparedPositionMs,
                durationMs = currentState.totalDurationMs,
                bufferedPositionMs = 0L,
                playbackSpeed = 1f,
                isPlaying = currentState.isPlaying,
            )
        }
    }

    actual fun prepare(snapshot: PlaybackSnapshot, startPositionMs: Long) {
        applySnapshot(snapshot, startPositionMs, isPlaying = false)
    }

    actual fun play(snapshot: PlaybackSnapshot, startPositionMs: Long) {
        applySnapshot(snapshot, startPositionMs, isPlaying = true)
    }

    actual fun updateQueue(snapshot: PlaybackSnapshot) {
        val previousTrackId = playbackSnapshot?.queue?.currentTrackId
        val previousPositionMs = preparedPositionMs
        val currentItem = snapshot.currentItem()
        applySnapshot(
            snapshot = snapshot,
            startPositionMs = if (currentItem?.trackId == previousTrackId) {
                previousPositionMs
            } else {
                0L
            },
            isPlaying = _state.value.isPlaying,
        )
    }

    actual fun moveQueueItem(
        fromIndex: Int,
        toIndex: Int,
        snapshot: PlaybackSnapshot
    ) {
        updateQueue(snapshot)
    }

    actual fun pause() {
        _state.value = _state.value.copy(isPlaying = false)
    }

    actual fun resume() {
        if (_state.value.currentTrackId != null) {
            _state.value = _state.value.copy(isPlaying = true)
        }
    }

    actual fun stop() {
        playbackSnapshot = null
        preparedPositionMs = 0L
        _state.value = AudioPlayerState()
    }

    actual fun seekTo(positionMs: Long) {
        preparedPositionMs = playbackSnapshot
            ?.currentItem()
            .clampPosition(positionMs)
    }

    actual fun setRepeatMode(mode: PlaybackRepeatMode) {
    }

    private fun applySnapshot(
        snapshot: PlaybackSnapshot,
        startPositionMs: Long,
        isPlaying: Boolean,
    ) {
        if (snapshot.items.isEmpty()) {
            stop()
            return
        }

        val copiedSnapshot = snapshot.copy(
            queue = snapshot.queue.copy(trackIds = snapshot.queue.trackIds.copyOf()),
            items = snapshot.items.toList(),
        )
        val currentItem = copiedSnapshot.currentItem()
        playbackSnapshot = copiedSnapshot
        preparedPositionMs = currentItem.clampPosition(startPositionMs)
        _state.value = AudioPlayerState(
            currentTrackId = currentItem?.trackId,
            isPlaying = isPlaying && currentItem != null,
            totalDurationMs = currentItem?.metadata?.durationMs ?: 0L,
            queue = copiedSnapshot.queue.copy(trackIds = copiedSnapshot.queue.trackIds.copyOf()),
        )
    }

    private fun PlaybackSnapshot.currentItem(): ResolvedPlaybackItem? {
        val currentTrackId = queue.currentTrackId
        return items.firstOrNull { it.trackId == currentTrackId }
            ?: items.getOrNull(queue.currentIndex)
    }

    private fun ResolvedPlaybackItem?.clampPosition(positionMs: Long): Long {
        val durationMs = this?.metadata?.durationMs ?: 0L
        return if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
    }
}
