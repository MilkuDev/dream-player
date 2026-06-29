package org.milkdev.dreamplayer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.milkdev.dreamplayer.diagnostics.LogStorage
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders
import org.milkdev.dreamplayer.extensions.network.AiPromptService
import org.milkdev.dreamplayer.extensions.network.buildAiPromptRequestBody
import org.milkdev.dreamplayer.extensions.data.AlbumArtFileStore
import org.milkdev.dreamplayer.extensions.data.CoverArtLookup
import org.milkdev.dreamplayer.extensions.data.CoverArtRepository
import org.milkdev.dreamplayer.extensions.data.LastFmAlbumLookup
import org.milkdev.dreamplayer.extensions.data.LastFmImage
import org.milkdev.dreamplayer.extensions.data.LastFmRepository
import org.milkdev.dreamplayer.extensions.data.selectBestLastFmImageUrl
import org.milkdev.dreamplayer.extensions.network.LastFmErrorType
import org.milkdev.dreamplayer.extensions.network.LastFmNetworkDiagnosticsService
import org.milkdev.dreamplayer.extensions.network.LastFmResult
import org.milkdev.dreamplayer.extensions.network.LastFmService
import org.milkdev.dreamplayer.extensions.network.NetworkHosts
import org.milkdev.dreamplayer.extensions.network.RequestRateLimiter
import org.milkdev.dreamplayer.extensions.network.NetworkSecurityException
import org.milkdev.dreamplayer.extensions.network.SecureNetworkEndpoint
import org.milkdev.dreamplayer.extensions.network.SecureNetworkPolicy

class NetworkDiagnosticsTest {
    @Test
    fun securePolicyBlocksHttpAndUnknownHosts() {
        assertFailsWith<NetworkSecurityException> {
            SecureNetworkPolicy.requireAllowed(
                SecureNetworkEndpoint(
                    serviceName = "OpenAI",
                    url = "http://${NetworkHosts.OpenAi}/v1/models",
                    allowedHosts = setOf(NetworkHosts.OpenAi),
                )
            )
        }

        assertFailsWith<NetworkSecurityException> {
            SecureNetworkPolicy.requireAllowed(
                SecureNetworkEndpoint(
                    serviceName = "OpenAI",
                    url = "https://example.com/v1/models",
                    allowedHosts = setOf(NetworkHosts.OpenAi),
                )
            )
        }
    }

    @Test
    fun lastFmDiagnosticsUsesAllowedHttpsEndpointAndDoesNotExposeKeyInStatus() = runBlocking {
        LogStorage.clearLastFmNetwork()
        val apiKey = "LASTFM_SECRET"
        var capturedUrl = ""
        val client = HttpClient(
            MockEngine { request ->
                capturedUrl = request.url.toString()
                respond(
                    content = """{"artists":{"artist":[]}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )

        val status = LastFmNetworkDiagnosticsService(
            LastFmService(client)
        ).testApiKey(apiKey)

        assertTrue(capturedUrl.startsWith("https://${NetworkHosts.LastFm}/2.0/"))
        assertTrue(capturedUrl.contains("method=chart.gettopartists"))
        assertTrue(capturedUrl.contains("limit=1"))
        assertTrue(capturedUrl.contains("api_key=$apiKey"))
        assertFalse(status.toDisplayText().contains(apiKey))

        val trace = LogStorage.lastFmNetworkLogs.value.joinToString("\n")
        assertTrue(trace.contains("Last.fm -> GET"))
        assertTrue(trace.contains("Last.fm <- HTTP 200"))
        assertFalse(trace.contains(apiKey))
        LogStorage.clearLastFmNetwork()
    }

    @Test
    fun lastFmErrorCodesMapToTypedFailures() = runBlocking {
        val cases = listOf(
            10 to LastFmErrorType.InvalidApiKey,
            26 to LastFmErrorType.SuspendedApiKey,
            29 to LastFmErrorType.RateLimited,
        )

        cases.forEach { (code, expectedType) ->
            val client = HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":$code,"message":"from lastfm"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            )

            val result = LastFmService(client).testApiKey("key")

            val failure = assertIs<LastFmResult.Failure>(result)
            assertEquals(expectedType, failure.type)
            assertEquals(code, failure.code)
        }
    }

    @Test
    fun lastFmAlbumCoverPrefersMegaThenExtraLargeThenLastNonBlank() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "album": {
                            "name": "Believe",
                            "artist": "Cher",
                            "image": [
                              {"#text": "small.jpg", "size": "small"},
                              {"#text": "extralarge.jpg", "size": "extralarge"},
                              {"#text": "mega.jpg", "size": "mega"}
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )
        val repository = LastFmRepository(LastFmService(client))

        assertEquals(
            "mega.jpg",
            repository.getAlbumCoverUrl(
                artist = "Cher",
                album = "Believe",
                apiKey = "key",
            )
        )

        assertEquals(
            "extralarge.jpg",
            selectBestLastFmImageUrl(
                listOf(
                    LastFmImage(url = "small.jpg", size = "small"),
                    LastFmImage(url = "extralarge.jpg", size = "extralarge"),
                )
            )
        )

        assertEquals(
            "last.jpg",
            selectBestLastFmImageUrl(
                listOf(
                    LastFmImage(url = "", size = "small"),
                    LastFmImage(url = "last.jpg", size = "large"),
                )
            )
        )
    }

    @Test
    fun lastFmAlbumMetadataParsesYearGenreAndCover() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "album": {
                            "name": "Discovery",
                            "artist": "Daft Punk",
                            "releasedate": "12 Mar 2001",
                            "image": [
                              {"#text": "small.jpg", "size": "small"},
                              {"#text": "cover.jpg", "size": "extralarge"}
                            ],
                            "toptags": {
                              "tag": [
                                {"name": " Electronic  House ", "url": "https://last.fm/tag/electronic"}
                              ]
                            }
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )

        val result = LastFmRepository(LastFmService(client)).lookupAlbumMetadata(
            artist = "Daft Punk",
            album = "Discovery",
            apiKey = "key",
        )

        val found = assertIs<LastFmAlbumLookup.Found>(result)
        assertEquals(2001, found.metadata.year)
        assertEquals("electronic house", found.metadata.genre)
        assertEquals("cover.jpg", found.metadata.coverUrl)
    }

    @Test
    fun lastFmAlbumLookupSeparatesNoMatchAndRetryableFailures() = runBlocking {
        val notFoundClient = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":6,"message":"Album not found"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )
        val rateLimitedClient = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":29,"message":"Rate limit exceeded"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )

        assertIs<LastFmAlbumLookup.NoMatch>(
            LastFmRepository(LastFmService(notFoundClient)).lookupAlbumMetadata(
                artist = "Missing",
                album = "Missing",
                apiKey = "key",
            )
        )
        assertIs<LastFmAlbumLookup.RetryableFailure>(
            LastFmRepository(LastFmService(rateLimitedClient)).lookupAlbumMetadata(
                artist = "Daft Punk",
                album = "Discovery",
                apiKey = "key",
            )
        )
        Unit
    }

    @Test
    fun coverArtRepositoryUsesMusicBrainzUserAgentAndCachesCaaRedirect() = runBlocking {
        LogStorage.clearOtherNetwork()
        val saved = mutableListOf<SavedAlbumArt>()
        val requestedHosts = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requestedHosts += request.url.host
                when (request.url.host) {
                    NetworkHosts.MusicBrainz -> {
                        assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("DreamPlayer/"))
                        assertTrue(request.url.parameters["query"].orEmpty().contains("releasegroup"))
                        respond(
                            content = """
                                {
                                  "release-groups": [
                                    {
                                      "id": "48140466-cff6-3222-bd55-63c27e43190d",
                                      "score": 100,
                                      "title": "Discovery",
                                      "artist-credit": [
                                        {"name": "Daft Punk", "artist": {"name": "Daft Punk"}}
                                      ]
                                    }
                                  ]
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    NetworkHosts.CoverArtArchive -> respond(
                        content = "",
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = headersOf(
                            HttpHeaders.Location,
                            "http://${NetworkHosts.InternetArchive}/download/cover.jpg",
                        ),
                    )
                    NetworkHosts.InternetArchive -> respond(
                        content = "fake-image",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                    )
                    else -> error("Unexpected host ${request.url.host}")
                }
            }
        )

        val result = coverArtRepository(client, saved).lookupAlbumCover(
            albumId = 42,
            artist = "Daft Punk",
            album = "Discovery",
        )

        val found = assertIs<CoverArtLookup.Found>(result)
        assertEquals("file:///cached/42.jpg", found.localUri)
        assertEquals("48140466-cff6-3222-bd55-63c27e43190d", found.musicBrainzReleaseGroupMbid)
        assertContentEquals(
            listOf(NetworkHosts.MusicBrainz, NetworkHosts.CoverArtArchive, NetworkHosts.InternetArchive),
            requestedHosts,
        )
        assertEquals("fake-image", saved.single().bytes.decodeToString())

        val trace = LogStorage.otherNetworkLogs.value.joinToString("\n")
        assertTrue(trace.contains("MusicBrainz -> GET"))
        assertTrue(trace.contains("Cover Art Archive -> GET"))
        assertTrue(trace.contains("bytes=10"))
        LogStorage.clearOtherNetwork()
    }

    @Test
    fun coverArtRepositoryTreatsCaaNotFoundAsNoMatch() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    NetworkHosts.MusicBrainz -> respond(
                        content = """
                            {
                              "release-groups": [
                                {
                                  "id": "48140466-cff6-3222-bd55-63c27e43190d",
                                  "score": 100,
                                  "title": "Discovery",
                                  "artist-credit": [
                                    {"name": "Daft Punk", "artist": {"name": "Daft Punk"}}
                                  ]
                                }
                              ]
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    NetworkHosts.CoverArtArchive -> respond(
                        content = "",
                        status = HttpStatusCode.NotFound,
                    )
                    else -> error("Unexpected host ${request.url.host}")
                }
            }
        )

        val result = coverArtRepository(client, mutableListOf()).lookupAlbumCover(
            albumId = 42,
            artist = "Daft Punk",
            album = "Discovery",
        )

        assertIs<CoverArtLookup.NoMatch>(result)
        Unit
    }

    @Test
    fun coverArtRepositoryStopsOnMusicBrainzRateLimit() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "",
                    status = HttpStatusCode.ServiceUnavailable,
                )
            }
        )

        val result = coverArtRepository(client, mutableListOf()).lookupAlbumCover(
            albumId = 42,
            artist = "Daft Punk",
            album = "Discovery",
        )

        val failure = assertIs<CoverArtLookup.RetryableFailure>(result)
        assertTrue(failure.isRateLimited)
    }

    @Test
    fun aiPromptServiceSendsPromptThroughAllowedHttpsEndpoint() = runBlocking {
        val apiKey = "OPENAI_SECRET"
        var capturedHost = ""
        var capturedAuthorization = ""
        val client = HttpClient(
            MockEngine { request ->
                capturedHost = request.url.host
                capturedAuthorization = request.headers[HttpHeaders.Authorization].orEmpty()
                respond(
                    content = """{"output_text":"hello from model"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )

        val answer = AiPromptService(client).sendPrompt(
            provider = AiPlaylistProviders.OpenAi,
            apiKey = apiKey,
            model = "gpt-5.4-mini",
            prompt = "Say hello",
        )

        val requestBody = buildAiPromptRequestBody(
            provider = AiPlaylistProviders.OpenAi,
            model = "gpt-5.4-mini",
            prompt = "Say hello",
        ).toString()

        assertEquals(NetworkHosts.OpenAi, capturedHost)
        assertEquals("Bearer $apiKey", capturedAuthorization)
        assertTrue(requestBody.contains("Say hello"))
        assertTrue(requestBody.contains("gpt-5.4-mini"))
        assertFalse(requestBody.contains("GPT-5.4 Mini"))
        assertEquals("hello from model", answer)
    }

    private fun coverArtRepository(
        client: HttpClient,
        saved: MutableList<SavedAlbumArt>,
    ): CoverArtRepository {
        return CoverArtRepository(
            client = client,
            fileStore = object : AlbumArtFileStore {
                override suspend fun saveRemoteAlbumArt(
                    albumId: Long,
                    sourceUrl: String,
                    bytes: ByteArray,
                    contentType: String?,
                ): String {
                    saved += SavedAlbumArt(albumId, sourceUrl, bytes, contentType)
                    return "file:///cached/$albumId.jpg"
                }
            },
            musicBrainzLimiter = RequestRateLimiter(0),
            coverDownloadLimiter = RequestRateLimiter(0),
        )
    }

    private data class SavedAlbumArt(
        val albumId: Long,
        val sourceUrl: String,
        val bytes: ByteArray, // TODO:
        val contentType: String?,
    )
}
