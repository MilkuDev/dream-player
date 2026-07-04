package org.milkdev.dreamplayer.extensions.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.milkdev.dreamplayer.diagnostics.NetworkTraceLog
import org.milkdev.dreamplayer.extensions.network.NetworkHosts
import org.milkdev.dreamplayer.extensions.network.RequestRateLimiter
import org.milkdev.dreamplayer.extensions.network.SecureNetworkEndpoint
import org.milkdev.dreamplayer.extensions.network.SecureNetworkPolicy
import org.milkdev.dreamplayer.extensions.network.httpClient

object CoverArtNetworkPolicy {
    val MusicBrainzEndpoint = SecureNetworkEndpoint(
        serviceName = "MusicBrainz",
        url = "https://${NetworkHosts.MusicBrainz}/ws/2/release-group",
        allowedHosts = setOf(NetworkHosts.MusicBrainz),
    )
    val CoverArtArchiveEndpoint = SecureNetworkEndpoint(
        serviceName = "Cover Art Archive",
        url = "https://${NetworkHosts.CoverArtArchive}",
        allowedHosts = setOf(NetworkHosts.CoverArtArchive, NetworkHosts.InternetArchive),
    )
    val LastFmImageHosts = setOf(
        NetworkHosts.LastFm,
        "lastfm.freetls.fastly.net",
        "lastfm-img2.akamaized.net",
    )
}

class CoverArtRepository(
    private val client: HttpClient = httpClient,
    private val fileStore: AlbumArtFileStore = createAlbumArtFileStore(),
    private val musicBrainzLimiter: RequestRateLimiter = RequestRateLimiter(MUSICBRAINZ_INTERVAL_MS),
    private val coverDownloadLimiter: RequestRateLimiter = RequestRateLimiter(COVER_DOWNLOAD_INTERVAL_MS),
) {
    private val noRedirectClient = client.config {
        followRedirects = false
    }

    suspend fun lookupAlbumCover(
        albumId: Long,
        artist: String,
        album: String,
    ): CoverArtLookup {
        if (artist.isBlank() || album.isBlank()) return CoverArtLookup.NoMatch

        return when (val search = searchMusicBrainzReleaseGroup(artist = artist, album = album)) {
            is MusicBrainzSearchResult.Found -> downloadCover(
                albumId = albumId,
                sourceUrl = coverArtArchiveFrontUrl(search.releaseGroup.id),
                serviceName = CoverArtNetworkPolicy.CoverArtArchiveEndpoint.serviceName,
                allowedHosts = CoverArtNetworkPolicy.CoverArtArchiveEndpoint.allowedHosts,
                limiter = coverDownloadLimiter,
                musicBrainzReleaseGroupMbid = search.releaseGroup.id,
            )
            MusicBrainzSearchResult.NoMatch -> CoverArtLookup.NoMatch
            is MusicBrainzSearchResult.RetryableFailure -> CoverArtLookup.RetryableFailure(
                message = search.message,
                isRateLimited = search.isRateLimited,
            )
        }
    }

    suspend fun cacheLastFmCover(
        albumId: Long,
        coverUrl: String,
    ): CoverArtLookup {
        if (coverUrl.isBlank()) return CoverArtLookup.NoMatch

        return downloadCover(
            albumId = albumId,
            sourceUrl = coverUrl,
            serviceName = "Last.fm images",
            allowedHosts = CoverArtNetworkPolicy.LastFmImageHosts,
            limiter = coverDownloadLimiter,
            musicBrainzReleaseGroupMbid = null,
        )
    }

    private suspend fun searchMusicBrainzReleaseGroup(
        artist: String,
        album: String,
    ): MusicBrainzSearchResult {
        val endpoint = CoverArtNetworkPolicy.MusicBrainzEndpoint
        runCatching { SecureNetworkPolicy.requireAllowed(endpoint) }
            .getOrElse { error ->
                NetworkTraceLog.failure(
                    serviceName = endpoint.serviceName,
                    message = error.message ?: "Запрос заблокирован политикой безопасности",
                )
                return MusicBrainzSearchResult.RetryableFailure(
                    message = error.message ?: "Запрос MusicBrainz заблокирован политикой безопасности",
                )
            }

        musicBrainzLimiter.awaitPermit()
        val query = buildReleaseGroupQuery(artist = artist, album = album)

        NetworkTraceLog.request(
            serviceName = endpoint.serviceName,
            method = "GET",
            url = endpoint.url,
            parameters = listOf(
                "fmt" to "json",
                "limit" to "5",
                "query" to query,
            ),
        )

        return runCatching {
            client.get(endpoint.url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("fmt", "json")
                parameter("limit", "5")
                parameter("query", query)
            }
        }.fold(
            onSuccess = { response ->
                val text = response.bodyAsText()
                NetworkTraceLog.textResponse(
                    serviceName = endpoint.serviceName,
                    statusCode = response.status.value,
                    body = text,
                    contentType = response.headers[HttpHeaders.ContentType],
                )
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val decoded = runCatching {
                            json.decodeFromString<MusicBrainzReleaseGroupSearchResponse>(
                                text
                            )
                        }.getOrElse {
                            return MusicBrainzSearchResult.RetryableFailure(
                                message = "MusicBrainz вернул неожиданный ответ",
                            )
                        }
                        decoded.releaseGroups
                            .filter { it.score >= MIN_MUSICBRAINZ_SCORE }
                            .firstOrNull { it.matches(artist = artist, album = album) }
                            ?.let { MusicBrainzSearchResult.Found(it) }
                            ?: MusicBrainzSearchResult.NoMatch
                    }
                    HttpStatusCode.TooManyRequests,
                    HttpStatusCode.ServiceUnavailable -> {
                        musicBrainzLimiter.cooldown(RATE_LIMIT_COOLDOWN_MS)
                        MusicBrainzSearchResult.RetryableFailure(
                            message = "MusicBrainz ограничил частоту запросов",
                            isRateLimited = true,
                        )
                    }
                    else -> MusicBrainzSearchResult.RetryableFailure(
                        message = "MusicBrainz ответил HTTP ${response.status.value}",
                        isRateLimited = false,
                    )
                }
            },
            onFailure = { error ->
                NetworkTraceLog.failure(
                    serviceName = endpoint.serviceName,
                    message = error.message ?: "Ошибка сети или TLS",
                )
                MusicBrainzSearchResult.RetryableFailure(
                    message = "Ошибка сети или TLS при обращении к MusicBrainz",
                )
            },
        )
    }

    private suspend fun downloadCover(
        albumId: Long,
        sourceUrl: String,
        serviceName: String,
        allowedHosts: Set<String>,
        limiter: RequestRateLimiter,
        musicBrainzReleaseGroupMbid: String?,
        redirectDepth: Int = 0,
    ): CoverArtLookup {
        if (redirectDepth > MAX_REDIRECTS) {
            return CoverArtLookup.RetryableFailure("Слишком много перенаправлений при загрузке обложки")
        }

        val checkedUrl = sourceUrl.normalizedArchiveRedirectUrl()
        runCatching {
            SecureNetworkPolicy.requireAllowed(
                serviceName = serviceName,
                url = checkedUrl,
                allowedHosts = allowedHosts,
            )
        }.getOrElse { error ->
            NetworkTraceLog.failure(
                serviceName = serviceName,
                message = error.message ?: "URL заблокирован политикой безопасности",
            )
            return CoverArtLookup.PermanentFailure(
                error.message ?: "URL обложки заблокирован политикой безопасности",
            )
        }

        limiter.awaitPermit()
        NetworkTraceLog.request(
            serviceName = serviceName,
            method = "GET",
            url = checkedUrl,
        )

        return runCatching {
            noRedirectClient.get(checkedUrl) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }
        }.fold(
            onSuccess = { response ->
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val bytes: ByteArray = response.body()
                        NetworkTraceLog.metadataResponse(
                            serviceName = serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                            byteCount = bytes.size,
                        )
                        val localUri = fileStore.saveRemoteAlbumArt(
                            albumId = albumId,
                            sourceUrl = checkedUrl,
                            bytes = bytes,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        CoverArtLookup.Found(
                            localUri = localUri,
                            musicBrainzReleaseGroupMbid = musicBrainzReleaseGroupMbid,
                        )
                    }
                    HttpStatusCode.NotFound -> {
                        NetworkTraceLog.metadataResponse(
                            serviceName = serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        CoverArtLookup.NoMatch
                    }
                    HttpStatusCode.MovedPermanently,
                    HttpStatusCode.Found,
                    HttpStatusCode.SeeOther,
                    HttpStatusCode.TemporaryRedirect,
                    HttpStatusCode.PermanentRedirect -> {
                        val redirectUrl = response.headers[HttpHeaders.Location]
                            ?.toRedirectUrl(currentUrl = checkedUrl)
                            ?: return CoverArtLookup.RetryableFailure("Сервер обложек вернул пустой redirect")
                        NetworkTraceLog.metadataResponse(
                            serviceName = serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                            note = "location=$redirectUrl",
                        )
                        downloadCover(
                            albumId = albumId,
                            sourceUrl = redirectUrl,
                            serviceName = serviceName,
                            allowedHosts = allowedHosts,
                            limiter = limiter,
                            musicBrainzReleaseGroupMbid = musicBrainzReleaseGroupMbid,
                            redirectDepth = redirectDepth + 1,
                        )
                    }
                    HttpStatusCode.TooManyRequests,
                    HttpStatusCode.ServiceUnavailable -> {
                        NetworkTraceLog.metadataResponse(
                            serviceName = serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        limiter.cooldown(RATE_LIMIT_COOLDOWN_MS)
                        CoverArtLookup.RetryableFailure(
                            message = "$serviceName ограничил частоту запросов",
                            isRateLimited = true,
                        )
                    }
                    else -> {
                        NetworkTraceLog.metadataResponse(
                            serviceName = serviceName,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        CoverArtLookup.RetryableFailure(
                            message = "$serviceName ответил HTTP ${response.status.value}",
                        )
                    }
                }
            },
            onFailure = { error ->
                NetworkTraceLog.failure(
                    serviceName = serviceName,
                    message = error.message ?: "Ошибка сети или TLS",
                )
                CoverArtLookup.RetryableFailure("Ошибка сети или TLS при загрузке обложки")
            },
        )
    }

    private fun MusicBrainzReleaseGroup.matches(artist: String, album: String): Boolean {
        val expectedAlbum = album.normalizedMusicKey()
        val expectedArtist = artist.normalizedMusicKey()
        val titleMatches = title.normalizedMusicKey() == expectedAlbum
        val artistMatches = artistCredit.any { credit ->
            credit.name.normalizedMusicKey() == expectedArtist ||
                credit.artist?.name?.normalizedMusicKey() == expectedArtist
        }
        return titleMatches && artistMatches
    }

    private companion object {
        const val USER_AGENT = "DreamPlayer/1.0 (https://github.com/milkdev/DreamPlayer)"
        const val MIN_MUSICBRAINZ_SCORE = 90
        const val MAX_REDIRECTS = 4
        const val MUSICBRAINZ_INTERVAL_MS = 1_100L
        const val COVER_DOWNLOAD_INTERVAL_MS = 600L
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

sealed interface CoverArtLookup {
    data class Found(
        val localUri: String,
        val musicBrainzReleaseGroupMbid: String?,
    ) : CoverArtLookup

    object NoMatch : CoverArtLookup

    data class RetryableFailure(
        val message: String,
        val isRateLimited: Boolean = false,
    ) : CoverArtLookup

    data class PermanentFailure(val message: String) : CoverArtLookup
}

private sealed interface MusicBrainzSearchResult {
    data class Found(val releaseGroup: MusicBrainzReleaseGroup) : MusicBrainzSearchResult
    object NoMatch : MusicBrainzSearchResult
    data class RetryableFailure(
        val message: String,
        val isRateLimited: Boolean = false,
    ) : MusicBrainzSearchResult
}

@Serializable
private data class MusicBrainzReleaseGroupSearchResponse(
    @SerialName("release-groups") val releaseGroups: List<MusicBrainzReleaseGroup> = emptyList(),
)

@Serializable
private data class MusicBrainzReleaseGroup(
    val id: String,
    val score: Int = 0,
    val title: String = "",
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzArtistCredit> = emptyList(),
)

@Serializable
private data class MusicBrainzArtistCredit(
    val name: String = "",
    val artist: MusicBrainzArtist? = null,
)

@Serializable
private data class MusicBrainzArtist(
    val name: String = "",
)

private fun buildReleaseGroupQuery(artist: String, album: String): String {
    return "releasegroup:\"${album.escapeMusicBrainzQuery()}\" AND artist:\"${artist.escapeMusicBrainzQuery()}\""
}

private fun coverArtArchiveFrontUrl(mbid: String): String {
    return "https://${NetworkHosts.CoverArtArchive}/release-group/$mbid/front-500"
}

private fun String.escapeMusicBrainzQuery(): String {
    return trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
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

private fun String.toRedirectUrl(currentUrl: String): String {
    val absoluteUrl = if (startsWith("/")) {
        val parsed = Url(currentUrl)
        "${parsed.protocol.name}://${parsed.host}$this"
    } else {
        this
    }
    return absoluteUrl.normalizedArchiveRedirectUrl()
}

private fun String.normalizedArchiveRedirectUrl(): String {
    return if (startsWith("http://${NetworkHosts.InternetArchive}", ignoreCase = true)) {
        "https://${NetworkHosts.InternetArchive}${removePrefix("http://${NetworkHosts.InternetArchive}")}"
    } else {
        this
    }
}
