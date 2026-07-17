@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics

object PlaybackTrace {

    fun event(
        category: TraceCategory,
        event: String,
        message: String = "",
    ) {
        AppDebugLog.trace(category, event, message)
    }

    fun anomaly(
        kind: String,
        message: String,
    ) {
        AppDebugLog.trace(TraceCategory.Diagnostics, kind, message)
    }
}
