package org.milkdev.dreamplayer.library.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.diagnostics.LogStorage
import org.milkdev.dreamplayer.extensions.data.AlbumArtFileStore
import org.milkdev.dreamplayer.extensions.data.CoverArtRepository
import org.milkdev.dreamplayer.extensions.data.LastFmRepository
import org.milkdev.dreamplayer.extensions.data.MusicBrainzMetadataLookup
import org.milkdev.dreamplayer.extensions.data.MusicBrainzMetadataRepository
import org.milkdev.dreamplayer.extensions.network.LastFmService
import org.milkdev.dreamplayer.extensions.network.NetworkHosts
import org.milkdev.dreamplayer.extensions.network.RequestRateLimiter
import org.milkdev.dreamplayer.library.BaseDatabaseTest
import org.milkdev.dreamplayer.library.MetadataState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataSyncServiceTest : BaseDatabaseTest() {

    @Test
    fun missingLastFmKeyStopsWithoutChangingAlbumState() = runTestInScope {
        val albumId = insertAlbum()
        val client = HttpClient(MockEngine { error("Network must not be called without an API key") })
        val service = createService(
            client = client,
            apiKeyProvider = { null },
        )

        val result = service.syncLastFmBatch(limit = 1)
        val album = assertNotNull(musicDao.getAlbumById(albumId))

        assertEquals(MetadataSyncStopReason.NO_API_KEY, result.stopReason)
        assertEquals(0, result.processed)
        assertEquals(MetadataState.NOT_SYNCED.name, album.metadataState)
        assertEquals(0, album.metadataVersion)
        assertNull(album.lastMetadataAttemptAt)
        client.close()
    }

    @Test
    fun providerRateLimitsMarkAlbumFailedInsteadOfNoMatch() = runTestInScope {
        LogStorage.clear()
        val albumId = insertAlbum()
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    NetworkHosts.MUSIC_BRAINZ -> respond(
                        content = "",
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                    NetworkHosts.LAST_FM -> respond(
                        content = """{"error":29,"message":"Rate limit exceeded"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> error("Unexpected host ${request.url.host}")
                }
            }
        )
        val service = createService(
            client = client,
            apiKeyProvider = { "key" },
        )

        val result = service.syncLastFmBatch(limit = 1)
        val album = assertNotNull(musicDao.getAlbumById(albumId))

        assertEquals(MetadataSyncStopReason.RATE_LIMITED, result.stopReason)
        assertEquals(MetadataState.FAILED.name, album.metadataState)
        assertEquals(MetadataSyncService.CURRENT_METADATA_VERSION, album.metadataVersion)
        assertNotNull(album.lastMetadataAttemptAt)
        val debugTrace = LogStorage.logs.value.joinToString("\n")
        assertTrue(debugTrace.contains("result=failed reason=rate_limited"))
        assertFalse(debugTrace.contains(ALBUM))
        assertFalse(debugTrace.contains(ARTIST))
        LogStorage.clear()
        client.close()
    }

    @Test
    fun temporaryProviderFailuresMarkAlbumFailedAndStopCurrentDrain() = runTestInScope {
        val albumId = insertAlbum()
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    NetworkHosts.MUSIC_BRAINZ -> respond(
                        content = "",
                        status = HttpStatusCode.BadGateway,
                    )
                    NetworkHosts.LAST_FM -> respond(
                        content = """{"error":11,"message":"Service offline"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> error("Unexpected host ${request.url.host}")
                }
            }
        )
        val service = createService(
            client = client,
            apiKeyProvider = { "key" },
        )

        val result = service.syncLastFmBatch(limit = 1)
        val album = assertNotNull(musicDao.getAlbumById(albumId))

        assertEquals(MetadataSyncStopReason.SOURCE_UNAVAILABLE, result.stopReason)
        assertEquals(MetadataState.FAILED.name, album.metadataState)
        assertEquals(MetadataSyncService.CURRENT_METADATA_VERSION, album.metadataVersion)
        assertNotNull(album.lastMetadataAttemptAt)
        client.close()
    }

    @Test
    fun confirmedNoMatchesRemainTerminalNoMatch() = runTestInScope {
        LogStorage.clear()
        val albumId = insertAlbum()
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    NetworkHosts.MUSIC_BRAINZ -> respond(
                        content = "",
                        status = HttpStatusCode.NotFound,
                    )
                    NetworkHosts.LAST_FM -> respond(
                        content = """{"error":6,"message":"Album not found"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> error("Unexpected host ${request.url.host}")
                }
            }
        )
        val service = createService(
            client = client,
            apiKeyProvider = { "key" },
        )

        val result = service.syncLastFmBatch(limit = 1)
        val album = assertNotNull(musicDao.getAlbumById(albumId))

        assertEquals(MetadataSyncStopReason.BATCH_LIMIT, result.stopReason)
        assertEquals(1, result.processed)
        assertEquals(MetadataState.NO_MATCH.name, album.metadataState)
        assertEquals(MetadataSyncService.CURRENT_METADATA_VERSION, album.metadataVersion)
        val debugTrace = LogStorage.logs.value.joinToString("\n")
        assertTrue(debugTrace.contains("result=no_match reason=providers_confirmed_no_match"))
        assertFalse(debugTrace.contains("result=failed reason=rate_limited"))
        LogStorage.clear()
        client.close()
    }

    @Test
    fun confirmedMatchWithoutNewValuesIsDoneInsteadOfNoMatch() = runTestInScope {
        val albumId = insertAlbum(
            releaseGroupMbid = RELEASE_GROUP_MBID,
            identitySourceTrust = 80,
        )
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    NetworkHosts.MUSIC_BRAINZ -> respond(
                        content = """
                            {
                              "id": "$RELEASE_GROUP_MBID",
                              "title": "$ALBUM",
                              "artist-credit": [
                                {"name": "$ARTIST", "artist": {"name": "$ARTIST"}}
                              ]
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    NetworkHosts.LAST_FM -> respond(
                        content = """{"error":6,"message":"Album not found"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> error("Unexpected host ${request.url.host}")
                }
            }
        )
        val service = createService(
            client = client,
            apiKeyProvider = { "key" },
        )

        val result = service.syncLastFmBatch(limit = 1)
        val album = assertNotNull(musicDao.getAlbumById(albumId))

        assertEquals(MetadataSyncStopReason.BATCH_LIMIT, result.stopReason)
        assertEquals(1, result.processed)
        assertEquals(MetadataState.DONE.name, album.metadataState)
        assertEquals(RELEASE_GROUP_MBID, album.musicBrainzReleaseGroupMbid)
        client.close()
    }

    @Test
    fun musicBrainzServiceUnavailableIsRetryableAndRateLimited() = runTestInScope {
        val client = HttpClient(
            MockEngine {
                respond(content = "", status = HttpStatusCode.ServiceUnavailable)
            }
        )

        val result = MusicBrainzMetadataRepository(
            client = client,
            limiter = RequestRateLimiter(0),
        ).lookupAlbumMetadata(
            artist = "Daft Punk",
            album = "Discovery",
            releaseGroupMbid = null,
        )

        val failure = assertIs<MusicBrainzMetadataLookup.RetryableFailure>(result)
        assertTrue(failure.isRateLimited)
        client.close()
    }

    private suspend fun insertAlbum(
        releaseGroupMbid: String? = null,
        identitySourceTrust: Int = 0,
    ): Long {
        musicDao.insertArtists(listOf(ArtistEntity(name = ARTIST)))
        val artistId = musicDao.getArtistIdMap().single { it.name == ARTIST }.id
        musicDao.insertAlbums(
            listOf(
                AlbumEntity(
                    title = ALBUM,
                    artistId = artistId,
                    titleSortKey = ALBUM.lowercase(),
                    artistSortKey = ARTIST.lowercase(),
                    lastSeenTimestamp = 1L,
                    musicBrainzReleaseGroupMbid = releaseGroupMbid,
                    identitySourceTrust = identitySourceTrust,
                )
            )
        )
        return musicDao.getAlbumIdMap().single { it.title == ALBUM && it.artistId == artistId }.id
    }

    private fun kotlinx.coroutines.test.TestScope.createService(
        client: HttpClient,
        apiKeyProvider: suspend () -> String?,
    ): MetadataSyncService {
        return MetadataSyncService(
            musicDao = musicDao,
            lastFmRepository = LastFmRepository(LastFmService(client)),
            musicBrainzRepository = MusicBrainzMetadataRepository(
                client = client,
                limiter = RequestRateLimiter(0),
            ),
            coverArtRepository = CoverArtRepository(
                client = client,
                fileStore = object : AlbumArtFileStore {
                    override suspend fun saveRemoteAlbumArt(
                        albumId: Long,
                        sourceUrl: String,
                        bytes: ByteArray,
                        contentType: String?,
                    ): String = "file:///unused/$albumId"
                },
                musicBrainzLimiter = RequestRateLimiter(0),
                coverDownloadLimiter = RequestRateLimiter(0),
            ),
            scope = this,
            lastFmApiKeyProvider = apiKeyProvider,
        )
    }

    private companion object {
        const val ARTIST = "Daft Punk"
        const val ALBUM = "Discovery"
        const val RELEASE_GROUP_MBID = "48140466-cff6-3222-bd55-63c27e43190d"
    }
}
