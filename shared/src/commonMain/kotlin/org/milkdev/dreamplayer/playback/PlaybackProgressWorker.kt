package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class PlaybackProgressWorker(
    private val scope: CoroutineScope,
    private val audioPlayerState: StateFlow<AudioPlayerState>,
    private val playbackTimeSource: PlaybackTimeSource,
    private val onCheckpoint: (trackId: Long, positionMs: Long) -> Unit,
    checkpointDistanceMs: Long = DEFAULT_CHECKPOINT_DISTANCE_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private val tracker = PlaybackProgressCheckpointTracker(checkpointDistanceMs)

    fun start(): Job {
        return scope.launch {
            while (isActive) {
                val state = audioPlayerState.value
                val trackId = state.currentTrackId
                val positionMs = playbackTimeSource.snapshot().positionMs.coerceAtLeast(0L)
                if (tracker.shouldCheckpoint(trackId, positionMs, state.isPlaying)) {
                    onCheckpoint(checkNotNull(trackId), positionMs)
                }
                delay(pollIntervalMs)
            }
        }
    }

    private companion object {
        const val DEFAULT_CHECKPOINT_DISTANCE_MS = 10_000L
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
    }
}

internal class PlaybackProgressCheckpointTracker(
    private val checkpointDistanceMs: Long,
) {
    private var trackedTrackId: Long? = null
    private var checkpointPositionMs: Long = 0L

    init {
        require(checkpointDistanceMs > 0L)
    }

    fun shouldCheckpoint(
        trackId: Long?,
        positionMs: Long,
        isPlaying: Boolean,
    ): Boolean {
        if (trackId == null) {
            trackedTrackId = null
            checkpointPositionMs = 0L
            return false
        }

        val normalizedPositionMs = positionMs.coerceAtLeast(0L)
        if (trackedTrackId != trackId) {
            trackedTrackId = trackId
            checkpointPositionMs = normalizedPositionMs
            return false
        }

        if (!isPlaying) return false
        if (abs(normalizedPositionMs - checkpointPositionMs) < checkpointDistanceMs) {
            return false
        }

        checkpointPositionMs = normalizedPositionMs
        return true
    }
}
