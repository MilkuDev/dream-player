@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.diagnostics

expect object AppDebugLog {
    fun log(event: String)
}
