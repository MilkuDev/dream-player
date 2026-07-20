@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics

object PhaseLogThrottle {

    private var phase: String = "idle"
    private var prevIsPlaying: Boolean? = null
    private val prevPositions = mutableMapOf<String, Long>()
    private val counters = mutableMapOf<String, Int>()

    fun shouldLog(tag: String, isPlaying: Boolean, positionMs: Long): Boolean = synchronized(this) {
        if (prevIsPlaying == null) {
            phase = if (isPlaying) "transition" else "idle"
            counters.clear()
            prevPositions.clear()
            prevIsPlaying = isPlaying
            prevPositions[tag] = positionMs
            counters[tag] = 1
            return@synchronized true
        }

        val isPlayingChanged = prevIsPlaying != isPlaying

        if (isPlayingChanged) {
            phase = if (isPlaying) "transition" else "idle"
            counters.clear()
            prevPositions.clear()
        }

        if (isPlaying && positionMs == prevPositions.getOrDefault(tag, -1L) && !isPlayingChanged) {
            prevIsPlaying = isPlaying
            prevPositions[tag] = positionMs
            return@synchronized false
        }

        val tagCount = counters.getOrDefault(tag, 0)

        if (tagCount >= 50) {
            if (phase == "transition") {
                phase = "active"
                counters.clear()
                prevPositions.clear()
            } else {
                prevIsPlaying = isPlaying
                prevPositions[tag] = positionMs
                return@synchronized false
            }
        }

        prevIsPlaying = isPlaying
        prevPositions[tag] = positionMs
        counters[tag] = tagCount + 1
        return@synchronized true
    }
}
