package org.milkdev.dreamplayer.extensions.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.milkdev.dreamplayer.diagnostics.NetworkTraceLog
import org.milkdev.dreamplayer.extensions.network.NetworkHosts
import org.milkdev.dreamplayer.extensions.network.RequestRateLimiter
import org.milkdev.dreamplayer.extensions.network.SecureNetworkEndpoint
import org.milkdev.dreamplayer.extensions.network.SecureNetworkPolicy
import org.milkdev.dreamplayer.extensions.network.httpClient

class MusicBrainzMetadataRepository(
    private val client: HttpClient = httpClient,
    private val limiter: RequestRateLimiter = RequestRateLimiter(MUSICBRAINZ_INTERVAL_MS),
) {
    suspend fun lookupAlbumMetadata(
        artist: String,
        album: String,
        releaseGroupMbid: String?,
    ): MusicBrainzMetadataLookup {
        releaseGroupMbid?.takeIf { it.isNotBlank() }?.let { mbid ->
            when (val lookup = lookupReleaseGroup(mbid)) {
                is MusicBrainzMetadataLookup.Found -> return lookup
                is MusicBrainzMetadataLookup.RetryableFailure -> return lookup
                MusicBrainzMetadataLookup.NoMatch -> Unit
            }
        }

        return searchReleaseGroup(artist = artist, album = album)
    }

    private suspend fun lookupReleaseGroup(mbid: String): MusicBrainzMetadataLookup {
        val endpoint = MusicBrainzMetadataNetworkPolicy.releaseGroupLookupEndpoint(mbid)
        return request(
            endpoint = endpoint,
            traceParameters = listOf(
                "fmt" to "json",
                "inc" to "genres+tags",
            ),
        ) {
            parameter("fmt", "json")
            parameter("inc", "genres+tags")
        }?.let { text ->
            val releaseGroup = runCatching {
                json.decodeFromString<MusicBrainzMetadataReleaseGroup>(text)
            }.getOrElse {
                return MusicBrainzMetadataLookup.RetryableFailure("MusicBrainz вернул неожиданный ответ")
            }
            MusicBrainzMetadataLookup.Found(releaseGroup.toAlbumMetadata(confidence = 1f))
        } ?: MusicBrainzMetadataLookup.NoMatch
    }

    private suspend fun searchReleaseGroup(
        artist: String,
        album: String,
    ): MusicBrainzMetadataLookup {
        if (artist.isBlank() || album.isBlank()) return MusicBrainzMetadataLookup.NoMatch

        val endpoint = MusicBrainzMetadataNetworkPolicy.ReleaseGroupSearchEndpoint
        val query = buildReleaseGroupQuery(artist = artist, album = album)
        val text = request(
            endpoint = endpoint,
            traceParameters = listOf(
                "fmt" to "json",
                "limit" to "5",
                "query" to query,
            ),
        ) {
            parameter("fmt", "json")
            parameter("limit", "5")
            parameter("query", query)
        } ?: return MusicBrainzMetadataLookup.NoMatch

        val decoded = runCatching {
            json.decodeFromString<MusicBrainzMetadataReleaseGroupSearchResponse>(text)
        }.getOrElse {
            return MusicBrainzMetadataLookup.RetryableFailure("MusicBrainz вернул неожиданный ответ")
        }

        val best = decoded.releaseGroups
            .filter { it.score >= MIN_MUSICBRAINZ_SCORE }
            .firstOrNull { it.matches(artist = artist, album = album) }
            ?: return MusicBrainzMetadataLookup.NoMatch

        return MusicBrainzMetadataLookup.Found(best.toAlbumMetadata(confidence = best.score / 100f))
    }

    private suspend fun request(
        endpoint: SecureNetworkEndpoint,
        traceParameters: List<Pair<String, String>>,
        parameters: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): String? {
        runCatching { SecureNetworkPolicy.requireAllowed(endpoint) }
            .getOrElse { error ->
                NetworkTraceLog.failure(
                    serviceName = endpoint.serviceName,
                    message = error.message ?: "Запрос заблокирован политикой безопасности",
                )
                return null
            }

        limiter.awaitPermit()
        NetworkTraceLog.request(
            serviceName = endpoint.serviceName,
            method = "GET",
            url = endpoint.url,
            parameters = traceParameters,
        )

        return runCatching {
            client.get(endpoint.url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameters()
            }
        }.fold(
            onSuccess = { response ->
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val text = response.bodyAsText()
                        NetworkTraceLog.textResponse(
                            serviceName = endpoint.serviceName,
                            statusCode = response.status.value,
                            body = text,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        text
                    }
                    HttpStatusCode.NotFound -> {
                        NetworkTraceLog.metadataResponse(
                            serviceName = endpoint.serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        null
                    }
                    HttpStatusCode.TooManyRequests,
                    HttpStatusCode.ServiceUnavailable -> {
                        NetworkTraceLog.metadataResponse(
                            serviceName = endpoint.serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        limiter.cooldown(RATE_LIMIT_COOLDOWN_MS)
                        null
                    }
                    else -> {
                        NetworkTraceLog.metadataResponse(
                            serviceName = endpoint.serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        null
                    }
                }
            },
            onFailure = { error ->
                NetworkTraceLog.failure(
                    serviceName = endpoint.serviceName,
                    message = error.message ?: "Ошибка сети или TLS",
                )
                null
            },
        )
    }

    private fun MusicBrainzMetadataReleaseGroup.toAlbumMetadata(confidence: Float): MusicBrainzAlbumMetadata {
        val genreNames = genres
            .ifEmpty { tags }
            .sortedByDescending { it.count }
            .map { it.name.normalizeGenre() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_MUSICBRAINZ_GENRES)

        return MusicBrainzAlbumMetadata(
            releaseGroupMbid = id,
            year = firstReleaseDate?.take(4)?.toIntOrNull(),
            genres = genreNames,
            confidence = confidence,
        )
    }

    private fun MusicBrainzMetadataReleaseGroup.matches(artist: String, album: String): Boolean {
        val expectedAlbum = album.normalizedMusicKey()
        val expectedArtist = artist.normalizedMusicKey()
        val titleMatches = title.normalizedMusicKey() == expectedAlbum
        val artistMatches = artistCredit.any { credit ->
            credit.name.normalizedMusicKey() == expectedArtist ||
                credit.artist?.name?.normalizedMusicKey() == expectedArtist
        }
        return titleMatches && artistMatches
    }

    private fun String.normalizeGenre(): String {
        return trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun String.normalizedMusicKey(): String {
        val characters = buildString {
            this@normalizedMusicKey.trim().lowercase().forEach { character ->
                append(if (character.isLetterOrDigit()) character else ' ')
            }
        }
        return characters.splitToSequence(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun buildReleaseGroupQuery(artist: String, album: String): String {
        return "releasegroup:\"${album.escapeMusicBrainzQuery()}\" AND artist:\"${artist.escapeMusicBrainzQuery()}\""
    }

    private fun String.escapeMusicBrainzQuery(): String {
        return trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private companion object {
        const val USER_AGENT = "DreamPlayer/1.0 (https://github.com/milkdev/DreamPlayer)"
        const val MUSICBRAINZ_INTERVAL_MS = 1_100L
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        const val MIN_MUSICBRAINZ_SCORE = 90
        const val MAX_MUSICBRAINZ_GENRES = 5

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

object MusicBrainzMetadataNetworkPolicy {
    val ReleaseGroupSearchEndpoint = SecureNetworkEndpoint(
        serviceName = "MusicBrainz",
        url = "https://${NetworkHosts.MusicBrainz}/ws/2/release-group",
        allowedHosts = setOf(NetworkHosts.MusicBrainz),
    )

    fun releaseGroupLookupEndpoint(mbid: String): SecureNetworkEndpoint {
        return SecureNetworkEndpoint(
            serviceName = "MusicBrainz",
            url = "https://${NetworkHosts.MusicBrainz}/ws/2/release-group/$mbid",
            allowedHosts = setOf(NetworkHosts.MusicBrainz),
        )
    }
}

sealed interface MusicBrainzMetadataLookup {
    data class Found(val metadata: MusicBrainzAlbumMetadata) : MusicBrainzMetadataLookup
    object NoMatch : MusicBrainzMetadataLookup
    data class RetryableFailure(val message: String) : MusicBrainzMetadataLookup
}

data class MusicBrainzAlbumMetadata(
    val releaseGroupMbid: String,
    val year: Int?,
    val genres: List<String>,
    val confidence: Float,
)

@Serializable
private data class MusicBrainzMetadataReleaseGroupSearchResponse(
    @SerialName("release-groups") val releaseGroups: List<MusicBrainzMetadataReleaseGroup> = emptyList(),
)

@Serializable
private data class MusicBrainzMetadataReleaseGroup(
    val id: String,
    val score: Int = 100,
    val title: String = "",
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzMetadataArtistCredit> = emptyList(),
    val genres: List<MusicBrainzMetadataTag> = emptyList(),
    val tags: List<MusicBrainzMetadataTag> = emptyList(),
)

@Serializable
private data class MusicBrainzMetadataArtistCredit(
    val name: String = "",
    val artist: MusicBrainzMetadataArtist? = null,
)

@Serializable
private data class MusicBrainzMetadataArtist(
    val name: String = "",
)

@Serializable
private data class MusicBrainzMetadataTag(
    val name: String = "",
    val count: Int = 0,
)


