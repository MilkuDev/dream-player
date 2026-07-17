package org.milkdev.dreamplayer.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.milkdev.dreamplayer.playback.PlaybackTimeSource
import org.milkdev.dreamplayer.playback.diagnostics.*

suspend fun runPlaybackDiagnostics(
    playbackTimeSource: PlaybackTimeSource
) {
    val samples = collectSnapshotSequence(
        source = { playbackTimeSource.snapshot() },
        count = 120,
        intervalMs = 16L,)
    val analysis = analyzeSnapshotSequence(samples)
    println("========== SAMPLE LOG ==========")
    println(formatSampleLog(samples))
    println("========== DELTA TABLE ==========")
    println(formatDeltaTable(analysis))
    println("========== ANOMALIES ==========")
    println(formatAnomalyReport(analysis))
    println("========== CONSISTENCY ==========")
    println(formatConsistencyReport(checkSnapshotConsistency(samples)))
    println("========== SLIDER ==========")
    println(
        formatSliderSimulation(
            simulateSliderSequence(
                positions = samples.map { it.positionMs },
                durations = samples.map { it.durationMs },)
        )
    )
}
