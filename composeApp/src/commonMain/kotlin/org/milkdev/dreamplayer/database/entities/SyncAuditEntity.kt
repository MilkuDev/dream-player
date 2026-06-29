package org.milkdev.dreamplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncStatus {
    SUCCESS, PARTIAL, ERROR
}

@Entity(tableName = "sync_audit")
data class SyncAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val durationMs: Long,
    val tracksFound: Int,
    val status: SyncStatus,
    val errorLog: String? = null
)
