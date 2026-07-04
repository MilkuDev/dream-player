package org.milkdev.dreamplayer.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EmbeddedMbidStatus {
    UNKNOWN,
    FOUND,
    NOT_PRESENT,
    FAILED
}

enum class RemoteResolveStatus {
    PENDING,
    RESOLVED,
    NO_MATCH,
    FAILED
}

enum class MetadataEntityType {
    ALBUM,
    TRACK
}

enum class MetadataProvider {
    EMBEDDED,
    MUSICBRAINZ,
    LASTFM
}

@Entity(
    tableName = "album_releases",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["releaseMbid"], unique = true),
    ],
)
data class AlbumReleaseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val albumId: Long,
    val releaseMbid: String,
    val title: String? = null,
    val country: String? = null,
    val status: String? = null,
    val date: String? = null,
    val artworkUri: String? = null,
    val hydratedAt: Long? = null,
)

@Entity(
    tableName = "track_release_refs",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["releaseTrackMbid"], unique = true),
        Index(value = ["releaseMbid"]),
    ],
)
data class TrackReleaseRefEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Long,
    val releaseTrackMbid: String,
    val releaseMbid: String? = null,
    val mediumPosition: Int? = null,
    val trackPosition: Int? = null,
    val hydratedAt: Long? = null,
)

@Entity(
    tableName = "album_metadata_state",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["albumId"], unique = true),
        Index(value = ["remoteResolveStatus"]),
        Index(value = ["attemptTimestamp"]),
        Index(value = ["fetchedTimestamp"]),
    ],
)
data class AlbumMetadataStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val albumId: Long,
    val embeddedMbidStatus: String = EmbeddedMbidStatus.UNKNOWN.name,
    val remoteResolveStatus: String = RemoteResolveStatus.PENDING.name,
    val embeddedFingerprint: String? = null,
    val generation: Long = 0L,
    val attemptTimestamp: Long? = null,
    val fetchedTimestamp: Long? = null,
)

@Entity(
    tableName = "track_metadata_state",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trackId"], unique = true),
        Index(value = ["remoteResolveStatus"]),
        Index(value = ["attemptTimestamp"]),
        Index(value = ["fetchedTimestamp"]),
    ],
)
data class TrackMetadataStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Long,
    val embeddedMbidStatus: String = EmbeddedMbidStatus.UNKNOWN.name,
    val remoteResolveStatus: String = RemoteResolveStatus.PENDING.name,
    val embeddedFingerprint: String? = null,
    val generation: Long = 0L,
    val attemptTimestamp: Long? = null,
    val fetchedTimestamp: Long? = null,
)

@Entity(
    tableName = "metadata_resolution",
    indices = [
        Index(value = ["entityType", "entityId"]),
        Index(value = ["provider", "sourceId"]),
        Index(value = ["resolvedAt"]),
    ],
)
data class MetadataResolutionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entityType: String,
    val entityId: Long,
    val provider: String,
    val sourceId: String? = null,
    val confidence: Float = 0f,
    val identityTrust: Int = 0,
    val yearTrust: Int = 0,
    val genreTrust: Int = 0,
    val artworkTrust: Int = 0,
    val resolvedAt: Long,
)

