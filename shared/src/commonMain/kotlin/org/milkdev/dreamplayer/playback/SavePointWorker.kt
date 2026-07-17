package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.milkdev.dreamplayer.library.SettingsRepository
import kotlin.time.Duration.Companion.milliseconds

class SavePointWorker(
    private val scope: CoroutineScope,
    private val audioPlayerState: StateFlow<AudioPlayerState>,
    private val playbackTimeSource: PlaybackTimeSource,
) {
    private val savePointToleranceMs = 500L
    private var currentSavePoints: List<Long> = emptyList()
    private var hitSavePointIndices: MutableSet<Int> = mutableSetOf()
    private var lastTrackId: Long? = null

    fun start() {
        scope.launch {
            audioPlayerState.collect { state ->
                val trackId = state.currentTrackId
                if (trackId != null && trackId != lastTrackId) {
                    currentSavePoints = SavePointCalculator.calculate(state.totalDurationMs)
                    hitSavePointIndices.clear()
                    lastTrackId = trackId
                }
            }
        }

        scope.launch {
            while (isActive) {
                val state = audioPlayerState.value
                if (state.isPlaying && currentSavePoints.isNotEmpty() && lastTrackId != null) {
                    val snapshot = playbackTimeSource.snapshot()
                    val positionMs = snapshot.positionMs

                    for (i in currentSavePoints.indices) {
                        if (i in hitSavePointIndices) continue
                        val point = currentSavePoints[i]
                        if (positionMs >= point && positionMs - point < savePointToleranceMs) {
                            hitSavePointIndices.add(i)
                            SettingsRepository.saveTrackPositionOnly(positionMs)
                            break
                        }
                    }
                }
                delay(250)
            }
        }
    }
}
