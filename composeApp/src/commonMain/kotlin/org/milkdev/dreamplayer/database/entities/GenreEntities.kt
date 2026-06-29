package org.milkdev.dreamplayer.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "genres",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["sortKey"]),
    ],
)
data class GenreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val sortKey: String,
    val createdAt: Long,
)

@Entity(
    tableName = "album_genre_cross_ref",
    primaryKeys = ["albumId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["genreId"]),
        Index(value = ["genreId", "albumId"]),
    ],
)
data class AlbumGenreCrossRef(
    val albumId: Long,
    val genreId: Long,
    val rank: Int = 0,
    val sourceTrust: Int = 0,
)

@Entity(
    tableName = "track_genre_cross_ref",
    primaryKeys = ["trackId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["genreId"]),
        Index(value = ["genreId", "trackId"]),
    ],
)
data class TrackGenreCrossRef(
    val trackId: Long,
    val genreId: Long,
    val rank: Int = 0,
    val sourceTrust: Int = 0,
)

