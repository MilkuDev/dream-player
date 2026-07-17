@file:Suppress("SpellCheckingInspection")

package org.milkdev.dreamplayer.playback.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the playback diagnostics tools.
 *
 * Run with:  .\gradlew.bat :shared:jvmTest
 *
 * These tests use ONLY synthetic data — no real PlaybackTimeSource needed.
 */
class PlaybackDiagnosticsTest {

    // -----------------------------------------------------------------------
    // TEST 3 — Slider input simulation
    // -----------------------------------------------------------------------

    @Test
    fun `smooth playback produces smooth slider fractions`() {
        val positions = (0L..100_000L step 16L).take(60)
        val durations = positions.map { 240_000L }

        val result = simulateSliderSequence(positions, durations)

        println("\n=== Test: smooth playback ===")
        println(formatSliderSimulation(result))

        assertEquals(60, result.frames.size)
        // all fractional deltas should be positive (monotonic advance)
        result.frames.drop(1).forEach { frame ->
            assertTrue(frame.fractionalDelta > 0f, "frame ${frame.frameIndex}: fraction should increase, got ${frame.fractionalDelta}")
        }
    }

    @Test
    fun `duration spike 1ms causes visual jump`() {
        val positions = listOf(50_000L, 50_000L)
        val durations = listOf(1L, 240_000L)

        val result = simulateSliderSequence(positions, durations)

        println("\n=== Test: duration spike (1ms -> 240s) ===")
        println(formatSliderSimulation(result))

        assertEquals(2, result.frames.size)
        // Fraction should go from 50000/1 to 50000/240000 — huge visual jump
        val visualJump = result.frames.last().visualJumpMs
        assertTrue(kotlin.math.abs(visualJump) > 40_000L, "Should produce a large visual jump, got $visualJump")
    }

    @Test
    fun `position decrease causes negative fractional delta`() {
        val positions = listOf(100_000L, 100_020L, 100_010L)
        val durations = listOf(240_000L, 240_000L, 240_000L)

        val result = simulateSliderSequence(positions, durations)

        println("\n=== Test: non-monotonic position ===")
        println(formatSliderSimulation(result))

        assertEquals(3, result.frames.size)
        // frame 2 (index 2) should show a position decrease
        val frame2 = result.frames[2]
        assertEquals(-10L, frame2.positionDeltaMs, "positionDeltaMs should be -10")
        assertTrue(frame2.fractionalDelta < 0f, "fractionalDelta should be negative, got ${frame2.fractionalDelta}")
    }

    @Test
    fun `track transition produces fractional regression`() {
        val positions = listOf(240_000L, 0L)
        val durations = listOf(240_000L, 200_000L)

        val result = simulateSliderSequence(positions, durations)

        println("\n=== Test: track transition (240s -> 0s) ===")
        println(formatSliderSimulation(result))

        assertEquals(2, result.frames.size)
        // Fraction goes from 1.0 to 0.0 — this is a regression
        assertTrue(result.frames.last().fractionalDelta < 0f, "fraction should decrease at track transition")
    }

    @Test
    fun `duration increase while position unchanged causes fractional decrease`() {
        val positions = listOf(100_000L, 100_000L)
        val durations = listOf(100_001L, 200_000L)

        val result = simulateSliderSequence(positions, durations)

        println("\n=== Test: duration increase, position unchanged ===")
        println(formatSliderSimulation(result))

        // Fraction goes from ~1.0 to 0.5
        assertTrue(result.frames.last().fractionalDelta < 0f, "fraction should decrease when duration increases")
    }

    // -----------------------------------------------------------------------
    // TEST 4 — Snapshot consistency check
    // -----------------------------------------------------------------------

    @Test
    fun `consistent snapshot sequence reports no issues`() {
        val samples = (0..59).map { i ->
            SnapshotSample(
                index = i,
                elapsedSinceStartMs = i * 16L,
                positionMs = i * 16L,
                durationMs = 240_000L,
                isPlaying = true,
                playbackSpeed = 1f,
            )
        }

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: consistent sequence ===")
        println(formatConsistencyReport(report))

        assertTrue(report.isConsistent, "Expected no issues for monotonic sequence")
    }

    @Test
    fun `detects position exceeding duration`() {
        val samples = listOf(
            SnapshotSample(0, 0L, 100_000L, 240_000L, true, 1f),
            SnapshotSample(1, 16L, 250_000L, 240_000L, true, 1f),
        )

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: position exceeds duration ===")
        println(formatConsistencyReport(report))

        assertTrue(report.issues.any { it.kind == ConsistencyIssueKind.POSITION_EXCEEDS_DURATION })
    }

    @Test
    fun `detects frozen position while playing`() {
        val samples = (0..19).map { i ->
            SnapshotSample(
                index = i,
                elapsedSinceStartMs = i * 16L,
                positionMs = 100_000L,  // never changes
                durationMs = 240_000L,
                isPlaying = true,
                                playbackSpeed = 1f,
            )
        }

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: frozen position while playing ===")
        println(formatConsistencyReport(report))

        assertTrue(report.issues.any { it.kind == ConsistencyIssueKind.PLAYING_FROZEN })
    }

    @Test
    fun `detects duration change without position advance`() {
        val samples = listOf(
            SnapshotSample(0, 0L, 50_000L, 240_000L, true, 1f),
            SnapshotSample(1, 16L, 50_000L, 200_000L, true, 1f),
        )

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: duration changed, position same while playing ===")
        println(formatConsistencyReport(report))

        assertTrue(report.issues.any { it.kind == ConsistencyIssueKind.DURATION_CHANGED_WITHOUT_POSITION_ADVANCE })
    }

    @Test
    fun `detects negative position`() {
        val samples = listOf(
            SnapshotSample(0, 0L, -1L, 240_000L, true, 1f),
        )

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: negative position ===")
        println(formatConsistencyReport(report))

        assertTrue(report.issues.any { it.kind == ConsistencyIssueKind.NEGATIVE_POSITION })
    }

    @Test
    fun `detects speed zero while playing`() {
        val samples = listOf(
            SnapshotSample(0, 0L, 100_000L, 240_000L, true, 0f),
        )

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: speed zero while playing ===")
        println(formatConsistencyReport(report))

        assertTrue(report.issues.any { it.kind == ConsistencyIssueKind.SPEED_ZERO_WHILE_PLAYING })
    }

    // -----------------------------------------------------------------------
    // Duration change severity tests
    // -----------------------------------------------------------------------

    @Test
    fun `duration 0 to N during track load is INFO not ERROR`() {
        val samples = listOf(
            SnapshotSample(0, 0L, 0L, 0L, true, 1f),
            SnapshotSample(1, 16L, 0L, 240_000L, true, 1f),
        )

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: duration 0->N (track load) ===")
        println(formatConsistencyReport(report))

        val issue = report.issues.firstOrNull { it.kind == ConsistencyIssueKind.DURATION_CHANGED_WITHOUT_POSITION_ADVANCE }
        assertTrue(issue != null, "Should detect duration change")
        assertEquals(Severity.INFO, issue.severity, "Track load duration change should be INFO, not ERROR")
    }

    @Test
    fun `duration N to M with position frozen after playing is WARNING`() {
        val samples = listOf(
            SnapshotSample(0, 0L, 100_000L, 240_000L, true, 1f),
            SnapshotSample(1, 16L, 100_000L, 200_000L, true, 1f),
        )

        val report = checkSnapshotConsistency(samples)

        println("\n=== Test: duration N->M with position frozen (mid-playback) ===")
        println(formatConsistencyReport(report))

        val issue = report.issues.firstOrNull { it.kind == ConsistencyIssueKind.DURATION_CHANGED_WITHOUT_POSITION_ADVANCE }
        assertTrue(issue != null)
        assertEquals(Severity.WARNING, issue.severity, "Mid-playback duration change should be WARNING")
    }

    // -----------------------------------------------------------------------
    // Audio drift tests
    // -----------------------------------------------------------------------

    @Test
    fun `no drift when position matches wall time`() {
        // position advances at same rate as wall clock (1x speed)
        val samples = (0..59).map { i ->
            SnapshotSample(
                index = i,
                elapsedSinceStartMs = i * 16L,
                positionMs = i * 16L,
                durationMs = 240_000L,
                isPlaying = true,
                playbackSpeed = 1f,
            )
        }

        val analysis = analyzeSnapshotSequence(samples)
        val drift = computeAccumulatedDrift(samples)

        println("\n=== Test: no drift ===")
        println(formatDriftReport(drift))
        println(formatAnomalyReport(analysis))

        assertFalse(analysis.anomalies.any { it.kind == AnomalyKind.AUDIO_LAG }, "Should not report audio lag")
        assertTrue(drift.cumulativeDriftMs < 10L, "Drift should be near zero")
    }

    @Test
    fun `detects audio lag when position falls behind wall time`() {
        // position advances at 12ms/frame while wall advances at 16ms/frame → 25% lag
        val samples = (0..59).map { i ->
            SnapshotSample(
                index = i,
                elapsedSinceStartMs = i * 16L,
                positionMs = (i * 12L).coerceAtMost(240_000L),
                durationMs = 240_000L,
                isPlaying = true,
                playbackSpeed = 1f,
            )
        }

        val analysis = analyzeSnapshotSequence(samples)
        val drift = computeAccumulatedDrift(samples)

        println("\n=== Test: audio lag (12ms/frame vs 16ms/frame) ===")
        println(formatDriftReport(drift))
        println(formatAnomalyReport(analysis))

        assertTrue(analysis.anomalies.any { it.kind == AnomalyKind.AUDIO_LAG }, "Should report audio lag")
        assertTrue(drift.isLagging, "Drift should indicate lagging")
        assertTrue(drift.cumulativeDriftMs > 200L, "Cumulative drift should be >200ms over 60 frames")
    }

    @Test
    fun `pause resets drift accumulation`() {
        // play → pause → play: drift from first segment should not carry over
        val samples = listOf(
            SnapshotSample(0, 0L, 0L, 240_000L, true, 1f),
            SnapshotSample(1, 16L, 10L, 240_000L, true, 1f),
            SnapshotSample(2, 32L, 10L, 240_000L, false, 1f),  // pause — drift resets
            SnapshotSample(3, 48L, 10L, 240_000L, false, 1f),  // still paused
            SnapshotSample(4, 64L, 10L, 240_000L, true, 1f),   // resume
            SnapshotSample(5, 80L, 20L, 240_000L, true, 1f),   // position advances from 10
        )

        val drift = computeAccumulatedDrift(samples)

        println("\n=== Test: pause resets drift ===")
        println(formatDriftReport(drift))

        // First segment: wall=16ms, pos=10ms → drift=6ms
        // Pause: skipped
        // Second segment: wall=16ms, pos=10ms → drift=6ms
        // Total drift should be ~12ms, not the full ~22ms
        assertTrue(drift.cumulativeDriftMs < 30L, "Drift should not accumulate across pause")
    }

    @Test
    fun `drift at 2x speed expects position to outpace wall`() {
        // at 2x speed, position should advance ~32ms per 16ms wall
        val samples = (0..29).map { i ->
            SnapshotSample(
                index = i,
                elapsedSinceStartMs = i * 16L,
                positionMs = i * 32L,
                durationMs = 240_000L,
                isPlaying = true,
                playbackSpeed = 2f,
            )
        }

        val drift = computeAccumulatedDrift(samples)

        println("\n=== Test: 2x speed — position matches expected ===")
        println(formatDriftReport(drift))

        // at 2x, expected per frame = 16*2 = 32ms, actual = 32ms → drift = 0
        assertTrue(kotlin.math.abs(drift.cumulativeDriftMs) < 10L, "Drift should be near zero at correct 2x speed")
    }
}
