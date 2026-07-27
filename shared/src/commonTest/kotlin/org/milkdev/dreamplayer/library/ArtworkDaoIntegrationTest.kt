package org.milkdev.dreamplayer.library

import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.database.entities.TrackEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkDaoIntegrationTest : BaseDatabaseTest() {

    @Test
    fun changedPlatformIdentityClearsUnverifiedLocalUri() = runTestInScope {
        val ids = insertLibraryRows(
            albumArtUri = "content://media/external/audio/albumart/42",
            localArtworkState = LocalArtworkState.PRESENT,
        )

        musicDao.updateAlbumScanFields(
            albumId = ids.albumId,
            albumArtUri = null,
            coverSource = CoverSource.NONE.name,
            localArtworkKey = "content://media/external/audio/albums/42",
            localArtworkFingerprint = "new-fingerprint",
            localArtworkState = LocalArtworkState.UNKNOWN.name,
            timestamp = NOW,
            titleSortKey = "album",
            artistSortKey = "artist",
        )

        val album = musicDao.getAlbumById(ids.albumId)!!
        assertNull(album.albumArtUri)
        assertEquals(LocalArtworkState.UNKNOWN.name, album.localArtworkState)
        assertNull(album.localArtworkCheckedAt)
    }

    @Test
    fun missingVerificationEnqueuesRemoteLookupAndPresentVerificationRemovesIt() = runTestInScope {
        val ids = insertLibraryRows(localArtworkState = LocalArtworkState.UNKNOWN)

        musicDao.updateLocalArtworkVerification(
            albumId = ids.albumId,
            state = LocalArtworkState.MISSING.name,
            artworkUri = null,
            coverSource = CoverSource.LOCAL_CACHE.name,
            timestamp = NOW,
        )

        assertEquals(1, pendingCoverCount())

        musicDao.updateLocalArtworkVerification(
            albumId = ids.albumId,
            state = LocalArtworkState.PRESENT.name,
            artworkUri = "content://media/external/audio/albums/42",
            coverSource = CoverSource.LOCAL_CACHE.name,
            timestamp = NOW + 1,
        )

        assertEquals(0, pendingCoverCount())
        val album = musicDao.getAlbumById(ids.albumId)!!
        assertEquals(LocalArtworkState.PRESENT.name, album.localArtworkState)
        assertEquals("content://media/external/audio/albums/42", album.albumArtUri)
    }

    @Test
    fun trackProjectionFallsBackToRemoteAlbumCover() = runTestInScope {
        val ids = insertLibraryRows(
            localArtworkState = LocalArtworkState.MISSING,
            coverUri = "file:///covers/album.jpg",
            coverUpdatedAt = NOW + 5,
        )

        val row = musicDao.getTracksWithArtworkByIds(listOf(ids.trackId)).single()

        assertEquals("file:///covers/album.jpg", row.artworkUri)
        assertEquals(NOW + 5, row.artworkRevision)
    }

    @Test
    fun metadataUpdateDoesNotOverwriteConcurrentLocalVerification() = runTestInScope {
        val ids = insertLibraryRows(localArtworkState = LocalArtworkState.UNKNOWN)
        val staleAlbum = musicDao.getAlbumById(ids.albumId)!!

        musicDao.updateLocalArtworkVerification(
            albumId = ids.albumId,
            state = LocalArtworkState.PRESENT.name,
            artworkUri = "content://media/external/audio/albums/42",
            coverSource = CoverSource.LOCAL_CACHE.name,
            timestamp = NOW,
        )
        musicDao.updateAlbumPreservingLocalArtwork(
            staleAlbum.copy(
                coverUri = "file:///covers/remote.jpg",
                coverSource = CoverSource.REMOTE.name,
                coverUpdatedAt = NOW + 1,
            )
        )

        val album = musicDao.getAlbumById(ids.albumId)!!
        assertEquals(LocalArtworkState.PRESENT.name, album.localArtworkState)
        assertEquals("content://media/external/audio/albums/42", album.albumArtUri)
        assertEquals("file:///covers/remote.jpg", album.coverUri)
        assertEquals(CoverSource.LOCAL_CACHE.name, album.coverSource)
    }

    private suspend fun pendingCoverCount(): Int {
        return musicDao.getPendingCoverLookupCount(
            retryTimestamp = NOW - 1,
            pendingTimeoutTimestamp = NOW - 1,
            currentVersion = CURRENT_COVER_VERSION,
        )
    }

    private suspend fun insertLibraryRows(
        albumArtUri: String? = null,
        localArtworkState: LocalArtworkState,
        coverUri: String? = null,
        coverUpdatedAt: Long? = null,
    ): InsertedIds {
        musicDao.insertArtists(listOf(ArtistEntity(name = "Artist")))
        val artistId = musicDao.getArtistIdMap().single().id
        musicDao.insertAlbums(
            listOf(
                AlbumEntity(
                    title = "Album",
                    artistId = artistId,
                    albumArtUri = albumArtUri,
                    localArtworkState = localArtworkState.name,
                    coverUri = coverUri,
                    coverUpdatedAt = coverUpdatedAt,
                    coverLookupState = CoverLookupState.NOT_SYNCED.name,
                    coverLookupVersion = 0,
                    titleSortKey = "album",
                    artistSortKey = "artist",
                    lastSeenTimestamp = NOW,
                )
            )
        )
        val albumId = musicDao.getAlbumIdMap().single().id
        musicDao.upsertTracks(
            listOf(
                TrackEntity(
                    filePath = "/music/track.mp3",
                    title = "Track",
                    artistName = "Artist",
                    albumName = "Album",
                    artistId = artistId,
                    albumId = albumId,
                    durationMs = 180_000,
                    fileSize = 4_000_000,
                    lastModified = NOW,
                    lastSeenTimestamp = NOW,
                )
            )
        )
        val trackId = musicDao.getTracksByPaths(listOf("/music/track.mp3")).single().id
        return InsertedIds(albumId = albumId, trackId = trackId)
    }

    private data class InsertedIds(
        val albumId: Long,
        val trackId: Long,
    )

    private companion object {
        const val NOW = 10_000L
        const val CURRENT_COVER_VERSION = 1
    }
}
