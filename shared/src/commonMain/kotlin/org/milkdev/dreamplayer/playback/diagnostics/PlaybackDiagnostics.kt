@file:Suppress("SpellCheckingInspection")

package org.milkdev.dreamplayer.playback.diagnostics

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.milkdev.dreamplayer.playback.PlaybackTimeSnapshot
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// ---------------------------------------------------------------------------
// Data types
// ---------------------------------------------------------------------------

/**
 * A timestamped record of a single PlaybackTimeSource reading.
 *
 * elapsedSinceStartMs is the monotonic time elapsed since the first sample
 * in a collection sequence (set after collection, not by fromSnapshot).
 */
data class SnapshotSample(
    val index: Int,
    val elapsedSinceStartMs: Long = 0L,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
) {
    companion object {
        fun fromSnapshot(index: Int, snapshot: PlaybackTimeSnapshot): SnapshotSample = SnapshotSample(
            index = index,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            isPlaying = snapshot.isPlaying,
            playbackSpeed = snapshot.playbackSpeed,
        )
    }
}

// ---------------------------------------------------------------------------
// TEST 1 & 2 — SnapshotCollector + SnapshotSequenceAnalyzer
// ---------------------------------------------------------------------------

/**
 * Collects [count] sequential samples from [source].
 *
 * The [intervalMs] controls the delay *between* snapshots (minimum ~16ms =
 * one frame at 60 fps).
 *
 * Returns samples with elapsedSinceStartMs filled in from a monotonic clock.
 */
@Suppress("unused")
suspend fun collectSnapshotSequence(
    source: () -> PlaybackTimeSnapshot,
    count: Int = 60,
    intervalMs: Long = 16L,
): List<SnapshotSample> {
    val startMark = TimeSource.Monotonic.markNow()
    val samples = mutableListOf<SnapshotSample>()
    var index = 0
    while (currentCoroutineContext().isActive && index < count) {
        val snapshot = source()
        val elapsedMs = startMark.elapsedNow().inWholeMilliseconds
        samples.add(
            SnapshotSample.fromSnapshot(index, snapshot).copy(elapsedSinceStartMs = elapsedMs)
        )
        index++
        if (index < count) {
            delay(intervalMs.milliseconds)
        }
    }
    return samples
}

// ---------------------------------------------------------------------------
// Analysis types
// ---------------------------------------------------------------------------

data class SampleDelta(
    val sampleIndex: Int,
    val elapsedDeltaMs: Long,
    val positionDeltaMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val flags: List<String> = emptyList(),
)

data class SequenceAnalysis(
    val sampleCount: Int,
    val totalWallMs: Long,
    val totalPositionAdvanceMs: Long,
    val deltas: List<SampleDelta>,
    val anomalies: List<AnomalyReport>,
    val finalDriftMs: Long = 0L,
    val maxDriftMs: Long = 0L,
    val avgDriftRatePerSecond: Float = 0f,
)

data class AnomalyReport(
    val sampleIndex: Int,
    val kind: AnomalyKind,
    val message: String,
)

enum class Severity { INFO, WARNING, ERROR }

enum class AnomalyKind {
    POSITION_DECREASED,
    POSITION_FROZEN,
    LARGE_FORWARD_JUMP,
    DURATION_CHANGED,
    POSITION_EXCEEDS_DURATION,
    PLAYING_WITHOUT_ADVANCEMENT,
    NEGATIVE_POSITION,
    AUDIO_LAG,
}

// ---------------------------------------------------------------------------
// Analysis functions
// ---------------------------------------------------------------------------

@Suppress("unused")
fun analyzeSnapshotSequence(samples: List<SnapshotSample>): SequenceAnalysis {
    if (samples.isEmpty()) return SequenceAnalysis(0, 0L, 0L, emptyList(), emptyList())

    val deltas = mutableListOf<SampleDelta>()
    val anomalies = mutableListOf<AnomalyReport>()
    val totalWallMs = samples.last().elapsedSinceStartMs - samples.first().elapsedSinceStartMs
    val totalPositionMs = samples.last().positionMs - samples.first().positionMs

    var frozenCount = 0
    var driftAccumMs = 0L

    for (i in 1 until samples.size) {
        val prev = samples[i - 1]
        val curr = samples[i]
        val elapsedDeltaMs = curr.elapsedSinceStartMs - prev.elapsedSinceStartMs
        val positionDeltaMs = curr.positionMs - prev.positionMs
        val flags = mutableListOf<String>()

        // Highlight single-frame staircase behavior (time moves, position doesn't)
        if (positionDeltaMs == 0L && curr.isPlaying && elapsedDeltaMs > 0) {
            flags.add("STALL")
        }

        // POSITION_DECREASED
        if (positionDeltaMs < 0) {
            flags.add("DECREASED")
            anomalies.add(AnomalyReport(
                sampleIndex = i,
                kind = AnomalyKind.POSITION_DECREASED,
                message = "position went ${prev.positionMs} -> ${curr.positionMs} (delta=$positionDeltaMs ms)",
            ))
        }

        // POSITION_EXCEEDS_DURATION
        if (curr.positionMs > curr.durationMs) {
            flags.add("POS>DUR")
            anomalies.add(AnomalyReport(
                sampleIndex = i,
                kind = AnomalyKind.POSITION_EXCEEDS_DURATION,
                message = "positionMs=${curr.positionMs} > durationMs=${curr.durationMs}",
            ))
        }

        // NEGATIVE_POSITION
        if (curr.positionMs < 0) {
            flags.add("NEGATIVE")
            anomalies.add(AnomalyReport(
                sampleIndex = i,
                kind = AnomalyKind.NEGATIVE_POSITION,
                message = "positionMs=${curr.positionMs} is negative",
            ))
        }

        // POSITION_FROZEN (Persistent stall)
        if (positionDeltaMs == 0L && curr.isPlaying) {
            frozenCount++
            if (frozenCount >= 3) {
                flags.add("FROZEN")
                if (frozenCount == 3 || frozenCount % 5 == 0) {
                    anomalies.add(AnomalyReport(
                        sampleIndex = i,
                        kind = AnomalyKind.POSITION_FROZEN,
                        message = "position frozen at ${curr.positionMs} for ${frozenCount + 1} consecutive frames while isPlaying=true",
                    ))
                }
            }
        } else {
            frozenCount = 0
        }

        // LARGE_FORWARD_JUMP
        if (positionDeltaMs > 0 && elapsedDeltaMs > 0 && positionDeltaMs > elapsedDeltaMs * 2) {
            flags.add("LARGE_JUMP")
            anomalies.add(AnomalyReport(
                sampleIndex = i,
                kind = AnomalyKind.LARGE_FORWARD_JUMP,
                message = "position delta $positionDeltaMs ms is >2x elapsed delta $elapsedDeltaMs ms",
            ))
        }

        // DURATION_CHANGED
        if (curr.durationMs != prev.durationMs) {
            flags.add("DUR_CHANGED(${prev.durationMs}->${curr.durationMs})")
            anomalies.add(AnomalyReport(
                sampleIndex = i,
                kind = AnomalyKind.DURATION_CHANGED,
                message = "duration changed ${prev.durationMs} -> ${curr.durationMs} ms",
            ))
        }

        // AUDIO_LAG calculation
        if (curr.isPlaying && prev.isPlaying && positionDeltaMs >= 0 && elapsedDeltaMs > 0) {
            val expectedAdvanceMs = (elapsedDeltaMs * curr.playbackSpeed).toLong()
            val frameDriftMs = expectedAdvanceMs - positionDeltaMs
            driftAccumMs += frameDriftMs
        } else if (!curr.isPlaying) {
            driftAccumMs = 0L
        }

        deltas.add(SampleDelta(
            sampleIndex = i,
            elapsedDeltaMs = elapsedDeltaMs,
            positionDeltaMs = positionDeltaMs,
            durationMs = curr.durationMs,
            isPlaying = curr.isPlaying,
            flags = flags,
        ))
    }

    if (driftAccumMs > 100L) {
        val rateStr = if (totalWallMs > 0L) format1Decimal(driftAccumMs.toFloat() / totalWallMs.toFloat() * 1000f) else "0.0"
        anomalies.add(AnomalyReport(
            sampleIndex = samples.lastIndex,
            kind = AnomalyKind.AUDIO_LAG,
            message = "audio lags behind wall clock: accumulated drift = ${driftAccumMs}ms over ${totalWallMs}ms (avg ${rateStr}ms/s)",
        ))
    }

    val maxDriftMs = samples.filter { it.isPlaying }.let { playing ->
        if (playing.size < 2) 0L else {
            val firstPos = playing.first().positionMs
            val firstWall = playing.first().elapsedSinceStartMs
            playing.maxOf { (it.elapsedSinceStartMs - firstWall - (it.positionMs - firstPos)).coerceAtLeast(0L) }
        }
    }

    return SequenceAnalysis(
        sampleCount = samples.size,
        totalWallMs = totalWallMs,
        totalPositionAdvanceMs = totalPositionMs,
        deltas = deltas,
        anomalies = anomalies,
        finalDriftMs = driftAccumMs,
        maxDriftMs = maxDriftMs,
        avgDriftRatePerSecond = if (totalWallMs > 0L) driftAccumMs.toFloat() / totalWallMs.toFloat() * 1000f else 0f,
    )
}

// ---------------------------------------------------------------------------
// Formatters
// ---------------------------------------------------------------------------

@Suppress("unused")
fun formatSampleLog(samples: List<SnapshotSample>): String {
    return samples.joinToString("\n") { s ->
        val markers = buildString {
            if (s.positionMs > s.durationMs) append("  !! POS>DUR")
            if (s.positionMs < 0) append("  !! NEGATIVE")
        }
        "[${s.index.toString().padStart(3, '0')}] elapsed=${s.elapsedSinceStartMs} position=${s.positionMs} duration=${s.durationMs} playing=${s.isPlaying} speed=${s.playbackSpeed}$markers"
    }
}

@Suppress("unused")
fun formatDeltaTable(analysis: SequenceAnalysis): String {
    val header = "sample | elapsed delta | position delta | duration | flags"
    val sep = "-".repeat(header.length.coerceAtLeast(60))
    val rows = analysis.deltas.joinToString("\n") { d ->
        "${d.sampleIndex.toString().padStart(6)} | ${d.elapsedDeltaMs.toString().padStart(13)} | ${d.positionDeltaMs.toString().padStart(14)} | ${d.durationMs.toString().padStart(8)} | ${d.flags.joinToString(", ")}"
    }
    val avgDriftStr = format1Decimal(analysis.avgDriftRatePerSecond)
    val summary = "\nTotal: ${analysis.sampleCount} samples, ${analysis.totalWallMs}ms elapsed, ${analysis.totalPositionAdvanceMs}ms position advance | drift: final=${analysis.finalDriftMs}ms max=${analysis.maxDriftMs}ms avg=${avgDriftStr}ms/s"
    return "$header\n$sep\n$rows$summary"
}

data class DriftReport(
    val totalWallMs: Long,
    val totalPositionAdvanceMs: Long,
    val cumulativeDriftMs: Long,
) {
    val isLagging: Boolean get() = cumulativeDriftMs > 100L
}

@Suppress("unused")
fun computeAccumulatedDrift(samples: List<SnapshotSample>): DriftReport {
    if (samples.size < 2) return DriftReport(0L, 0L, 0L)

    var driftMs = 0L
    var totalWallMs = 0L
    var totalPosMs = 0L

    for (i in 1 until samples.size) {
        val prev = samples[i - 1]
        val curr = samples[i]
        if (!curr.isPlaying || !prev.isPlaying) continue
        val wallDelta = curr.elapsedSinceStartMs - prev.elapsedSinceStartMs
        val posDelta = curr.positionMs - prev.positionMs
        if (posDelta < 0) continue
        totalWallMs += wallDelta
        totalPosMs += posDelta
        val expectedAdvanceMs = (wallDelta * curr.playbackSpeed).toLong()
        driftMs += (expectedAdvanceMs - posDelta)
    }

    return DriftReport(
        totalWallMs = totalWallMs,
        totalPositionAdvanceMs = totalPosMs,
        cumulativeDriftMs = driftMs,
    )
}

@Suppress("unused")
fun formatDriftReport(report: DriftReport): String {
    val rate = if (report.totalWallMs > 0L)
        format1Decimal(report.cumulativeDriftMs.toFloat() / report.totalWallMs.toFloat() * 1000f)
    else "N/A"
    val verdict = if (report.isLagging) "⚠ LAGGING" else "OK"
    return "[$verdict] wall=${report.totalWallMs}ms position=${report.totalPositionAdvanceMs}ms drift=${report.cumulativeDriftMs}ms avg=${rate}ms/s"
}

@Suppress("unused")
fun formatAnomalyReport(analysis: SequenceAnalysis): String {
    if (analysis.anomalies.isEmpty()) return "No anomalies detected."
    val header = "=== ANOMALIES (${analysis.anomalies.size} found) ==="
    val body = analysis.anomalies.joinToString("\n") { a ->
        "  [${a.kind.name}] sample=${a.sampleIndex}: ${a.message}"
    }
    val avgDriftStr = format1Decimal(analysis.avgDriftRatePerSecond)
    val driftInfo = "\n--- Drift: final=${analysis.finalDriftMs}ms max=${analysis.maxDriftMs}ms avg=${avgDriftStr}ms/s"
    return "$header\n$body$driftInfo"
}

// ---------------------------------------------------------------------------
// TEST 3 — Slider input simulation
// ---------------------------------------------------------------------------

data class SliderSimulationFrame(
    val frameIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val normalizedFraction: Float,
    val fractionalDelta: Float,
    val positionDeltaMs: Long,
    val durationDeltaMs: Long,
    val visualJumpMs: Long,
    val jumpCause: String,
)

data class SliderSimulationResult(
    val frames: List<SliderSimulationFrame>,
    val totalVisualJumpMs: Long,
    val maxVisualJumpMs: Long,
)

@Suppress("unused")
fun simulateSliderSequence(
    positions: List<Long>,
    durations: List<Long>,
    referenceDurationMs: Long = 240_000L,
): SliderSimulationResult {
    val count = minOf(positions.size, durations.size)
    require(count >= 1) { "Need at least 1 position+duration pair" }

    val frames = mutableListOf<SliderSimulationFrame>()

    for (i in 0 until count) {
        val pos = positions[i]
        val dur = durations[i].coerceAtLeast(1L)
        val fraction = pos.toFloat() / dur.toFloat()
        val posDelta = if (i > 0) pos - positions[i - 1] else 0L
        val durDelta = if (i > 0) dur - durations[i - 1] else 0L
        val prevFraction = if (i > 0) frames.last().normalizedFraction else fraction
        val fractionalDelta = fraction - prevFraction
        val visualJumpMs = (fractionalDelta * referenceDurationMs).toLong()
        val jumpCause = buildString {
            when {
                i == 0 -> append("initial")
                posDelta < 0 -> append("position DECREASED by ${-posDelta}ms")
                posDelta == 0L && durDelta != 0L -> append("duration changed by ${durDelta}ms (position unchanged)")
                fractionalDelta > 0 && posDelta > 0 && durDelta == 0L -> append("position advanced by ${posDelta}ms")
                fractionalDelta < 0 && posDelta >= 0 && durDelta > 0 -> append("duration INCREASED, fraction decreased despite position unchanged/advanced")
                fractionalDelta < 0 && posDelta < 0 -> append("position decreased by ${-posDelta}ms")
                fractionalDelta > 0 && durDelta < 0 -> append("duration DECREASED, fraction increased despite no position change")
                else -> append("posDelta=${posDelta}ms durDelta=${durDelta}ms")
            }
        }
        frames.add(SliderSimulationFrame(
            frameIndex = i,
            positionMs = pos,
            durationMs = dur,
            normalizedFraction = fraction,
            fractionalDelta = fractionalDelta,
            positionDeltaMs = posDelta,
            durationDeltaMs = durDelta,
            visualJumpMs = visualJumpMs,
            jumpCause = jumpCause,
        ))
    }

    return SliderSimulationResult(
        frames = frames,
        totalVisualJumpMs = frames.drop(1).sumOf { kotlin.math.abs(it.visualJumpMs) },
        maxVisualJumpMs = frames.drop(1).maxOfOrNull { kotlin.math.abs(it.visualJumpMs) } ?: 0L,
    )
}

@Suppress("unused")
fun formatSliderSimulation(result: SliderSimulationResult): String {
    val header = "frame | positionMs | durationMs | fraction  | fractDelta | visualΔ(ms) | cause"
    val sep = "-".repeat(header.length.coerceAtLeast(80))
    val rows = result.frames.joinToString("\n") { f ->
        val fractionStr = format6Decimals(f.normalizedFraction).padStart(8)
        val deltaStr = format6Decimals(f.fractionalDelta).padStart(10)
        "${f.frameIndex.toString().padStart(5)} | ${f.positionMs.toString().padStart(10)} | ${f.durationMs.toString().padStart(9)} | $fractionStr | $deltaStr | ${f.visualJumpMs.toString().padStart(10)} | ${f.jumpCause}"
    }
    val summary = "\nTotal visual drift: ${result.totalVisualJumpMs}ms | Max single-frame visual jump: ${result.maxVisualJumpMs}ms"
    return "$header\n$sep\n$rows$summary"
}

private fun format6Decimals(value: Float): String {
    val isNegative = value < 0
    val absValue = kotlin.math.abs(value)
    val integerPart = absValue.toLong()
    val remainder = absValue - integerPart
    var fractionalPart = (remainder * 1_000_000f + 0.5f).toLong()
    var finalInteger = integerPart

    if (fractionalPart >= 1_000_000L) {
        finalInteger += 1
        fractionalPart -= 1_000_000L
    }

    val sign = if (isNegative) "-" else ""
    return "$sign$finalInteger.${fractionalPart.toString().padStart(6, '0')}"
}

private fun format1Decimal(value: Float): String {
    val isNegative = value < 0
    val absValue = kotlin.math.abs(value)
    val integerPart = absValue.toLong()
    val remainder = absValue - integerPart
    var fractionalPart = (remainder * 10f + 0.5f).toLong()
    var finalInteger = integerPart

    if (fractionalPart >= 10L) {
        finalInteger += 1
        fractionalPart -= 10L
    }

    val sign = if (isNegative) "-" else ""
    return "$sign$finalInteger.$fractionalPart"
}

// ---------------------------------------------------------------------------
// TEST 4 — Snapshot consistency check
// ---------------------------------------------------------------------------

data class ConsistencyReport(
    val issues: List<ConsistencyIssue>,
) {
    val isConsistent: Boolean get() = issues.isEmpty()
}

data class ConsistencyIssue(
    val kind: ConsistencyIssueKind,
    val severity: Severity,
    val snapshotIndex: Int,
    val message: String,
)

enum class ConsistencyIssueKind {
    POSITION_EXCEEDS_DURATION,
    NEGATIVE_POSITION,
    DURATION_CHANGED_WITHOUT_POSITION_ADVANCE,
    PLAYING_FROZEN,
    SPEED_ZERO_WHILE_PLAYING,
}

@Suppress("unused")
fun checkSnapshotConsistency(
    samples: List<SnapshotSample>,
): ConsistencyReport {
    val issues = mutableListOf<ConsistencyIssue>()

    for ((i, sample) in samples.withIndex()) {
        if (sample.durationMs > 0L && sample.positionMs > sample.durationMs) {
            issues.add(ConsistencyIssue(
                kind = ConsistencyIssueKind.POSITION_EXCEEDS_DURATION,
                severity = Severity.ERROR,
                snapshotIndex = i,
                message = "positionMs=${sample.positionMs} > durationMs=${sample.durationMs} (by ${sample.positionMs - sample.durationMs}ms)",
            ))
        }

        if (sample.positionMs < 0) {
            issues.add(ConsistencyIssue(
                kind = ConsistencyIssueKind.NEGATIVE_POSITION,
                severity = Severity.ERROR,
                snapshotIndex = i,
                message = "positionMs=${sample.positionMs} is negative",
            ))
        }

        if (sample.isPlaying && sample.playbackSpeed <= 0f) {
            issues.add(ConsistencyIssue(
                kind = ConsistencyIssueKind.SPEED_ZERO_WHILE_PLAYING,
                severity = Severity.ERROR,
                snapshotIndex = i,
                message = "isPlaying=true but playbackSpeed=${sample.playbackSpeed}",
            ))
        }
    }

    for (i in 1 until samples.size) {
        val prev = samples[i - 1]
        val curr = samples[i]

        if (curr.durationMs != prev.durationMs && curr.positionMs == prev.positionMs && curr.isPlaying) {
            val severity = if (prev.durationMs == 0L) Severity.INFO else Severity.WARNING
            issues.add(ConsistencyIssue(
                kind = ConsistencyIssueKind.DURATION_CHANGED_WITHOUT_POSITION_ADVANCE,
                severity = severity,
                snapshotIndex = i,
                message = "duration changed ${prev.durationMs}->${curr.durationMs}ms but position stayed at ${curr.positionMs}ms while playing",
            ))
        }
    }

    var frozenStart = -1
    for (i in 1 until samples.size) {
        val prevPos = samples[i - 1].positionMs
        val currPos = samples[i].positionMs
        val isPlaying = samples[i].isPlaying

        if (isPlaying && currPos == prevPos) {
            if (frozenStart < 0) frozenStart = i - 1
        } else {
            if (frozenStart >= 0 && (i - frozenStart) >= 10) {
                val actualElapsedMs = samples[i - 1].elapsedSinceStartMs - samples[frozenStart].elapsedSinceStartMs
                issues.add(ConsistencyIssue(
                    kind = ConsistencyIssueKind.PLAYING_FROZEN,
                    severity = Severity.WARNING,
                    snapshotIndex = frozenStart,
                    message = "isPlaying=true but position did not advance for ${i - frozenStart} frames (measured ${actualElapsedMs}ms)",
                ))
            }
            frozenStart = -1
        }
    }

    if (frozenStart >= 0 && (samples.size - frozenStart) >= 10) {
        val actualElapsedMs = samples.last().elapsedSinceStartMs - samples[frozenStart].elapsedSinceStartMs
        issues.add(ConsistencyIssue(
            kind = ConsistencyIssueKind.PLAYING_FROZEN,
            severity = Severity.WARNING,
            snapshotIndex = frozenStart,
            message = "isPlaying=true but position did not advance for ${samples.size - frozenStart} frames (measured ${actualElapsedMs}ms, sequence ended while still frozen)",
        ))
    }

    return ConsistencyReport(issues = issues)
}

@Suppress("unused")
fun formatConsistencyReport(report: ConsistencyReport): String {
    if (report.isConsistent) return "All snapshots are consistent."
    val errors = report.issues.count { it.severity == Severity.ERROR }
    val warnings = report.issues.count { it.severity == Severity.WARNING }
    val infos = report.issues.count { it.severity == Severity.INFO }
    val header = "=== CONSISTENCY ISSUES (${report.issues.size} found: $errors error(s), $warnings warning(s), $infos info) ==="
    val body = report.issues.joinToString("\n") { issue ->
        val tag = when (issue.severity) {
            Severity.ERROR -> "ERROR"
            Severity.WARNING -> "WARN"
            Severity.INFO -> "INFO"
        }
        "  [$tag][${issue.kind.name}] snapshot=${issue.snapshotIndex}: ${issue.message}"
    }
    return "$header\n$body"
}