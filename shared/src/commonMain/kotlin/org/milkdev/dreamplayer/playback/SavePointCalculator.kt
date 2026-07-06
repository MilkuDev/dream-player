package org.milkdev.dreamplayer.playback

/**
 * Рассчитывает сейв-поинты (ms) для сохранения прогресса воспроизведения.
 *
 * Правила:
 * - Длительность < 5_000ms — не сохранять (пустой список).
 * - Длительность 5_000–30_000ms — 3 точки: 3s от начала, середина, за 3s до конца.
 * - Длительность ≥ 30_000ms — 7 точек: 5s от начала, 5 равноудалённых, за 5s до конца.
 */
object SavePointCalculator {

    private const val MIN_SAVE_DURATION_MS = 5_000L
    private const val SHORT_TRACK_SAVE_MARGIN_MS = 3_000L
    private const val LONG_TRACK_SAVE_MARGIN_MS = 5_000L

    fun calculate(durationMs: Long): List<Long> {
        if (durationMs < MIN_SAVE_DURATION_MS) return emptyList()

        return if (durationMs < 30_000L) {
            calculateShortTrack(durationMs)
        } else {
            calculateLongTrack(durationMs)
        }
    }

    private fun calculateShortTrack(durationMs: Long): List<Long> {
        val start = SHORT_TRACK_SAVE_MARGIN_MS
        val end = durationMs - SHORT_TRACK_SAVE_MARGIN_MS
        if (start >= end) return emptyList()

        val mid = durationMs / 2
        return listOf(start, mid, end).distinct().sorted()
    }

    private fun calculateLongTrack(durationMs: Long): List<Long> {
        val start = LONG_TRACK_SAVE_MARGIN_MS
        val end = durationMs - LONG_TRACK_SAVE_MARGIN_MS
        if (start >= end) return emptyList()

        // 7 точек: start + 5 равноудалённых + end
        val step = (end - start) / 6
        return (0..6).map { i -> start + step * i }.distinct().sorted()
    }
}