@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.library.metadata

import org.milkdev.dreamplayer.library.RawTrackData

actual object EmbeddedMetadataReader {
    actual suspend fun read(rawTrack: RawTrackData): EmbeddedMetadata? {
        TODO("Not yet implemented")
    }
}