@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.features

import org.milkdev.org.milkdev.dreamplayer.features.PlatformFeatureStatus

actual object PlatformFeatureProvider {
    actual val aiDailyPlaylistApi: PlatformFeatureStatus = PlatformFeatureStatus(
        enabled = false,
        reason = "Будет доступно позже",
    )
}
