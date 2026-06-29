@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics

import android.util.Log

actual object AppDebugLog {
    private const val TAG = "DreamPlayer"

    actual fun log(event: String) {
        Log.d(TAG, event)
        LogStorage.addLog(event)
    }
}