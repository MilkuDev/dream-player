@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics

import kotlin.time.TimeSource

actual object AppDebugLog {
    private val processStart = TimeSource.Monotonic.markNow()

    actual fun log(event: String) {
        LogStorage.addLog(event)
    }

    actual fun trace(
        category: TraceCategory,
        event: String,
        message: String,
    ) {
        val elapsedMs = processStart.elapsedNow().inWholeMilliseconds
        LogStorage.addLog("[${elapsedMs}ms][$category][$event] $message")
    }
}
