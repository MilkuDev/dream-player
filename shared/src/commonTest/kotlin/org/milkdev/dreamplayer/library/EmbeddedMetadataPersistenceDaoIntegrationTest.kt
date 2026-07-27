package org.milkdev.dreamplayer.library

import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.database.entities.EmbeddedMbidStatus
import org.milkdev.dreamplayer.database.entities.GenreEntity
import org.milkdev.dreamplayer.database.entities.MetadataEntityType
import org.milkdev.dreamplayer.database.entities.MetadataProvider
import org.milkdev.dreamplayer.database.entities.TrackEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedMetadataPersistenceDaoIntegrationTest : BaseDatabaseTest() {

    @Test
    fun staleExtractionCannotWriteAndCurrentExtractionPersistsAllEvidence() = runTestInScope {
        val ids = insertLibraryGraph()

        assertFalse(
            applyEmbeddedMetadata(
                trackId = ids.trackId,
                expectedFingerprint = "stale-fingerprint",
                expectedGeneration = GENERATION,
            )
        )
        assertFalse(
            applyEmbeddedMetadata(
                trackId = ids.trackId,
                expectedFingerprint = CONTENT_FINGERPRINT,
                expectedGeneration = GENERATION - 1,
            )
        )
        assertNull(musicDao.getTracksByIds(listOf(ids.trackId)).single().musicBrainzRecordingMbid)
        assertNull(musicDao.getAlbumById(ids.albumId)?.musicBrainzReleaseGroupMbid)
        assertNull(musicDao.getArtistById(ids.artistId)?.musicBrainzArtistMbid)
        assertNull(musicDao.getTrackMetadataState(ids.trackId))
        assertTrue(musicDao.getAlbumReleases(ids.albumId).isEmpty())

        assertTrue(
            applyEmbeddedMetadata(
                trackId = ids.trackId,
                expectedFingerprint = CONTENT_FINGERPRINT,
                expectedGeneration = GENERATION,
            )
        )

        val track = musicDao.getTracksByIds(listOf(ids.trackId)).single()
        val album = musicDao.getAlbumById(ids.albumId)
        val artist = musicDao.getArtistById(ids.artistId)
        val state = musicDao.getTrackMetadataState(ids.trackId)
        assertEquals(RECORDING_MBID, track.musicBrainzRecordingMbid)
        assertEquals(EMBEDDED_TRUST, track.identitySourceTrust)
        assertEquals(RELEASE_GROUP_MBID, album?.musicBrainzReleaseGroupMbid)
        assertEquals(1998, album?.year)
        assertEquals(EMBEDDED_TRUST, album?.identitySourceTrust)
        assertEquals(EMBEDDED_TRUST, album?.yearSourceTrust)
        assertEquals(ARTIST_MBID, artist?.musicBrainzArtistMbid)
        assertEquals(RELEASE_MBID, musicDao.getAlbumReleases(ids.albumId).single().releaseMbid)
        assertEquals(EmbeddedMbidStatus.FOUND.name, state?.embeddedMbidStatus)
        assertEquals("tag-fingerprint", state?.embeddedFingerprint)
        assertEquals(GENERATION, state?.generation)
        assertEquals(listOf("Rock"), musicDao.getGenreIdMap(listOf("Rock")).map { it.name })

        assertEmbeddedResolution(ids.trackId, MetadataEntityType.TRACK, RECORDING_MBID)
        assertEmbeddedResolution(ids.albumId, MetadataEntityType.ALBUM, RELEASE_GROUP_MBID)
        assertEmbeddedResolution(ids.albumId, MetadataEntityType.ALBUM_RELEASE, RELEASE_MBID)
        assertEmbeddedResolution(ids.artistId, MetadataEntityType.ARTIST, ARTIST_MBID)
        assertEmbeddedResolution(ids.albumId, MetadataEntityType.ALBUM_ARTIST, ALBUM_ARTIST_MBID)

        assertTrue(
            applyEmbeddedMetadata(
                trackId = ids.trackId,
                expectedFingerprint = CONTENT_FINGERPRINT,
                expectedGeneration = GENERATION,
            )
        )
        assertEquals(
            1,
            musicDao.getMetadataResolutions(
                MetadataEntityType.ALBUM_ARTIST.name,
                ids.albumId,
            ).size,
            "Repeated extraction must not duplicate persisted evidence",
        )
        assertEquals(1, musicDao.getAlbumReleases(ids.albumId).size)
    }

    @Test
    fun embeddedMetadataDoesNotOverwriteHigherTrustValues() = runTestInScope {
        val ids = insertLibraryGraph(
            artistMbid = HIGH_TRUST_ARTIST_MBID,
            recordingMbid = HIGH_TRUST_RECORDING_MBID,
            releaseGroupMbid = HIGH_TRUST_RELEASE_GROUP_MBID,
            year = 2005,
            trust = HIGH_TRUST,
        )

        assertTrue(
            applyEmbeddedMetadata(
                trackId = ids.trackId,
                expectedFingerprint = CONTENT_FINGERPRINT,
                expectedGeneration = GENERATION,
            )
        )

        val track = musicDao.getTracksByIds(listOf(ids.trackId)).single()
        val album = musicDao.getAlbumById(ids.albumId)
        val artist = musicDao.getArtistById(ids.artistId)
        assertEquals(HIGH_TRUST_RECORDING_MBID, track.musicBrainzRecordingMbid)
        assertEquals(HIGH_TRUST, track.identitySourceTrust)
        assertEquals(HIGH_TRUST_RELEASE_GROUP_MBID, album?.musicBrainzReleaseGroupMbid)
        assertEquals(2005, album?.year)
        assertEquals(HIGH_TRUST, album?.identitySourceTrust)
        assertEquals(HIGH_TRUST, album?.yearSourceTrust)
        assertEquals(HIGH_TRUST_ARTIST_MBID, artist?.musicBrainzArtistMbid)
    }

    private suspend fun applyEmbeddedMetadata(
        trackId: Long,
        expectedFingerprint: String,
        expectedGeneration: Long,
    ): Boolean {
        return musicDao.applyEmbeddedMetadata(
            trackId = trackId,
            expectedFilePath = TRACK_PATH,
            expectedContentFingerprint = expectedFingerprint,
            expectedGeneration = expectedGeneration,
            recordingMbid = RECORDING_MBID,
            releaseMbid = RELEASE_MBID,
            releaseGroupMbid = RELEASE_GROUP_MBID,
            artistMbids = listOf(ARTIST_MBID),
            albumArtistMbids = listOf(ALBUM_ARTIST_MBID),
            year = 1998,
            genres = listOf(
                GenreEntity(
                    name = "Rock",
                    sortKey = "rock",
                    createdAt = NOW,
                )
            ),
            embeddedMbidStatus = EmbeddedMbidStatus.FOUND.name,
            embeddedFingerprint = "tag-fingerprint",
            timestamp = NOW,
            identityTrust = EMBEDDED_TRUST,
            yearTrust = EMBEDDED_TRUST,
            genreTrust = 80,
        )
    }

    private suspend fun insertLibraryGraph(
        artistMbid: String? = null,
        recordingMbid: String? = null,
        releaseGroupMbid: String? = null,
        year: Int? = null,
        trust: Int = 0,
    ): LibraryIds {
        musicDao.insertArtists(
            listOf(
                ArtistEntity(
                    name = ARTIST,
                    musicBrainzArtistMbid = artistMbid,
                )
            )
        )
        val artistId = musicDao.getArtistIdMap().single().id
        musicDao.insertAlbums(
            listOf(
                AlbumEntity(
                    title = ALBUM,
                    artistId = artistId,
                    year = year,
                    musicBrainzReleaseGroupMbid = releaseGroupMbid,
                    yearSourceTrust = trust,
                    identitySourceTrust = trust,
                    titleSortKey = "embedded album",
                    artistSortKey = "embedded artist",
                    lastSeenTimestamp = GENERATION,
                )
            )
        )
        val albumId = musicDao.getAlbumIdMap().single().id
        musicDao.upsertTracks(
            listOf(
                TrackEntity(
                    filePath = TRACK_PATH,
                    title = "Embedded Track",
                    artistName = ARTIST,
                    albumName = ALBUM,
                    artistId = artistId,
                    albumId = albumId,
                    durationMs = 180_000L,
                    fileSize = 4_000_000L,
                    lastModified = NOW,
                    contentFingerprint = CONTENT_FINGERPRINT,
                    lastSeenTimestamp = GENERATION,
                    musicBrainzRecordingMbid = recordingMbid,
                    identitySourceTrust = trust,
                )
            )
        )
        val trackId = musicDao.getTracksByPaths(listOf(TRACK_PATH)).single().id
        return LibraryIds(artistId, albumId, trackId)
    }

    private suspend fun assertEmbeddedResolution(
        entityId: Long,
        entityType: MetadataEntityType,
        sourceId: String,
    ) {
        val resolution = musicDao.getMetadataResolutions(entityType.name, entityId).single()
        assertEquals(MetadataProvider.EMBEDDED.name, resolution.provider)
        assertEquals(sourceId, resolution.sourceId)
        assertEquals(EMBEDDED_TRUST, resolution.identityTrust)
    }

    private data class LibraryIds(
        val artistId: Long,
        val albumId: Long,
        val trackId: Long,
    )

    private companion object {
        const val ARTIST = "Embedded Artist"
        const val ALBUM = "Embedded Album"
        const val TRACK_PATH = "/music/embedded.flac"
        const val CONTENT_FINGERPRINT = "content-fingerprint"
        const val GENERATION = 25_000L
        const val NOW = 26_000L
        const val EMBEDDED_TRUST = 100
        const val HIGH_TRUST = 120

        const val RECORDING_MBID = "00000000-0000-0000-0000-000000000001"
        const val RELEASE_MBID = "00000000-0000-0000-0000-000000000002"
        const val RELEASE_GROUP_MBID = "00000000-0000-0000-0000-000000000003"
        const val ARTIST_MBID = "00000000-0000-0000-0000-000000000004"
        const val ALBUM_ARTIST_MBID = "00000000-0000-0000-0000-000000000005"
        const val HIGH_TRUST_RECORDING_MBID = "00000000-0000-0000-0000-000000000006"
        const val HIGH_TRUST_RELEASE_GROUP_MBID = "00000000-0000-0000-0000-000000000007"
        const val HIGH_TRUST_ARTIST_MBID = "00000000-0000-0000-0000-000000000008"
    }
}
