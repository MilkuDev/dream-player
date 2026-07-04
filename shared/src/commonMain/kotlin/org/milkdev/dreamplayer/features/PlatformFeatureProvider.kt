@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.features

data class PlatformFeatureStatus(
    val enabled: Boolean,
    val reason: String? = null,
)

expect object PlatformFeatureProvider {
    val aiDailyPlaylistApi: PlatformFeatureStatus
}
