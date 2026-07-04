@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import org.milkdev.dreamplayer.database.dao.MusicDao
import org.milkdev.dreamplayer.database.dao.PlaylistDao
import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.AlbumGenreCrossRef
import org.milkdev.dreamplayer.database.entities.AlbumMetadataStateEntity
import org.milkdev.dreamplayer.database.entities.AlbumReleaseEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.database.entities.GenreEntity
import org.milkdev.dreamplayer.database.entities.MetadataResolutionEntity
import org.milkdev.dreamplayer.database.entities.PlaybackHistoryEntity
import org.milkdev.dreamplayer.database.entities.PlaylistEntity
import org.milkdev.dreamplayer.database.entities.PlaylistTrackCrossRef
import org.milkdev.dreamplayer.database.entities.SyncAuditEntity
import org.milkdev.dreamplayer.database.entities.TrackEntity
import org.milkdev.dreamplayer.database.entities.TrackGenreCrossRef
import org.milkdev.dreamplayer.database.entities.TrackMetadataStateEntity
import org.milkdev.dreamplayer.database.entities.TrackReleaseRefEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        PlaylistTrackCrossRef::class,
        SyncAuditEntity::class,
        AlbumReleaseEntity::class,
        TrackReleaseRefEntity::class,
        AlbumMetadataStateEntity::class,
        TrackMetadataStateEntity::class,
        MetadataResolutionEntity::class,
        GenreEntity::class,
        AlbumGenreCrossRef::class,
        TrackGenreCrossRef::class,
        PlaybackHistoryEntity::class,
    ],
    version = 8
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun musicDao(): MusicDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

val Migration1To2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("DROP TABLE IF EXISTS migration_playlists_backup")
        connection.execSql(
            """
            CREATE TEMP TABLE migration_playlists_backup AS
            SELECT id, name, createdAt
            FROM playlists
            """.trimIndent()
        )

        connection.execSql("DROP TABLE IF EXISTS playlist_tracks")
        connection.execSql("DROP TABLE playlists")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                isSystem INTEGER NOT NULL,
                editable INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.execSql(
            """
            INSERT INTO playlists (id, name, createdAt, isSystem, editable)
            SELECT id, name, createdAt, 0, 1
            FROM migration_playlists_backup
            """.trimIndent()
        )

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS artists (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL
            )
            """.trimIndent()
        )
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_artists_name ON artists(name)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS albums (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                artistId INTEGER NOT NULL,
                albumArtUri TEXT,
                FOREIGN KEY(artistId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent()
        )
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_albums_artistId_title ON albums(artistId, title)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS library_tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                filePath TEXT NOT NULL,
                title TEXT NOT NULL,
                artistName TEXT NOT NULL,
                albumName TEXT NOT NULL,
                artistId INTEGER NOT NULL,
                albumId INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                fileSize INTEGER NOT NULL,
                lastModified INTEGER NOT NULL,
                albumArtUri TEXT,
                isPresent INTEGER NOT NULL,
                lastSeenTimestamp INTEGER NOT NULL,
                FOREIGN KEY(artistId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(albumId) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent()
        )
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_library_tracks_filePath ON library_tracks(filePath)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_artistId ON library_tracks(artistId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_albumId ON library_tracks(albumId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_title ON library_tracks(title)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_artistId_title_durationMs_fileSize ON library_tracks(artistId, title, durationMs, fileSize)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS playlist_track_cross_ref (
                playlistId INTEGER NOT NULL,
                trackId INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(playlistId, trackId),
                FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(trackId) REFERENCES library_tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE INDEX IF NOT EXISTS index_playlist_track_cross_ref_trackId ON playlist_track_cross_ref(trackId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_playlist_track_cross_ref_playlistId ON playlist_track_cross_ref(playlistId)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS sync_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                tracksFound INTEGER NOT NULL,
                status TEXT NOT NULL,
                errorLog TEXT
            )
            """.trimIndent()
        )

        connection.execSql("DROP TABLE migration_playlists_backup")
    }
}

val Migration2To3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN mediaStoreId INTEGER")
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN contentFingerprint TEXT NOT NULL DEFAULT ''")
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN fileHash TEXT")
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN availability TEXT NOT NULL DEFAULT 'AVAILABLE'")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_mediaStoreId ON library_tracks(mediaStoreId)")
    }
}

val Migration3To4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        // --- AlbumEntity updates ---
        connection.execSql("ALTER TABLE albums ADD COLUMN coverUri TEXT")
        connection.execSql("ALTER TABLE albums ADD COLUMN year INTEGER")
        connection.execSql("ALTER TABLE albums ADD COLUMN genre TEXT")
        connection.execSql("ALTER TABLE albums ADD COLUMN metadataState TEXT NOT NULL DEFAULT 'NOT_SYNCED'")
        connection.execSql("ALTER TABLE albums ADD COLUMN metadataUpdatedAt INTEGER")
        connection.execSql("ALTER TABLE albums ADD COLUMN lastMetadataAttemptAt INTEGER")
        connection.execSql("ALTER TABLE albums ADD COLUMN metadataVersion INTEGER NOT NULL DEFAULT 0")
        connection.execSql("ALTER TABLE albums ADD COLUMN coverSource TEXT NOT NULL DEFAULT 'NONE'")
        connection.execSql("ALTER TABLE albums ADD COLUMN coverUpdatedAt INTEGER")
        connection.execSql("ALTER TABLE albums ADD COLUMN titleSortKey TEXT NOT NULL DEFAULT ''")
        connection.execSql("ALTER TABLE albums ADD COLUMN artistSortKey TEXT NOT NULL DEFAULT ''")
        connection.execSql("ALTER TABLE albums ADD COLUMN lastSeenTimestamp INTEGER NOT NULL DEFAULT 0")

        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_year ON albums(year)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_genre ON albums(genre)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_metadataState ON albums(metadataState)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_lastMetadataAttemptAt ON albums(lastMetadataAttemptAt)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_titleSortKey ON albums(titleSortKey)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_artistSortKey ON albums(artistSortKey)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_lastSeenTimestamp ON albums(lastSeenTimestamp)")

        // --- TrackEntity updates ---
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN titleSortKey TEXT NOT NULL DEFAULT ''")
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN artistSortKey TEXT NOT NULL DEFAULT ''")

        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_titleSortKey ON library_tracks(titleSortKey)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_artistSortKey ON library_tracks(artistSortKey)")
    }
}

val Migration4To5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            """
            UPDATE albums
            SET titleSortKey = lower(trim(title))
            WHERE titleSortKey = ''
            """.trimIndent()
        )
        connection.execSql(
            """
            UPDATE albums
            SET artistSortKey = (
                SELECT lower(trim(name))
                FROM artists
                WHERE artists.id = albums.artistId
            )
            WHERE artistSortKey = ''
            """.trimIndent()
        )
        connection.execSql(
            """
            UPDATE albums
            SET lastSeenTimestamp = COALESCE(
                (
                    SELECT MAX(lastSeenTimestamp)
                    FROM library_tracks
                    WHERE library_tracks.albumId = albums.id
                ),
                lastSeenTimestamp
            )
            WHERE lastSeenTimestamp = 0
            """.trimIndent()
        )
        connection.execSql(
            """
            UPDATE library_tracks
            SET titleSortKey = lower(trim(title))
            WHERE titleSortKey = ''
            """.trimIndent()
        )
        connection.execSql(
            """
            UPDATE library_tracks
            SET artistSortKey = lower(trim(artistName))
            WHERE artistSortKey = ''
            """.trimIndent()
        )

        rebuildAlbumsAndTracksForVersion5(connection)
    }
}

val Migration5To6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE albums ADD COLUMN coverLookupState TEXT NOT NULL DEFAULT 'NOT_SYNCED'")
        connection.execSql("ALTER TABLE albums ADD COLUMN coverLookupAttemptAt INTEGER")
        connection.execSql("ALTER TABLE albums ADD COLUMN coverLookupUpdatedAt INTEGER")
        connection.execSql("ALTER TABLE albums ADD COLUMN coverLookupVersion INTEGER NOT NULL DEFAULT 0")
        connection.execSql("ALTER TABLE albums ADD COLUMN musicBrainzReleaseGroupMbid TEXT")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_coverLookupState ON albums(coverLookupState)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_coverLookupAttemptAt ON albums(coverLookupAttemptAt)")
    }
}

val Migration6To7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql("ALTER TABLE artists ADD COLUMN musicBrainzArtistMbid TEXT")

        connection.execSql("ALTER TABLE albums ADD COLUMN artworkSourceTrust INTEGER NOT NULL DEFAULT 0")
        connection.execSql("ALTER TABLE albums ADD COLUMN genreSummary TEXT")
        connection.execSql("ALTER TABLE albums ADD COLUMN genreSummaryVersion INTEGER NOT NULL DEFAULT 0")
        connection.execSql("ALTER TABLE albums ADD COLUMN yearSourceTrust INTEGER NOT NULL DEFAULT 0")
        connection.execSql("ALTER TABLE albums ADD COLUMN identitySourceTrust INTEGER NOT NULL DEFAULT 0")
        connection.execSql("ALTER TABLE albums ADD COLUMN deletedAt INTEGER")
        connection.execSql("UPDATE albums SET genreSummary = genre WHERE genre IS NOT NULL AND genre != ''")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_musicBrainzReleaseGroupMbid ON albums(musicBrainzReleaseGroupMbid)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_deletedAt ON albums(deletedAt)")

        connection.execSql("ALTER TABLE library_tracks ADD COLUMN musicBrainzRecordingMbid TEXT")
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN identitySourceTrust INTEGER NOT NULL DEFAULT 0")
        connection.execSql("ALTER TABLE library_tracks ADD COLUMN deletedAt INTEGER")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_musicBrainzRecordingMbid ON library_tracks(musicBrainzRecordingMbid)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_deletedAt ON library_tracks(deletedAt)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS album_releases (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                albumId INTEGER NOT NULL,
                releaseMbid TEXT NOT NULL,
                title TEXT,
                country TEXT,
                status TEXT,
                date TEXT,
                artworkUri TEXT,
                hydratedAt INTEGER,
                FOREIGN KEY(albumId) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE INDEX IF NOT EXISTS index_album_releases_albumId ON album_releases(albumId)")
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_album_releases_releaseMbid ON album_releases(releaseMbid)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS track_release_refs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                trackId INTEGER NOT NULL,
                releaseTrackMbid TEXT NOT NULL,
                releaseMbid TEXT,
                mediumPosition INTEGER,
                trackPosition INTEGER,
                hydratedAt INTEGER,
                FOREIGN KEY(trackId) REFERENCES library_tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_release_refs_trackId ON track_release_refs(trackId)")
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_track_release_refs_releaseTrackMbid ON track_release_refs(releaseTrackMbid)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_release_refs_releaseMbid ON track_release_refs(releaseMbid)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS album_metadata_state (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                albumId INTEGER NOT NULL,
                embeddedMbidStatus TEXT NOT NULL,
                remoteResolveStatus TEXT NOT NULL,
                embeddedFingerprint TEXT,
                generation INTEGER NOT NULL,
                attemptTimestamp INTEGER,
                fetchedTimestamp INTEGER,
                FOREIGN KEY(albumId) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_album_metadata_state_albumId ON album_metadata_state(albumId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_album_metadata_state_remoteResolveStatus ON album_metadata_state(remoteResolveStatus)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_album_metadata_state_attemptTimestamp ON album_metadata_state(attemptTimestamp)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_album_metadata_state_fetchedTimestamp ON album_metadata_state(fetchedTimestamp)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS track_metadata_state (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                trackId INTEGER NOT NULL,
                embeddedMbidStatus TEXT NOT NULL,
                remoteResolveStatus TEXT NOT NULL,
                embeddedFingerprint TEXT,
                generation INTEGER NOT NULL,
                attemptTimestamp INTEGER,
                fetchedTimestamp INTEGER,
                FOREIGN KEY(trackId) REFERENCES library_tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_track_metadata_state_trackId ON track_metadata_state(trackId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_metadata_state_remoteResolveStatus ON track_metadata_state(remoteResolveStatus)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_metadata_state_attemptTimestamp ON track_metadata_state(attemptTimestamp)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_metadata_state_fetchedTimestamp ON track_metadata_state(fetchedTimestamp)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS metadata_resolution (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                entityType TEXT NOT NULL,
                entityId INTEGER NOT NULL,
                provider TEXT NOT NULL,
                sourceId TEXT,
                confidence REAL NOT NULL,
                identityTrust INTEGER NOT NULL,
                yearTrust INTEGER NOT NULL,
                genreTrust INTEGER NOT NULL,
                artworkTrust INTEGER NOT NULL,
                resolvedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.execSql("CREATE INDEX IF NOT EXISTS index_metadata_resolution_entityType_entityId ON metadata_resolution(entityType, entityId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_metadata_resolution_provider_sourceId ON metadata_resolution(provider, sourceId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_metadata_resolution_resolvedAt ON metadata_resolution(resolvedAt)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS genres (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                sortKey TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_genres_name ON genres(name)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_genres_sortKey ON genres(sortKey)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS album_genre_cross_ref (
                albumId INTEGER NOT NULL,
                genreId INTEGER NOT NULL,
                rank INTEGER NOT NULL,
                sourceTrust INTEGER NOT NULL,
                PRIMARY KEY(albumId, genreId),
                FOREIGN KEY(albumId) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(genreId) REFERENCES genres(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE INDEX IF NOT EXISTS index_album_genre_cross_ref_albumId ON album_genre_cross_ref(albumId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_album_genre_cross_ref_genreId ON album_genre_cross_ref(genreId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_album_genre_cross_ref_genreId_albumId ON album_genre_cross_ref(genreId, albumId)")

        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS track_genre_cross_ref (
                trackId INTEGER NOT NULL,
                genreId INTEGER NOT NULL,
                rank INTEGER NOT NULL,
                sourceTrust INTEGER NOT NULL,
                PRIMARY KEY(trackId, genreId),
                FOREIGN KEY(trackId) REFERENCES library_tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(genreId) REFERENCES genres(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_genre_cross_ref_trackId ON track_genre_cross_ref(trackId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_genre_cross_ref_genreId ON track_genre_cross_ref(genreId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_track_genre_cross_ref_genreId_trackId ON track_genre_cross_ref(genreId, trackId)")

        backfillGenresFromAlbumSummary(connection)
    }
}

val Migration7To8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSql(
            """
            CREATE TABLE IF NOT EXISTS playback_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                trackId INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(trackId) REFERENCES library_tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSql("CREATE INDEX IF NOT EXISTS index_playback_history_trackId ON playback_history(trackId)")
        connection.execSql("CREATE INDEX IF NOT EXISTS index_playback_history_timestamp ON playback_history(timestamp)")
    }
}

private fun rebuildAlbumsAndTracksForVersion5(connection: SQLiteConnection) {
    connection.execSql("DROP TABLE IF EXISTS migration_playlist_track_cross_ref_backup")
    connection.execSql("DROP TABLE IF EXISTS migration_library_tracks_backup")
    connection.execSql("DROP TABLE IF EXISTS migration_albums_backup")

    connection.execSql(
        """
        CREATE TEMP TABLE migration_playlist_track_cross_ref_backup AS
        SELECT playlistId, trackId, position
        FROM playlist_track_cross_ref
        """.trimIndent()
    )
    connection.execSql(
        """
        CREATE TEMP TABLE migration_library_tracks_backup AS
        SELECT
            id,
            filePath,
            title,
            artistName,
            albumName,
            artistId,
            albumId,
            durationMs,
            fileSize,
            lastModified,
            mediaStoreId,
            contentFingerprint,
            fileHash,
            availability,
            albumArtUri,
            isPresent,
            lastSeenTimestamp,
            titleSortKey,
            artistSortKey
        FROM library_tracks
        """.trimIndent()
    )
    connection.execSql(
        """
        CREATE TEMP TABLE migration_albums_backup AS
        SELECT
            id,
            title,
            artistId,
            albumArtUri,
            coverUri,
            year,
            genre,
            metadataState,
            metadataUpdatedAt,
            lastMetadataAttemptAt,
            metadataVersion,
            coverSource,
            coverUpdatedAt,
            titleSortKey,
            artistSortKey,
            lastSeenTimestamp
        FROM albums
        """.trimIndent()
    )

    connection.execSql("DROP TABLE playlist_track_cross_ref")
    connection.execSql("DROP TABLE library_tracks")
    connection.execSql("DROP TABLE albums")

    connection.execSql(
        """
        CREATE TABLE IF NOT EXISTS albums (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL,
            artistId INTEGER NOT NULL,
            albumArtUri TEXT,
            coverUri TEXT,
            year INTEGER,
            genre TEXT,
            metadataState TEXT NOT NULL,
            metadataUpdatedAt INTEGER,
            lastMetadataAttemptAt INTEGER,
            metadataVersion INTEGER NOT NULL,
            coverSource TEXT NOT NULL,
            coverUpdatedAt INTEGER,
            titleSortKey TEXT NOT NULL,
            artistSortKey TEXT NOT NULL,
            lastSeenTimestamp INTEGER NOT NULL,
            FOREIGN KEY(artistId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent()
    )
    connection.execSql(
        """
        INSERT INTO albums (
            id,
            title,
            artistId,
            albumArtUri,
            coverUri,
            year,
            genre,
            metadataState,
            metadataUpdatedAt,
            lastMetadataAttemptAt,
            metadataVersion,
            coverSource,
            coverUpdatedAt,
            titleSortKey,
            artistSortKey,
            lastSeenTimestamp
        )
        SELECT
            id,
            title,
            artistId,
            albumArtUri,
            coverUri,
            year,
            genre,
            metadataState,
            metadataUpdatedAt,
            lastMetadataAttemptAt,
            metadataVersion,
            coverSource,
            coverUpdatedAt,
            COALESCE(NULLIF(titleSortKey, ''), lower(trim(title))),
            COALESCE(
                NULLIF(artistSortKey, ''),
                (
                    SELECT lower(trim(name))
                    FROM artists
                    WHERE artists.id = migration_albums_backup.artistId
                ),
                ''
            ),
            CASE
                WHEN lastSeenTimestamp = 0 THEN COALESCE(
                    (
                        SELECT MAX(lastSeenTimestamp)
                        FROM migration_library_tracks_backup
                        WHERE migration_library_tracks_backup.albumId = migration_albums_backup.id
                    ),
                    lastSeenTimestamp
                )
                ELSE lastSeenTimestamp
            END
        FROM migration_albums_backup
        """.trimIndent()
    )

    connection.execSql(
        """
        CREATE TABLE IF NOT EXISTS library_tracks (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            filePath TEXT NOT NULL,
            title TEXT NOT NULL,
            artistName TEXT NOT NULL,
            albumName TEXT NOT NULL,
            artistId INTEGER NOT NULL,
            albumId INTEGER NOT NULL,
            durationMs INTEGER NOT NULL,
            fileSize INTEGER NOT NULL,
            lastModified INTEGER NOT NULL,
            mediaStoreId INTEGER,
            contentFingerprint TEXT NOT NULL,
            fileHash TEXT,
            availability TEXT NOT NULL,
            albumArtUri TEXT,
            isPresent INTEGER NOT NULL,
            lastSeenTimestamp INTEGER NOT NULL,
            titleSortKey TEXT NOT NULL,
            artistSortKey TEXT NOT NULL,
            FOREIGN KEY(artistId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(albumId) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent()
    )
    connection.execSql(
        """
        INSERT INTO library_tracks (
            id,
            filePath,
            title,
            artistName,
            albumName,
            artistId,
            albumId,
            durationMs,
            fileSize,
            lastModified,
            mediaStoreId,
            contentFingerprint,
            fileHash,
            availability,
            albumArtUri,
            isPresent,
            lastSeenTimestamp,
            titleSortKey,
            artistSortKey
        )
        SELECT
            id,
            filePath,
            title,
            artistName,
            albumName,
            artistId,
            albumId,
            durationMs,
            fileSize,
            lastModified,
            mediaStoreId,
            contentFingerprint,
            fileHash,
            availability,
            albumArtUri,
            isPresent,
            lastSeenTimestamp,
            COALESCE(NULLIF(titleSortKey, ''), lower(trim(title))),
            COALESCE(NULLIF(artistSortKey, ''), lower(trim(artistName)))
        FROM migration_library_tracks_backup
        """.trimIndent()
    )

    connection.execSql(
        """
        CREATE TABLE IF NOT EXISTS playlist_track_cross_ref (
            playlistId INTEGER NOT NULL,
            trackId INTEGER NOT NULL,
            position INTEGER NOT NULL,
            PRIMARY KEY(playlistId, trackId),
            FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(trackId) REFERENCES library_tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    connection.execSql(
        """
        INSERT INTO playlist_track_cross_ref (playlistId, trackId, position)
        SELECT playlistId, trackId, position
        FROM migration_playlist_track_cross_ref_backup
        """.trimIndent()
    )

    connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_albums_artistId_title ON albums(artistId, title)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_year ON albums(year)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_genre ON albums(genre)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_metadataState ON albums(metadataState)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_lastMetadataAttemptAt ON albums(lastMetadataAttemptAt)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_titleSortKey ON albums(titleSortKey)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_artistSortKey ON albums(artistSortKey)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_titleSortKey_id ON albums(titleSortKey, id)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_artistSortKey_titleSortKey_id ON albums(artistSortKey, titleSortKey, id)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_year_titleSortKey_id ON albums(year, titleSortKey, id)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_genre_titleSortKey_id ON albums(genre, titleSortKey, id)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_albums_lastSeenTimestamp ON albums(lastSeenTimestamp)")

    connection.execSql("CREATE UNIQUE INDEX IF NOT EXISTS index_library_tracks_filePath ON library_tracks(filePath)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_mediaStoreId ON library_tracks(mediaStoreId)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_artistId ON library_tracks(artistId)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_albumId ON library_tracks(albumId)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_title ON library_tracks(title)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_titleSortKey_id ON library_tracks(titleSortKey, id)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_artistSortKey_titleSortKey_id ON library_tracks(artistSortKey, titleSortKey, id)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_albumName_titleSortKey_id ON library_tracks(albumName, titleSortKey, id)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_library_tracks_artistId_title_durationMs_fileSize ON library_tracks(artistId, title, durationMs, fileSize)")

    connection.execSql("CREATE INDEX IF NOT EXISTS index_playlist_track_cross_ref_trackId ON playlist_track_cross_ref(trackId)")
    connection.execSql("CREATE INDEX IF NOT EXISTS index_playlist_track_cross_ref_playlistId ON playlist_track_cross_ref(playlistId)")

    connection.execSql("DROP TABLE migration_playlist_track_cross_ref_backup")
    connection.execSql("DROP TABLE migration_library_tracks_backup")
    connection.execSql("DROP TABLE migration_albums_backup")
}

private fun backfillGenresFromAlbumSummary(connection: SQLiteConnection) {
    connection.execSql(
        """
        INSERT OR IGNORE INTO genres (name, sortKey, createdAt)
        SELECT DISTINCT trim(genre), lower(trim(genre)), 0
        FROM albums
        WHERE genre IS NOT NULL AND trim(genre) != ''
        """.trimIndent()
    )
    connection.execSql(
        """
        INSERT OR IGNORE INTO album_genre_cross_ref (albumId, genreId, rank, sourceTrust)
        SELECT albums.id, genres.id, 0, 40
        FROM albums
        INNER JOIN genres ON genres.sortKey = lower(trim(albums.genre))
        WHERE albums.genre IS NOT NULL AND trim(albums.genre) != ''
        """.trimIndent()
    )
}

private fun SQLiteConnection.execSql(sql: String) {
    prepare(sql).use { statement ->
        statement.step()
    }
}
