package org.milkdev.dreamplayer.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.milkdev.dreamplayer.database.entities.AlbumGenreCrossRef
import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.AlbumMetadataStateEntity
import org.milkdev.dreamplayer.database.entities.AlbumReleaseEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.database.entities.GenreEntity
import org.milkdev.dreamplayer.database.entities.MetadataEntityType
import org.milkdev.dreamplayer.database.entities.MetadataProvider
import org.milkdev.dreamplayer.database.entities.MetadataResolutionEntity
import org.milkdev.dreamplayer.database.entities.PlaybackHistoryEntity
import org.milkdev.dreamplayer.database.entities.SyncAuditEntity
import org.milkdev.dreamplayer.database.entities.TrackGenreCrossRef
import org.milkdev.dreamplayer.database.entities.TrackEntity
import org.milkdev.dreamplayer.database.entities.TrackMetadataStateEntity
import org.milkdev.dreamplayer.database.entities.TrackWithArtwork
import org.milkdev.dreamplayer.library.AlbumListItem
import org.milkdev.dreamplayer.library.ArtistListItem
import org.milkdev.dreamplayer.library.GenreListItem
import org.milkdev.dreamplayer.library.LibrarySummary
import org.milkdev.dreamplayer.library.TrackListItem

@Dao
interface MusicDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryEntry(entry: PlaybackHistoryEntity)

    @Query("""
    SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
           a.year, a.genre,
           COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
           MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
    FROM playback_history h
    INNER JOIN library_tracks t ON h.trackId = t.id
    LEFT JOIN albums a ON t.albumId = a.id
    WHERE t.isPresent = 1
    ORDER BY h.timestamp DESC
    LIMIT 1000
""")
    fun getRecentlyPlayedTracks(): Flow<List<TrackListItem>>

    @Query("""
    SELECT g.id, g.name
    FROM genres g
    WHERE EXISTS (
        SELECT 1 FROM track_genre_cross_ref tg 
        INNER JOIN library_tracks t ON tg.trackId = t.id 
        WHERE tg.genreId = g.id AND t.isPresent = 1 AND t.deletedAt IS NULL
    )
    ORDER BY RANDOM() LIMIT 1
""")
    suspend fun getRandomGenreWithTracks(): GenreIdResult?

    @Query("SELECT t.id FROM library_tracks t WHERE t.isPresent = 1 ORDER BY t.titleSortKey ASC, t.id ASC")
    suspend fun getAllTrackIdsSortedByTitle(): List<Long>

    @Query("SELECT t.id FROM library_tracks t LEFT JOIN albums a ON t.albumId = a.id WHERE t.isPresent = 1 ORDER BY t.artistSortKey ASC, t.titleSortKey ASC, t.id ASC")
    suspend fun getAllTrackIdsSortedByArtist(): List<Long>

    @Query("SELECT t.id FROM library_tracks t LEFT JOIN albums a ON t.albumId = a.id WHERE t.isPresent = 1 ORDER BY lower(trim(t.albumName)) ASC, t.titleSortKey ASC, t.id ASC")
    suspend fun getAllTrackIdsSortedByAlbum(): List<Long>

    @Query("""
        SELECT t.id FROM library_tracks t 
        LEFT JOIN albums a ON t.albumId = a.id 
        WHERE t.isPresent = 1 
        ORDER BY CASE WHEN a.year IS NULL THEN 1 ELSE 0 END ASC, a.year ASC, t.titleSortKey ASC, t.id ASC
    """)
    suspend fun getAllTrackIdsSortedByYear(): List<Long>

    @Query("""
        SELECT t.id FROM library_tracks t 
        LEFT JOIN albums a ON t.albumId = a.id 
        WHERE t.isPresent = 1 
        ORDER BY CASE WHEN a.genre IS NULL OR a.genre = '' THEN 1 ELSE 0 END ASC, a.genre COLLATE NOCASE ASC, t.titleSortKey ASC, t.id ASC
    """)
    suspend fun getAllTrackIdsSortedByGenre(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtists(artists: List<ArtistEntity>)

    @Query("SELECT id, name FROM artists")
    suspend fun getArtistIdMap(): List<ArtistIdResult>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Query("SELECT id, title, artistId FROM albums")
    suspend fun getAlbumIdMap(): List<AlbumIdResult>

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    suspend fun getAlbumById(albumId: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id IN (:albumIds)")
    suspend fun getAlbumsByIds(albumIds: List<Long>): List<AlbumEntity>

    @Query("SELECT * FROM artists WHERE id = :artistId LIMIT 1")
    suspend fun getArtistById(artistId: Long): ArtistEntity?

    @Query("SELECT name FROM artists WHERE id = :artistId")
    suspend fun getArtistName(artistId: Long): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumRelease(release: AlbumReleaseEntity)

    @Query("SELECT * FROM album_releases WHERE albumId = :albumId ORDER BY id ASC")
    suspend fun getAlbumReleases(albumId: Long): List<AlbumReleaseEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenres(genres: List<GenreEntity>)

    @Query("SELECT id, name FROM genres WHERE name IN (:names)")
    suspend fun getGenreIdMap(names: List<String>): List<GenreIdResult>

    @Query("DELETE FROM album_genre_cross_ref WHERE albumId = :albumId")
    suspend fun clearAlbumGenres(albumId: Long)

    @Query("DELETE FROM track_genre_cross_ref WHERE trackId = :trackId")
    suspend fun clearTrackGenres(trackId: Long)

    @Upsert
    suspend fun upsertAlbumGenres(refs: List<AlbumGenreCrossRef>)

    @Upsert
    suspend fun upsertTrackGenres(refs: List<TrackGenreCrossRef>)

    @Upsert
    suspend fun upsertAlbumMetadataState(state: AlbumMetadataStateEntity)

    @Upsert
    suspend fun upsertTrackMetadataState(state: TrackMetadataStateEntity)

    @Insert
    suspend fun insertMetadataResolution(resolution: MetadataResolutionEntity)

    @Query(
        """
        UPDATE metadata_resolution
        SET confidence = MAX(confidence, :confidence),
            identityTrust = MAX(identityTrust, :identityTrust),
            yearTrust = MAX(yearTrust, :yearTrust),
            resolvedAt = MAX(resolvedAt, :resolvedAt)
        WHERE entityType = :entityType
        AND entityId = :entityId
        AND provider = :provider
        AND (
            sourceId = :sourceId
            OR (sourceId IS NULL AND :sourceId IS NULL)
        )
        """
    )
    suspend fun updateMetadataResolutionIfPresent(
        entityType: String,
        entityId: Long,
        provider: String,
        sourceId: String?,
        confidence: Float,
        identityTrust: Int,
        yearTrust: Int,
        resolvedAt: Long,
    ): Int

    @Query("SELECT * FROM track_metadata_state WHERE trackId = :trackId LIMIT 1")
    suspend fun getTrackMetadataState(trackId: Long): TrackMetadataStateEntity?

    @Query(
        """
        SELECT *
        FROM metadata_resolution
        WHERE entityType = :entityType AND entityId = :entityId
        ORDER BY id ASC
        """
    )
    suspend fun getMetadataResolutions(
        entityType: String,
        entityId: Long,
    ): List<MetadataResolutionEntity>

    @Query(
        """
        UPDATE albums
        SET genreSummary = :summary,
            genreSummaryVersion = genreSummaryVersion + 1,
            genre = :summary
        WHERE id = :albumId
        """
    )
    suspend fun updateAlbumGenreSummary(albumId: Long, summary: String?)

    @Transaction
    suspend fun replaceAlbumGenres(
        albumId: Long,
        genres: List<GenreEntity>,
        sourceTrust: Int,
    ) {
        val cleanGenres = genres.distinctBy { it.name }
        clearAlbumGenres(albumId)
        if (cleanGenres.isEmpty()) {
            updateAlbumGenreSummary(albumId, null)
            return
        }

        insertGenres(cleanGenres)
        val idsByName = getGenreIdMap(cleanGenres.map { it.name }).associate { it.name to it.id }
        upsertAlbumGenres(
            cleanGenres.mapIndexedNotNull { index, genre ->
                AlbumGenreCrossRef(
                    albumId = albumId,
                    genreId = idsByName[genre.name] ?: return@mapIndexedNotNull null,
                    rank = index,
                    sourceTrust = sourceTrust,
                )
            }
        )
        updateAlbumGenreSummary(albumId, cleanGenres.take(5).joinToString(", ") { it.name })
    }

    @Transaction
    suspend fun replaceTrackGenres(
        trackId: Long,
        genres: List<GenreEntity>,
        sourceTrust: Int,
    ) {
        val cleanGenres = genres.distinctBy { it.name }
        clearTrackGenres(trackId)
        if (cleanGenres.isEmpty()) return

        insertGenres(cleanGenres)
        val idsByName = getGenreIdMap(cleanGenres.map { it.name }).associate { it.name to it.id }
        upsertTrackGenres(
            cleanGenres.mapIndexedNotNull { index, genre ->
                TrackGenreCrossRef(
                    trackId = trackId,
                    genreId = idsByName[genre.name] ?: return@mapIndexedNotNull null,
                    rank = index,
                    sourceTrust = sourceTrust,
                )
            }
        )
    }

    @Transaction
    suspend fun applyEmbeddedMetadata(
        trackId: Long,
        expectedFilePath: String,
        expectedContentFingerprint: String,
        expectedGeneration: Long,
        recordingMbid: String?,
        releaseMbid: String?,
        releaseGroupMbid: String?,
        artistMbids: List<String>,
        albumArtistMbids: List<String>,
        year: Int?,
        genres: List<GenreEntity>,
        embeddedMbidStatus: String,
        embeddedFingerprint: String?,
        timestamp: Long,
        identityTrust: Int,
        yearTrust: Int,
        genreTrust: Int,
    ): Boolean {
        val track = getTracksByIds(listOf(trackId)).singleOrNull() ?: return false
        if (
            !track.isPresent ||
            track.deletedAt != null ||
            track.filePath != expectedFilePath ||
            track.contentFingerprint != expectedContentFingerprint ||
            track.lastSeenTimestamp != expectedGeneration
        ) {
            return false
        }

        val normalizedRecordingMbid = recordingMbid.normalizeMbid()
        val normalizedReleaseMbid = releaseMbid.normalizeMbid()
        val normalizedReleaseGroupMbid = releaseGroupMbid.normalizeMbid()
        val uniqueArtistMbids = artistMbids.mapNotNull { it.normalizeMbid() }.distinct()
        val uniqueAlbumArtistMbids = albumArtistMbids.mapNotNull { it.normalizeMbid() }.distinct()

        updateTrackEmbeddedIdentity(
            trackId = track.id,
            recordingMbid = normalizedRecordingMbid,
            identityTrust = identityTrust,
        )
        updateAlbumEmbeddedIdentityAndYear(
            albumId = track.albumId,
            releaseGroupMbid = normalizedReleaseGroupMbid,
            year = year,
            identityTrust = identityTrust,
            yearTrust = yearTrust,
        )

        normalizedReleaseMbid?.let { mbid ->
            insertAlbumRelease(
                AlbumReleaseEntity(
                    albumId = track.albumId,
                    releaseMbid = mbid,
                    date = year?.toString(),
                )
            )
            updateAlbumReleaseDateIfMissing(mbid, year?.toString())
        }

        uniqueArtistMbids.singleOrNull()?.let { artistMbid ->
            updateArtistEmbeddedIdentity(track.artistId, artistMbid)
        }

        upsertTrackEmbeddedMetadataState(
            trackId = track.id,
            embeddedMbidStatus = embeddedMbidStatus,
            embeddedFingerprint = embeddedFingerprint,
            generation = expectedGeneration,
            timestamp = timestamp,
        )
        if (genres.isNotEmpty()) {
            replaceTrackGenres(
                trackId = track.id,
                genres = genres,
                sourceTrust = genreTrust,
            )
        }

        normalizedRecordingMbid?.let { mbid ->
            insertEmbeddedResolutionIfMissing(
                entityType = MetadataEntityType.TRACK,
                entityId = track.id,
                sourceId = mbid,
                timestamp = timestamp,
                identityTrust = identityTrust,
            )
        }
        if (normalizedReleaseGroupMbid != null || year != null) {
            insertEmbeddedResolutionIfMissing(
                entityType = MetadataEntityType.ALBUM,
                entityId = track.albumId,
                sourceId = normalizedReleaseGroupMbid,
                timestamp = timestamp,
                identityTrust = identityTrust.takeIf { normalizedReleaseGroupMbid != null } ?: 0,
                yearTrust = yearTrust.takeIf { year != null } ?: 0,
            )
        }
        normalizedReleaseMbid?.let { mbid ->
            insertEmbeddedResolutionIfMissing(
                entityType = MetadataEntityType.ALBUM_RELEASE,
                entityId = track.albumId,
                sourceId = mbid,
                timestamp = timestamp,
                identityTrust = identityTrust,
            )
        }
        uniqueArtistMbids.forEach { mbid ->
            insertEmbeddedResolutionIfMissing(
                entityType = MetadataEntityType.ARTIST,
                entityId = track.artistId,
                sourceId = mbid,
                timestamp = timestamp,
                identityTrust = identityTrust,
            )
        }
        uniqueAlbumArtistMbids.forEach { mbid ->
            insertEmbeddedResolutionIfMissing(
                entityType = MetadataEntityType.ALBUM_ARTIST,
                entityId = track.albumId,
                sourceId = mbid,
                timestamp = timestamp,
                identityTrust = identityTrust,
            )
        }
        return true
    }

    suspend fun insertEmbeddedResolutionIfMissing(
        entityType: MetadataEntityType,
        entityId: Long,
        sourceId: String?,
        timestamp: Long,
        identityTrust: Int,
        yearTrust: Int = 0,
    ) {
        val type = entityType.name
        val provider = MetadataProvider.EMBEDDED.name
        val updated = updateMetadataResolutionIfPresent(
            entityType = type,
            entityId = entityId,
            provider = provider,
            sourceId = sourceId,
            confidence = 1f,
            identityTrust = identityTrust,
            yearTrust = yearTrust,
            resolvedAt = timestamp,
        )
        if (updated > 0) return

        insertMetadataResolution(
            MetadataResolutionEntity(
                entityType = type,
                entityId = entityId,
                provider = provider,
                sourceId = sourceId,
                confidence = 1f,
                identityTrust = identityTrust,
                yearTrust = yearTrust,
                resolvedAt = timestamp,
            )
        )
    }

    private fun String?.normalizeMbid(): String? {
        return this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM library_tracks WHERE isPresent = 1) AS trackCount,
            (
                SELECT COUNT(*)
                FROM albums a
                WHERE EXISTS (
                    SELECT 1 FROM library_tracks t
                    WHERE t.albumId = a.id AND t.isPresent = 1
                )
            ) AS albumCount,
            (
                SELECT COUNT(DISTINCT artistId)
                FROM library_tracks
                WHERE isPresent = 1
            ) AS artistCount,
            (
                SELECT COUNT(*)
                FROM albums
                WHERE metadataVersion < :currentMetadataVersion
                OR metadataState NOT IN ('DONE', 'NO_MATCH')
                OR localArtworkState = 'UNKNOWN'
                OR (
                    localArtworkState = 'MISSING'
                    AND (coverUri IS NULL OR coverUri = '')
                    AND (
                        coverLookupVersion < :currentCoverLookupVersion
                        OR coverLookupState NOT IN ('DONE', 'NO_MATCH')
                    )
                )
            ) AS pendingMetadataCount
        """
    )
    suspend fun getLibrarySummary(
        currentMetadataVersion: Int,
        currentCoverLookupVersion: Int,
    ): LibrarySummary

    @Query(
        """
        SELECT COUNT(*)
        FROM albums
        WHERE metadataVersion < :currentVersion
        OR metadataState NOT IN ('DONE', 'NO_MATCH')
        """
    )
    suspend fun getPendingMetadataCount(currentVersion: Int): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM albums a
        WHERE a.localArtworkState = 'UNKNOWN'
        AND EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        """
    )
    suspend fun getPendingLocalArtworkVerificationCount(): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM albums a
        WHERE EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        AND a.localArtworkState = 'MISSING'
        AND (a.coverUri IS NULL OR a.coverUri = '')
        AND (
            a.coverLookupVersion < :currentVersion
            OR (
                a.coverLookupState IN ('NOT_SYNCED', 'FAILED', 'PENDING')
                AND (
                    a.coverLookupState != 'FAILED' OR
                    a.coverLookupAttemptAt IS NULL OR
                    a.coverLookupAttemptAt < :retryTimestamp
                )
                AND (
                    a.coverLookupState != 'PENDING' OR
                    a.coverLookupAttemptAt IS NULL OR
                    a.coverLookupAttemptAt < :pendingTimeoutTimestamp
                )
            )
        )
        """
    )
    suspend fun getPendingCoverLookupCount(
        retryTimestamp: Long,
        pendingTimeoutTimestamp: Long,
        currentVersion: Int,
    ): Int

    @Query(
        """
        SELECT a.id, a.title, ar.name AS artistName, a.year, a.genre,
            COALESCE(NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM albums a
        INNER JOIN artists ar ON ar.id = a.artistId
        WHERE EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        AND (
            :cursorId = 0 OR
            a.titleSortKey > :cursorKey OR
            (a.titleSortKey = :cursorKey AND a.id > :cursorId)
        )
        ORDER BY a.titleSortKey ASC, a.id ASC
        LIMIT :limit
        """
    )
    suspend fun getAlbumListItemsByTitle(
        limit: Int,
        cursorKey: String,
        cursorId: Long,
    ): List<AlbumListItem>

    @Query(
        """
        SELECT a.id, a.title, ar.name AS artistName, a.year, a.genre,
            COALESCE(NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM albums a
        INNER JOIN artists ar ON ar.id = a.artistId
        WHERE EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        AND (
            :cursorId = 0 OR
            a.artistSortKey > :cursorKey OR
            (
                a.artistSortKey = :cursorKey AND (
                    a.titleSortKey > :cursorTieKey OR
                    (a.titleSortKey = :cursorTieKey AND a.id > :cursorId)
                )
            )
        )
        ORDER BY a.artistSortKey ASC, a.titleSortKey ASC, a.id ASC
        LIMIT :limit
        """
    )
    suspend fun getAlbumListItemsByArtist(
        limit: Int,
        cursorKey: String,
        cursorTieKey: String,
        cursorId: Long,
    ): List<AlbumListItem>

    @Query(
        """
        SELECT a.id, a.title, ar.name AS artistName, a.year, a.genre,
            COALESCE(NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM albums a
        INNER JOIN artists ar ON ar.id = a.artistId
        WHERE EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        AND (
            :cursorId = 0 OR
            (CASE WHEN a.year IS NULL THEN 1 ELSE 0 END) > :cursorMissingBucket OR
            (
                (CASE WHEN a.year IS NULL THEN 1 ELSE 0 END) = :cursorMissingBucket AND (
                    COALESCE(a.year, 0) > :cursorNumber OR
                    (
                        COALESCE(a.year, 0) = :cursorNumber AND (
                            a.titleSortKey > :cursorTieKey OR
                            (a.titleSortKey = :cursorTieKey AND a.id > :cursorId)
                        )
                    )
                )
            )
        )
        ORDER BY CASE WHEN a.year IS NULL THEN 1 ELSE 0 END ASC,
            a.year ASC, a.titleSortKey ASC, a.id ASC
        LIMIT :limit
        """
    )
    suspend fun getAlbumListItemsByYear(
        limit: Int,
        cursorNumber: Int,
        cursorMissingBucket: Int,
        cursorTieKey: String,
        cursorId: Long,
    ): List<AlbumListItem>

    @Query(
        """
        SELECT a.id, a.title, ar.name AS artistName, a.year, a.genre,
            COALESCE(NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM albums a
        INNER JOIN artists ar ON ar.id = a.artistId
        WHERE EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        AND (
            :cursorId = 0 OR
            (CASE WHEN a.genre IS NULL OR a.genre = '' THEN 1 ELSE 0 END) > :cursorMissingBucket OR
            (
                (CASE WHEN a.genre IS NULL OR a.genre = '' THEN 1 ELSE 0 END) = :cursorMissingBucket AND (
                    lower(COALESCE(a.genre, '')) > :cursorKey OR
                    (
                        lower(COALESCE(a.genre, '')) = :cursorKey AND (
                            a.titleSortKey > :cursorTieKey OR
                            (a.titleSortKey = :cursorTieKey AND a.id > :cursorId)
                        )
                    )
                )
            )
        )
        ORDER BY CASE WHEN a.genre IS NULL OR a.genre = '' THEN 1 ELSE 0 END ASC,
            a.genre COLLATE NOCASE ASC, a.titleSortKey ASC, a.id ASC
        LIMIT :limit
        """
    )
    suspend fun getAlbumListItemsByGenre(
        limit: Int,
        cursorKey: String,
        cursorMissingBucket: Int,
        cursorTieKey: String,
        cursorId: Long,
    ): List<AlbumListItem>

    @Query(
        """
        SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
            a.year, a.genre,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON t.albumId = a.id
        WHERE t.isPresent = 1
        AND (
            :cursorId = 0 OR
            t.titleSortKey > :cursorKey OR
            (t.titleSortKey = :cursorKey AND t.id > :cursorId)
        )
        ORDER BY t.titleSortKey ASC, t.id ASC
        LIMIT :limit
        """
    )
    suspend fun getTrackListItemsByTitle(
        limit: Int,
        cursorKey: String,
        cursorId: Long,
    ): List<TrackListItem>

    @Query(
        """
        SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
            a.year, a.genre,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON t.albumId = a.id
        WHERE t.isPresent = 1
        AND (
            :cursorId = 0 OR
            t.artistSortKey > :cursorKey OR
            (
                t.artistSortKey = :cursorKey AND (
                    t.titleSortKey > :cursorTieKey OR
                    (t.titleSortKey = :cursorTieKey AND t.id > :cursorId)
                )
            )
        )
        ORDER BY t.artistSortKey ASC, t.titleSortKey ASC, t.id ASC
        LIMIT :limit
        """
    )
    suspend fun getTrackListItemsByArtist(
        limit: Int,
        cursorKey: String,
        cursorTieKey: String,
        cursorId: Long,
    ): List<TrackListItem>

    @Query(
        """
        SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
            a.year, a.genre,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON t.albumId = a.id
        WHERE t.isPresent = 1
        AND (
            :cursorId = 0 OR
            lower(trim(t.albumName)) > :cursorKey OR
            (
                lower(trim(t.albumName)) = :cursorKey AND (
                    t.titleSortKey > :cursorTieKey OR
                    (t.titleSortKey = :cursorTieKey AND t.id > :cursorId)
                )
            )
        )
        ORDER BY lower(trim(t.albumName)) ASC, t.titleSortKey ASC, t.id ASC
        LIMIT :limit
        """
    )
    suspend fun getTrackListItemsByAlbum(
        limit: Int,
        cursorKey: String,
        cursorTieKey: String,
        cursorId: Long,
    ): List<TrackListItem>

    @Query(
        """
        SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
            a.year, a.genre,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON t.albumId = a.id
        WHERE t.isPresent = 1
        AND (
            :cursorId = 0 OR
            (CASE WHEN a.year IS NULL THEN 1 ELSE 0 END) > :cursorMissingBucket OR
            (
                (CASE WHEN a.year IS NULL THEN 1 ELSE 0 END) = :cursorMissingBucket AND (
                    COALESCE(a.year, 0) > :cursorNumber OR
                    (
                        COALESCE(a.year, 0) = :cursorNumber AND (
                            t.titleSortKey > :cursorTieKey OR
                            (t.titleSortKey = :cursorTieKey AND t.id > :cursorId)
                        )
                    )
                )
            )
        )
        ORDER BY CASE WHEN a.year IS NULL THEN 1 ELSE 0 END ASC,
            a.year ASC, t.titleSortKey ASC, t.id ASC
        LIMIT :limit
        """
    )
    suspend fun getTrackListItemsByYear(
        limit: Int,
        cursorNumber: Int,
        cursorMissingBucket: Int,
        cursorTieKey: String,
        cursorId: Long,
    ): List<TrackListItem>

    @Query(
        """
        SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
            a.year, a.genre,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON t.albumId = a.id
        WHERE t.isPresent = 1
        AND (
            :cursorId = 0 OR
            (CASE WHEN a.genre IS NULL OR a.genre = '' THEN 1 ELSE 0 END) > :cursorMissingBucket OR
            (
                (CASE WHEN a.genre IS NULL OR a.genre = '' THEN 1 ELSE 0 END) = :cursorMissingBucket AND (
                    lower(COALESCE(a.genre, '')) > :cursorKey OR
                    (
                        lower(COALESCE(a.genre, '')) = :cursorKey AND (
                            t.titleSortKey > :cursorTieKey OR
                            (t.titleSortKey = :cursorTieKey AND t.id > :cursorId)
                        )
                    )
                )
            )
        )
        ORDER BY CASE WHEN a.genre IS NULL OR a.genre = '' THEN 1 ELSE 0 END ASC,
            a.genre COLLATE NOCASE ASC, t.titleSortKey ASC, t.id ASC
        LIMIT :limit
        """
    )
    suspend fun getTrackListItemsByGenre(
        limit: Int,
        cursorKey: String,
        cursorMissingBucket: Int,
        cursorTieKey: String,
        cursorId: Long,
    ): List<TrackListItem>

    @Query(
        """
        SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
            a.year, a.genre,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON t.albumId = a.id
        WHERE t.isPresent = 1
        AND (
            :query = '' OR
            (:searchTitle AND t.titleSortKey LIKE '%' || :query || '%') OR
            (:searchArtist AND t.artistSortKey LIKE '%' || :query || '%') OR
            (:searchAlbum AND lower(trim(t.albumName)) LIKE '%' || :query || '%')
        )
        AND (
            :cursorId = 0 OR
            t.titleSortKey > :cursorKey OR
            (t.titleSortKey = :cursorKey AND t.id > :cursorId)
        )
        ORDER BY t.titleSortKey ASC, t.id ASC
        LIMIT :limit
        """
    )
    suspend fun searchTrackListItems(
        query: String,
        searchTitle: Boolean,
        searchArtist: Boolean,
        searchAlbum: Boolean,
        limit: Int,
        cursorKey: String,
        cursorId: Long,
    ): List<TrackListItem>

    @Query(
        """
        SELECT ar.id, ar.name,
            COUNT(DISTINCT al.id) AS albumCount,
            COUNT(t.id) AS trackCount,
            COALESCE(
                (
                    SELECT NULLIF(tt.albumArtUri, '')
                    FROM library_tracks tt
                    WHERE tt.artistId = ar.id AND tt.isPresent = 1 AND tt.albumArtUri IS NOT NULL AND tt.albumArtUri != ''
                    ORDER BY tt.titleSortKey ASC
                    LIMIT 1
                ),
                (
                    SELECT NULLIF(aa.albumArtUri, '')
                    FROM albums aa
                    WHERE aa.artistId = ar.id AND aa.albumArtUri IS NOT NULL AND aa.albumArtUri != ''
                    ORDER BY aa.titleSortKey ASC
                    LIMIT 1
                ),
                (
                    SELECT NULLIF(aa.coverUri, '')
                    FROM albums aa
                    WHERE aa.artistId = ar.id AND aa.coverUri IS NOT NULL AND aa.coverUri != ''
                    ORDER BY aa.titleSortKey ASC
                    LIMIT 1
                )
            ) AS artworkUri
        FROM artists ar
        INNER JOIN library_tracks t ON t.artistId = ar.id AND t.isPresent = 1
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE (
            :cursorId = 0 OR
            lower(trim(ar.name)) > :cursorKey OR
            (lower(trim(ar.name)) = :cursorKey AND ar.id > :cursorId)
        )
        GROUP BY ar.id, ar.name
        ORDER BY lower(trim(ar.name)) ASC, ar.id ASC
        LIMIT :limit
        """
    )
    suspend fun getArtistListItemsByName(
        limit: Int,
        cursorKey: String,
        cursorId: Long,
    ): List<ArtistListItem>

    @Query(
        """
        SELECT g.id, g.name,
            COUNT(DISTINCT a.id) AS albumCount,
            COUNT(DISTINCT t.id) AS trackCount
        FROM genres g
        LEFT JOIN album_genre_cross_ref ag ON ag.genreId = g.id
        LEFT JOIN albums a ON a.id = ag.albumId
            AND a.deletedAt IS NULL
            AND EXISTS (
                SELECT 1 FROM library_tracks at
                WHERE at.albumId = a.id AND at.isPresent = 1 AND at.deletedAt IS NULL
            )
        LEFT JOIN track_genre_cross_ref tg ON tg.genreId = g.id
        LEFT JOIN library_tracks t ON t.id = tg.trackId
            AND t.isPresent = 1
            AND t.deletedAt IS NULL
        WHERE (
            :cursorId = 0 OR
            g.sortKey > :cursorKey OR
            (g.sortKey = :cursorKey AND g.id > :cursorId)
        )
        GROUP BY g.id, g.name, g.sortKey
        HAVING albumCount > 0 OR trackCount > 0
        ORDER BY g.sortKey ASC, g.id ASC
        LIMIT :limit
        """
    )
    suspend fun getGenreListItemsByName(
        limit: Int,
        cursorKey: String,
        cursorId: Long,
    ): List<GenreListItem>

    @Query(
        """
        SELECT a.id, a.title, ar.name AS artistName, a.year, COALESCE(a.genreSummary, a.genre) AS genre,
            COALESCE(NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM album_genre_cross_ref ag
        INNER JOIN albums a ON a.id = ag.albumId
        INNER JOIN artists ar ON ar.id = a.artistId
        WHERE ag.genreId = :genreId
        AND a.deletedAt IS NULL
        AND EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1 AND t.deletedAt IS NULL
        )
        ORDER BY a.titleSortKey ASC, a.id ASC
        """
    )
    fun getAlbumsByGenre(genreId: Long): Flow<List<AlbumListItem>>

    @Query(
        """
        SELECT t.id, t.title, t.artistName, t.albumName AS albumTitle, t.durationMs,
            a.year, COALESCE(a.genreSummary, a.genre) AS genre,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(t.lastModified, COALESCE(a.localArtworkCheckedAt, 0), COALESCE(a.coverUpdatedAt, 0)) AS artworkRevision
        FROM track_genre_cross_ref tg
        INNER JOIN library_tracks t ON t.id = tg.trackId
        LEFT JOIN albums a ON t.albumId = a.id
        WHERE tg.genreId = :genreId
        AND t.isPresent = 1
        AND t.deletedAt IS NULL
        ORDER BY t.titleSortKey ASC, t.id ASC
        """
    )
    fun getTracksByGenre(genreId: Long): Flow<List<TrackListItem>>

    @Query(
        """
        SELECT *
        FROM albums
        WHERE metadataVersion < :currentVersion
        OR (
            metadataState IN ('NOT_SYNCED', 'FAILED', 'PENDING')
            AND (
                metadataState != 'FAILED' OR
                lastMetadataAttemptAt IS NULL OR
                lastMetadataAttemptAt < :retryTimestamp
            )
            AND (
                metadataState != 'PENDING' OR
                lastMetadataAttemptAt IS NULL OR
                lastMetadataAttemptAt < :pendingTimeoutTimestamp
            )
        )
        ORDER BY
            CASE metadataState
                WHEN 'NOT_SYNCED' THEN 0
                WHEN 'PENDING' THEN 1
                WHEN 'FAILED' THEN 2
                ELSE 3
            END ASC,
            COALESCE(lastMetadataAttemptAt, 0) ASC,
            id ASC
        LIMIT :limit
        """
    )
    suspend fun getAlbumsToSync(
        limit: Int,
        retryTimestamp: Long,
        pendingTimeoutTimestamp: Long,
        currentVersion: Int,
    ): List<AlbumEntity>

    @Query(
        """
        SELECT a.*, ar.name AS artistName
        FROM albums a
        INNER JOIN artists ar ON ar.id = a.artistId
        WHERE EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        AND a.localArtworkState = 'MISSING'
        AND (a.coverUri IS NULL OR a.coverUri = '')
        AND (
            a.coverLookupVersion < :currentVersion
            OR (
                a.coverLookupState IN ('NOT_SYNCED', 'FAILED', 'PENDING')
                AND (
                    a.coverLookupState != 'FAILED' OR
                    a.coverLookupAttemptAt IS NULL OR
                    a.coverLookupAttemptAt < :retryTimestamp
                )
                AND (
                    a.coverLookupState != 'PENDING' OR
                    a.coverLookupAttemptAt IS NULL OR
                    a.coverLookupAttemptAt < :pendingTimeoutTimestamp
                )
            )
        )
        ORDER BY
            CASE a.coverLookupState
                WHEN 'NOT_SYNCED' THEN 0
                WHEN 'PENDING' THEN 1
                WHEN 'FAILED' THEN 2
                ELSE 3
            END ASC,
            COALESCE(a.coverLookupAttemptAt, 0) ASC,
            a.id ASC
        LIMIT :limit
        """
    )
    suspend fun getAlbumsForCoverLookup(
        limit: Int,
        retryTimestamp: Long,
        pendingTimeoutTimestamp: Long,
        currentVersion: Int,
    ): List<AlbumCoverLookupCandidate>

    @Query(
        """
        SELECT a.*, ar.name AS artistName
        FROM albums a
        INNER JOIN artists ar ON ar.id = a.artistId
        WHERE EXISTS (
            SELECT 1 FROM library_tracks t
            WHERE t.albumId = a.id AND t.isPresent = 1
        )
        AND a.localArtworkState = 'MISSING'
        AND (a.coverUri IS NULL OR a.coverUri = '')
        ORDER BY
            CASE a.coverLookupState
                WHEN 'NOT_SYNCED' THEN 0
                WHEN 'FAILED' THEN 1
                WHEN 'NO_MATCH' THEN 2
                WHEN 'PENDING' THEN 3
                ELSE 4
            END ASC,
            COALESCE(a.coverLookupAttemptAt, 0) ASC,
            a.id ASC
        LIMIT :limit
        """
    )
    suspend fun getAlbumsForForcedCoverLookup(limit: Int): List<AlbumCoverLookupCandidate>

    @Update
    suspend fun updateAlbum(album: AlbumEntity)

    @Transaction
    suspend fun updateAlbumPreservingLocalArtwork(album: AlbumEntity) {
        val current = getAlbumById(album.id) ?: return
        updateAlbum(
            album.copy(
                albumArtUri = current.albumArtUri,
                localArtworkKey = current.localArtworkKey,
                localArtworkFingerprint = current.localArtworkFingerprint,
                localArtworkState = current.localArtworkState,
                localArtworkCheckedAt = current.localArtworkCheckedAt,
                coverSource = if (
                    current.localArtworkState != "MISSING" &&
                    !current.albumArtUri.isNullOrBlank()
                ) {
                    current.coverSource
                } else {
                    album.coverSource
                },
            )
        )
    }

    @Query("UPDATE albums SET metadataState = :state, lastMetadataAttemptAt = :timestamp WHERE id = :albumId")
    suspend fun updateAlbumSyncState(albumId: Long, state: String, timestamp: Long)

    @Query("UPDATE albums SET coverLookupState = :state, coverLookupAttemptAt = :timestamp WHERE id = :albumId")
    suspend fun updateAlbumCoverLookupState(albumId: Long, state: String, timestamp: Long)

    @Query(
        """
        UPDATE library_tracks
        SET musicBrainzRecordingMbid = CASE
                WHEN :recordingMbid IS NOT NULL AND (musicBrainzRecordingMbid IS NULL OR :identityTrust > identitySourceTrust)
                THEN :recordingMbid
                ELSE musicBrainzRecordingMbid
            END,
            identitySourceTrust = CASE
                WHEN :recordingMbid IS NOT NULL AND (musicBrainzRecordingMbid IS NULL OR :identityTrust > identitySourceTrust)
                THEN :identityTrust
                ELSE identitySourceTrust
            END
        WHERE id = :trackId
        """
    )
    suspend fun updateTrackEmbeddedIdentity(
        trackId: Long,
        recordingMbid: String?,
        identityTrust: Int,
    )

    @Query(
        """
        UPDATE albums
        SET musicBrainzReleaseGroupMbid = CASE
                WHEN :releaseGroupMbid IS NOT NULL
                    AND (musicBrainzReleaseGroupMbid IS NULL OR :identityTrust > identitySourceTrust)
                THEN :releaseGroupMbid
                ELSE musicBrainzReleaseGroupMbid
            END,
            identitySourceTrust = CASE
                WHEN :releaseGroupMbid IS NOT NULL
                    AND (musicBrainzReleaseGroupMbid IS NULL OR :identityTrust > identitySourceTrust)
                THEN :identityTrust
                ELSE identitySourceTrust
            END,
            year = CASE
                WHEN :year IS NOT NULL AND (year IS NULL OR :yearTrust > yearSourceTrust)
                THEN :year
                ELSE year
            END,
            yearSourceTrust = CASE
                WHEN :year IS NOT NULL AND (year IS NULL OR :yearTrust > yearSourceTrust)
                THEN :yearTrust
                ELSE yearSourceTrust
            END
        WHERE id = :albumId
        """
    )
    suspend fun updateAlbumEmbeddedIdentityAndYear(
        albumId: Long,
        releaseGroupMbid: String?,
        year: Int?,
        identityTrust: Int,
        yearTrust: Int,
    )

    @Query(
        """
        UPDATE artists
        SET musicBrainzArtistMbid = :artistMbid
        WHERE id = :artistId AND musicBrainzArtistMbid IS NULL
        """
    )
    suspend fun updateArtistEmbeddedIdentity(
        artistId: Long,
        artistMbid: String,
    )

    @Query(
        """
        UPDATE album_releases
        SET date = COALESCE(date, :date)
        WHERE releaseMbid = :releaseMbid
        """
    )
    suspend fun updateAlbumReleaseDateIfMissing(
        releaseMbid: String,
        date: String?,
    )

    @Query(
        """
        INSERT INTO track_metadata_state (
            trackId,
            embeddedMbidStatus,
            remoteResolveStatus,
            embeddedFingerprint,
            generation,
            attemptTimestamp,
            fetchedTimestamp
        )
        VALUES (
            :trackId,
            :embeddedMbidStatus,
            'PENDING',
            :embeddedFingerprint,
            :generation,
            :timestamp,
            :timestamp
        )
        ON CONFLICT(trackId) DO UPDATE SET
            embeddedMbidStatus = excluded.embeddedMbidStatus,
            embeddedFingerprint = excluded.embeddedFingerprint,
            generation = excluded.generation,
            attemptTimestamp = excluded.attemptTimestamp,
            fetchedTimestamp = excluded.fetchedTimestamp
        """
    )
    suspend fun upsertTrackEmbeddedMetadataState(
        trackId: Long,
        embeddedMbidStatus: String,
        embeddedFingerprint: String?,
        generation: Long,
        timestamp: Long,
    )

    @Query(
        """
        UPDATE albums
        SET albumArtUri = CASE
                WHEN :localArtworkState = 'PRESENT' THEN :albumArtUri
                WHEN :localArtworkState = 'MISSING' THEN NULL
                WHEN localArtworkKey IS NOT :localArtworkKey
                    OR localArtworkFingerprint != :localArtworkFingerprint
                THEN NULL
                ELSE albumArtUri
            END,
            localArtworkKey = :localArtworkKey,
            localArtworkFingerprint = :localArtworkFingerprint,
            localArtworkState = CASE
                WHEN :localArtworkState != 'UNKNOWN' THEN :localArtworkState
                WHEN localArtworkKey IS NOT :localArtworkKey
                    OR localArtworkFingerprint != :localArtworkFingerprint
                THEN 'UNKNOWN'
                ELSE localArtworkState
            END,
            localArtworkCheckedAt = CASE
                WHEN :localArtworkState != 'UNKNOWN' THEN :timestamp
                WHEN localArtworkKey IS NOT :localArtworkKey
                    OR localArtworkFingerprint != :localArtworkFingerprint
                THEN NULL
                ELSE localArtworkCheckedAt
            END,
            coverSource = CASE
                WHEN :localArtworkState = 'PRESENT' THEN :coverSource
                WHEN :localArtworkState = 'MISSING'
                    OR localArtworkKey IS NOT :localArtworkKey
                    OR localArtworkFingerprint != :localArtworkFingerprint
                THEN CASE
                    WHEN coverUri IS NOT NULL AND coverUri != '' THEN 'REMOTE'
                    ELSE 'NONE'
                END
                ELSE coverSource
            END,
            lastSeenTimestamp = :timestamp,
            titleSortKey = :titleSortKey,
            artistSortKey = :artistSortKey,
            deletedAt = NULL
        WHERE id = :albumId
        """
    )
    suspend fun updateAlbumScanFields(
        albumId: Long,
        albumArtUri: String?,
        coverSource: String,
        localArtworkKey: String?,
        localArtworkFingerprint: String,
        localArtworkState: String,
        timestamp: Long,
        titleSortKey: String,
        artistSortKey: String,
    )

    @Transaction
    suspend fun updateAlbumsScanFields(updates: List<AlbumScanFieldsUpdate>) {
        updates.forEach { update ->
            updateAlbumScanFields(
                albumId = update.albumId,
                albumArtUri = update.albumArtUri,
                coverSource = update.coverSource,
                localArtworkKey = update.localArtworkKey,
                localArtworkFingerprint = update.localArtworkFingerprint,
                localArtworkState = update.localArtworkState,
                timestamp = update.timestamp,
                titleSortKey = update.titleSortKey,
                artistSortKey = update.artistSortKey,
            )
        }
    }

    @Query(
        """
        UPDATE albums
        SET albumArtUri = CASE
                WHEN :state = 'PRESENT' THEN :artworkUri
                WHEN :state = 'MISSING' THEN NULL
                ELSE albumArtUri
            END,
            localArtworkState = :state,
            localArtworkCheckedAt = :timestamp,
            coverSource = CASE
                WHEN :state = 'PRESENT' THEN :coverSource
                WHEN :state = 'MISSING' THEN CASE
                    WHEN coverUri IS NOT NULL AND coverUri != '' THEN 'REMOTE'
                    ELSE 'NONE'
                END
                ELSE coverSource
            END,
            coverLookupState = CASE
                WHEN :state = 'MISSING' AND (coverUri IS NULL OR coverUri = '') THEN 'NOT_SYNCED'
                ELSE coverLookupState
            END,
            coverLookupAttemptAt = CASE
                WHEN :state = 'MISSING' AND (coverUri IS NULL OR coverUri = '') THEN NULL
                ELSE coverLookupAttemptAt
            END,
            coverLookupVersion = CASE
                WHEN :state = 'MISSING' AND (coverUri IS NULL OR coverUri = '') THEN 0
                ELSE coverLookupVersion
            END
        WHERE id = :albumId
        """
    )
    suspend fun updateLocalArtworkVerification(
        albumId: Long,
        state: String,
        artworkUri: String?,
        coverSource: String,
        timestamp: Long,
    )

    @Query("UPDATE albums SET deletedAt = :timestamp WHERE lastSeenTimestamp < :threshold AND deletedAt IS NULL")
    suspend fun markOldAlbumsDeleted(threshold: Long, timestamp: Long)

    @Query("DELETE FROM albums WHERE deletedAt IS NOT NULL AND deletedAt < :threshold")
    suspend fun purgeDeletedAlbums(threshold: Long)

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM library_tracks WHERE filePath IN (:filePaths)")
    suspend fun getTracksByPaths(filePaths: List<String>): List<TrackEntity>

    @Query("SELECT * FROM library_tracks WHERE id IN (:trackIds)")
    suspend fun getTracksByIds(trackIds: List<Long>): List<TrackEntity>

    @Query(
        """
        SELECT t.*,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(
                t.lastModified,
                COALESCE(a.localArtworkCheckedAt, 0),
                COALESCE(a.coverUpdatedAt, 0)
            ) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON a.id = t.albumId
        WHERE t.id IN (:trackIds)
        """
    )
    suspend fun getTracksWithArtworkByIds(trackIds: List<Long>): List<TrackWithArtwork>

    @Query("SELECT * FROM library_tracks WHERE mediaStoreId IN (:mediaStoreIds)")
    suspend fun getTracksByMediaStoreIds(mediaStoreIds: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM library_tracks WHERE isPresent = 0")
    suspend fun getMissingTracks(): List<TrackEntity>

    @Query("SELECT filePath FROM library_tracks WHERE isPresent = 1")
    suspend fun getPresentTrackPaths(): List<String>

    @Query(
        """
        SELECT t.*,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(
                t.lastModified,
                COALESCE(a.localArtworkCheckedAt, 0),
                COALESCE(a.coverUpdatedAt, 0)
            ) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON a.id = t.albumId
        WHERE t.albumId = :albumId AND t.isPresent = 1
        ORDER BY t.titleSortKey ASC, t.id ASC
        """
    )
    fun getTracksWithArtworkByAlbum(albumId: Long): Flow<List<TrackWithArtwork>>

    @Query(
        """
        SELECT t.*,
            COALESCE(NULLIF(t.albumArtUri, ''), NULLIF(a.albumArtUri, ''), NULLIF(a.coverUri, '')) AS artworkUri,
            MAX(
                t.lastModified,
                COALESCE(a.localArtworkCheckedAt, 0),
                COALESCE(a.coverUpdatedAt, 0)
            ) AS artworkRevision
        FROM library_tracks t
        LEFT JOIN albums a ON a.id = t.albumId
        WHERE t.artistId = :artistId AND t.isPresent = 1
        ORDER BY t.titleSortKey ASC, t.id ASC
        """
    )
    fun getTracksWithArtworkByArtist(artistId: Long): Flow<List<TrackWithArtwork>>

    @Query("UPDATE library_tracks SET isPresent = 0, availability = 'MISSING', deletedAt = :timestamp WHERE filePath IN (:filePaths)")
    suspend fun markTracksMissingByPaths(filePaths: List<String>, timestamp: Long)

    @Query("DELETE FROM library_tracks WHERE deletedAt IS NOT NULL AND deletedAt < :threshold")
    suspend fun purgeDeletedTracks(threshold: Long)

    @Query(
        """
        DELETE FROM genres
        WHERE id NOT IN (SELECT genreId FROM album_genre_cross_ref)
        AND id NOT IN (SELECT genreId FROM track_genre_cross_ref)
        """
    )
    suspend fun purgeUnusedGenres()

    @Insert
    suspend fun insertAudit(audit: SyncAuditEntity)

    @Query("SELECT * FROM library_tracks WHERE isPresent = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomPresentTracks(limit: Int): List<TrackEntity>
}

data class ArtistIdResult(val id: Long, val name: String)
data class AlbumIdResult(val id: Long, val title: String, val artistId: Long)
data class GenreIdResult(val id: Long, val name: String)
data class AlbumCoverLookupCandidate(
    @Embedded val album: AlbumEntity,
    val artistName: String,
)
data class AlbumScanFieldsUpdate(
    val albumId: Long,
    val albumArtUri: String?,
    val coverSource: String,
    val localArtworkKey: String?,
    val localArtworkFingerprint: String,
    val localArtworkState: String,
    val timestamp: Long,
    val titleSortKey: String,
    val artistSortKey: String,
)
