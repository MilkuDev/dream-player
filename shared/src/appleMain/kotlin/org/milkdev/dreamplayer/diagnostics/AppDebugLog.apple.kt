@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics

actual object AppDebugLog {
    actual fun log(event: String) {
    }

    actual fun trace(
        category: TraceCategory,
        event: String,
        message: String,
    ) {
    }
}