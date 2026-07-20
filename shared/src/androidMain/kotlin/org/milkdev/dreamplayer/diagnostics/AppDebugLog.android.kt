@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics

import android.os.SystemClock
import android.util.Log


actual object AppDebugLog {

    private val processStart = SystemClock.elapsedRealtime()
    private const val TAG = "DreamPlayer"

    actual fun log(event: String) {
        Log.d(TAG, event)
        LogStorage.addLog(event)
    }

    actual fun trace(
        category: TraceCategory,
        event: String,
        message: String,
    ) {
        val elapsed = SystemClock.elapsedRealtime() - processStart
        val thread = Thread.currentThread().name

        val text = "[${elapsed}ms][$thread][$category][$event] $message"

        Log.d(TAG, text)
        LogStorage.addLog(text)
    }
}