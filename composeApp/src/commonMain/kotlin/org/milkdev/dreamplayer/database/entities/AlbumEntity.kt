package org.milkdev.dreamplayer.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["artistId", "title"], unique = true),
        Index(value = ["year"]),
        Index(value = ["genre"]),
        Index(value = ["metadataState"]),
        Index(value = ["lastMetadataAttemptAt"]),
        Index(value = ["titleSortKey"]),
        Index(value = ["artistSortKey"]),
        Index(value = ["titleSortKey", "id"]),
        Index(value = ["artistSortKey", "titleSortKey", "id"]),
        Index(value = ["year", "titleSortKey", "id"]),
        Index(value = ["genre", "titleSortKey", "id"]),
        Index(value = ["musicBrainzReleaseGroupMbid"]),
        Index(value = ["deletedAt"]),
        Index(value = ["coverLookupState"]),
        Index(value = ["coverLookupAttemptAt"]),
        Index(value = ["lastSeenTimestamp"])
    ]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artistId: Long,
    val albumArtUri: String? = null,
    val coverUri: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val metadataState: String = "NOT_SYNCED",
    val metadataUpdatedAt: Long? = null,
    val lastMetadataAttemptAt: Long? = null,
    val metadataVersion: Int = 0,
    val coverSource: String = "NONE",
    val coverUpdatedAt: Long? = null,
    val artworkSourceTrust: Int = 0,
    val coverLookupState: String = "NOT_SYNCED",
    val coverLookupAttemptAt: Long? = null,
    val coverLookupUpdatedAt: Long? = null,
    val coverLookupVersion: Int = 0,
    val musicBrainzReleaseGroupMbid: String? = null,
    val genreSummary: String? = null,
    val genreSummaryVersion: Int = 0,
    val yearSourceTrust: Int = 0,
    val identitySourceTrust: Int = 0,
    val titleSortKey: String = "",
    val artistSortKey: String = "",
    val lastSeenTimestamp: Long = 0L,
    val deletedAt: Long? = null
)
