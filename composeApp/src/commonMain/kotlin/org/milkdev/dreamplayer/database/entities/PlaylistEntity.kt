package org.milkdev.dreamplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val isSystem: Boolean = false,
    val editable: Boolean = true
)