@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics


actual object AppDebugLog {

    private val processStart = System.nanoTime()

    actual fun log(event: String) {
        println("DreamPlayer: $event")
        LogStorage.addLog(event)
    }

    actual fun trace(
        category: TraceCategory,
        event: String,
        message: String,
    ) {
        val elapsedMs = (System.nanoTime() - processStart) / 1_000_000L
        val thread = Thread.currentThread().name
        val text = "[${elapsedMs}ms][$thread][$category][$event] $message"
        println("DreamPlayer: $text")
        LogStorage.addLog(text)
    }
}
