@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.features

actual object PlatformFeatureProvider {
    actual val aiDailyPlaylistApi: PlatformFeatureStatus = PlatformFeatureStatus(enabled = true)
}
