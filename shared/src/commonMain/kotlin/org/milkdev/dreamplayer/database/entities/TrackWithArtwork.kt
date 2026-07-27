package org.milkdev.dreamplayer.database.entities

import androidx.room.Embedded

data class TrackWithArtwork(
    @Embedded val track: TrackEntity,
    val artworkUri: String?,
    val artworkRevision: Long,
)
