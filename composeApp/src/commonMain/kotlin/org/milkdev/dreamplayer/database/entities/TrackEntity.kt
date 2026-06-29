package org.milkdev.dreamplayer.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "library_tracks",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["filePath"], unique = true),
        Index(value = ["mediaStoreId"]),
        Index(value = ["artistId"]),
        Index(value = ["albumId"]),
        Index(value = ["title"]),
        Index(value = ["titleSortKey", "id"]),
        Index(value = ["artistSortKey", "titleSortKey", "id"]),
        Index(value = ["albumName", "titleSortKey", "id"]),
        Index(value = ["musicBrainzRecordingMbid"]),
        Index(value = ["deletedAt"]),
        Index(
            value = [
                "artistId",
                "title",
                "durationMs",
                "fileSize"
            ]
        )
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val artistName: String,
    val albumName: String,
    val artistId: Long,
    val albumId: Long,
    val durationMs: Long,
    val fileSize: Long,
    val lastModified: Long,
    val mediaStoreId: Long? = null,
    val contentFingerprint: String = "",
    val fileHash: String? = null,
    val availability: String = "AVAILABLE",
    val albumArtUri: String? = null,
    val isPresent: Boolean = true,
    val lastSeenTimestamp: Long,
    val musicBrainzRecordingMbid: String? = null,
    val identitySourceTrust: Int = 0,
    val titleSortKey: String = "",
    val artistSortKey: String = "",
    val deletedAt: Long? = null
)
