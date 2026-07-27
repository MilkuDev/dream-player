package org.milkdev.dreamplayer.library

import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.database.entities.TrackEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataQueueDaoIntegrationTest : BaseDatabaseTest() {

    @Test
    fun metadataVersionBumpRequeuesTerminalAlbumsAndUpdatesPendingCounts() = runTestInScope {
        val artistId = insertArtist()
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Old done",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION - 1,
            albumArtUri = "embedded://old-done",
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Old no match",
            metadataState = MetadataState.NO_MATCH.name,
            metadataVersion = CURRENT_METADATA_VERSION - 1,
            albumArtUri = "embedded://old-no-match",
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Current done",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            albumArtUri = "embedded://current-done",
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Current no match",
            metadataState = MetadataState.NO_MATCH.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            albumArtUri = "embedded://current-no-match",
        )

        val queuedTitles = musicDao.getAlbumsToSync(
            limit = 10,
            retryTimestamp = NOW - 1,
            pendingTimeoutTimestamp = NOW - 1,
            currentVersion = CURRENT_METADATA_VERSION,
        ).map { it.title }.toSet()

        assertEquals(setOf("Old done", "Old no match"), queuedTitles)
        assertEquals(2, musicDao.getPendingMetadataCount(CURRENT_METADATA_VERSION))
        assertEquals(
            2,
            musicDao.getLibrarySummary(
                currentMetadataVersion = CURRENT_METADATA_VERSION,
                currentCoverLookupVersion = CURRENT_COVER_VERSION,
            ).pendingMetadataCount,
        )
    }

    @Test
    fun metadataVersionBumpBypassesFailureCooldownOnlyForOutdatedVersion() = runTestInScope {
        val artistId = insertArtist()
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Old failed",
            metadataState = MetadataState.FAILED.name,
            metadataVersion = CURRENT_METADATA_VERSION - 1,
            lastMetadataAttemptAt = NOW,
            albumArtUri = "embedded://old-failed",
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Current failed",
            metadataState = MetadataState.FAILED.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            lastMetadataAttemptAt = NOW,
            albumArtUri = "embedded://current-failed",
        )

        val queuedTitles = musicDao.getAlbumsToSync(
            limit = 10,
            retryTimestamp = NOW - 1,
            pendingTimeoutTimestamp = NOW - 1,
            currentVersion = CURRENT_METADATA_VERSION,
        ).map { it.title }

        assertEquals(listOf("Old failed"), queuedTitles)
    }

    @Test
    fun coverVersionBumpRequeuesTerminalAlbumsAndUpdatesPendingCount() = runTestInScope {
        val artistId = insertArtist()
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Old cover done",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            coverLookupState = CoverLookupState.DONE.name,
            coverLookupVersion = CURRENT_COVER_VERSION - 1,
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Old cover no match",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            coverLookupState = CoverLookupState.NO_MATCH.name,
            coverLookupVersion = CURRENT_COVER_VERSION - 1,
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Current cover done",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            coverLookupState = CoverLookupState.DONE.name,
            coverLookupVersion = CURRENT_COVER_VERSION,
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Current cover no match",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            coverLookupState = CoverLookupState.NO_MATCH.name,
            coverLookupVersion = CURRENT_COVER_VERSION,
        )

        val queuedTitles = musicDao.getAlbumsForCoverLookup(
            limit = 10,
            retryTimestamp = NOW - 1,
            pendingTimeoutTimestamp = NOW - 1,
            currentVersion = CURRENT_COVER_VERSION,
        ).map { it.album.title }.toSet()

        assertEquals(setOf("Old cover done", "Old cover no match"), queuedTitles)
        assertEquals(
            2,
            musicDao.getPendingCoverLookupCount(
                retryTimestamp = NOW - 1,
                pendingTimeoutTimestamp = NOW - 1,
                currentVersion = CURRENT_COVER_VERSION,
            ),
        )
        assertEquals(
            2,
            musicDao.getLibrarySummary(
                currentMetadataVersion = CURRENT_METADATA_VERSION,
                currentCoverLookupVersion = CURRENT_COVER_VERSION,
            ).pendingMetadataCount,
        )
    }

    @Test
    fun coverLookupWaitsUntilLocalArtworkIsConfirmedMissing() = runTestInScope {
        val artistId = insertArtist()
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Unknown artwork",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            localArtworkState = LocalArtworkState.UNKNOWN,
            coverLookupState = CoverLookupState.NOT_SYNCED.name,
            coverLookupVersion = 0,
        )
        insertAlbumWithTrack(
            artistId = artistId,
            title = "Missing artwork",
            metadataState = MetadataState.DONE.name,
            metadataVersion = CURRENT_METADATA_VERSION,
            localArtworkState = LocalArtworkState.MISSING,
            coverLookupState = CoverLookupState.NOT_SYNCED.name,
            coverLookupVersion = 0,
        )

        val queuedTitles = musicDao.getAlbumsForCoverLookup(
            limit = 10,
            retryTimestamp = NOW - 1,
            pendingTimeoutTimestamp = NOW - 1,
            currentVersion = CURRENT_COVER_VERSION,
        ).map { it.album.title }

        assertEquals(listOf("Missing artwork"), queuedTitles)
        assertEquals(1, musicDao.getPendingLocalArtworkVerificationCount())
        assertEquals(
            1,
            musicDao.getPendingCoverLookupCount(
                retryTimestamp = NOW - 1,
                pendingTimeoutTimestamp = NOW - 1,
                currentVersion = CURRENT_COVER_VERSION,
            ),
        )
        assertEquals(
            2,
            musicDao.getLibrarySummary(
                currentMetadataVersion = CURRENT_METADATA_VERSION,
                currentCoverLookupVersion = CURRENT_COVER_VERSION,
            ).pendingMetadataCount,
        )
    }

    private suspend fun insertArtist(): Long {
        musicDao.insertArtists(listOf(ArtistEntity(name = ARTIST)))
        return musicDao.getArtistIdMap().single { it.name == ARTIST }.id
    }

    private suspend fun insertAlbumWithTrack(
        artistId: Long,
        title: String,
        metadataState: String,
        metadataVersion: Int,
        lastMetadataAttemptAt: Long? = null,
        albumArtUri: String? = null,
        localArtworkState: LocalArtworkState = if (albumArtUri.isNullOrBlank()) {
            LocalArtworkState.MISSING
        } else {
            LocalArtworkState.PRESENT
        },
        coverLookupState: String = CoverLookupState.DONE.name,
        coverLookupVersion: Int = CURRENT_COVER_VERSION,
    ) {
        musicDao.insertAlbums(
            listOf(
                AlbumEntity(
                    title = title,
                    artistId = artistId,
                    albumArtUri = albumArtUri,
                    localArtworkState = localArtworkState.name,
                    metadataState = metadataState,
                    metadataVersion = metadataVersion,
                    lastMetadataAttemptAt = lastMetadataAttemptAt,
                    coverLookupState = coverLookupState,
                    coverLookupVersion = coverLookupVersion,
                    titleSortKey = title.lowercase(),
                    artistSortKey = ARTIST.lowercase(),
                    lastSeenTimestamp = NOW,
                )
            )
        )
        val albumId = musicDao.getAlbumIdMap().single { it.title == title }.id
        musicDao.upsertTracks(
            listOf(
                TrackEntity(
                    filePath = "/music/${title.lowercase().replace(' ', '-')}.mp3",
                    title = "$title track",
                    artistName = ARTIST,
                    albumName = title,
                    artistId = artistId,
                    albumId = albumId,
                    durationMs = 180_000L,
                    fileSize = 4_000_000L,
                    lastModified = NOW,
                    lastSeenTimestamp = NOW,
                )
            )
        )
    }

    private companion object {
        const val ARTIST = "Queue Artist"
        const val NOW = 10_000L
        const val CURRENT_METADATA_VERSION = 2
        const val CURRENT_COVER_VERSION = 1
    }
}
