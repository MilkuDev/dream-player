package org.milkdev.dreamplayer.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.milkdev.dreamplayer.database.entities.PlaylistEntity
import org.milkdev.dreamplayer.database.entities.PlaylistTrackCrossRef
import org.milkdev.dreamplayer.database.entities.TrackWithArtwork

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insertPlaylist(
        playlist: PlaylistEntity
    ): Long

    @Upsert
    suspend fun upsertPlaylist(
        playlist: PlaylistEntity
    )

    @Query("""
        SELECT *
        FROM playlists
        WHERE isSystem = 0 OR id IN (:visibleSystemPlaylistIds)
        ORDER BY
            isSystem DESC,
            createdAt DESC
    """)
    fun getVisiblePlaylists(visibleSystemPlaylistIds: List<Long>): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insertTracks(crossRef: List<PlaylistTrackCrossRef>)

    @Query("""
    SELECT *
    FROM playlists
    WHERE id = :playlistId
    LIMIT 1
""")
    suspend fun getPlaylistById(
        playlistId: Long
    ): PlaylistEntity?

    @Query("""
        SELECT t.*,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(
                t.lastModified,
                COALESCE(a.localArtworkCheckedAt, 0),
                COALESCE(a.coverUpdatedAt, 0)
            ) AS artworkRevision
        FROM library_tracks t
        INNER JOIN playlist_track_cross_ref
        ON t.id = playlist_track_cross_ref.trackId
        LEFT JOIN albums a ON a.id = t.albumId
        WHERE playlist_track_cross_ref.playlistId = :playlistId
        AND t.isPresent = 1
        ORDER BY playlist_track_cross_ref.position ASC
    """)
    fun getTracksForPlaylist(
        playlistId: Long
    ): Flow<List<TrackWithArtwork>>

    @Query("""
        SELECT trackId
        FROM playlist_track_cross_ref
        WHERE playlistId = :playlistId
        ORDER BY position ASC
    """)
    suspend fun getTrackIdsForPlaylist(
        playlistId: Long
    ): List<Long>

    @Query("""
    DELETE FROM playlist_track_cross_ref
    WHERE playlistId = :playlistId
""")
    suspend fun clearPlaylist(
        playlistId: Long
    )

    @Transaction
    suspend fun replacePlaylistTracks(playlistId: Long, crossRefs: List<PlaylistTrackCrossRef>) {
        clearPlaylist(playlistId)
        if (crossRefs.isNotEmpty()) {
            insertTracks(crossRefs)
        }
    }
}
