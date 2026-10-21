package org.milkdev.dreamplayer.extensions.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
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
        url = "https://${NetworkHosts.MUSIC_BRAINZ}/ws/2/release-group",
        allowedHosts = setOf(NetworkHosts.MUSIC_BRAINZ),
    )
    val CoverArtArchiveEndpoint = SecureNetworkEndpoint(
        serviceName = "Cover Art Archive",
        url = "https://${NetworkHosts.COVER_ART_ARCHIVE}",
        allowedHosts = setOf(NetworkHosts.COVER_ART_ARCHIVE, NetworkHosts.INTERNET_ARCHIVE),
    )
    val LastFmImageHosts = setOf(
        NetworkHosts.LAST_FM,
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

        val requestTrace = NetworkTraceLog.request(
            serviceName = endpoint.serviceName,
            method = "GET",
            url = endpoint.url,
            parameters = listOf(
                "fmt" to "json",
                "limit" to "5",
                "query" to query,
            ),
        )

        return try {
            val response = client.get(endpoint.url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("fmt", "json")
                parameter("limit", "5")
                parameter("query", query)
            }
            val text = response.bodyAsText()
            NetworkTraceLog.textResponse(
                trace = requestTrace,
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
        } catch (error: CancellationException) {
            NetworkTraceLog.cancelled(requestTrace)
            throw error
        } catch (error: Throwable) {
            NetworkTraceLog.failure(
                trace = requestTrace,
                message = error.message ?: "Ошибка сети или TLS",
            )
            MusicBrainzSearchResult.RetryableFailure(
                message = "Ошибка сети или TLS при обращении к MusicBrainz",
            )
        }
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
        val requestTrace = NetworkTraceLog.request(
            serviceName = serviceName,
            method = "GET",
            url = checkedUrl,
        )

        return try {
            var redirectTarget: String? = null
            val lookupResult = noRedirectClient.prepareGet(checkedUrl) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.execute { response ->
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val contentType = response.headers[HttpHeaders.ContentType]
                        if (contentType != null && !contentType.startsWith("image/", ignoreCase = true) &&
                            !contentType.startsWith("application/octet-stream", ignoreCase = true)
                        ) {
                            NetworkTraceLog.metadataResponse(
                                trace = requestTrace,
                                statusCode = response.status.value,
                                contentType = contentType,
                                byteCount = 0,
                            )
                            return@execute CoverArtLookup.PermanentFailure(
                                "Сервер обложек вернул неверный Content-Type: $contentType"
                            )
                        }

                        // 1. Предварительная проверка заголовка Content-Length
                        val contentLength = response.contentLength()
                        if (contentLength != null && contentLength > MAX_IMAGE_SIZE_BYTES) {
                            NetworkTraceLog.metadataResponse(
                                trace = requestTrace,
                                statusCode = response.status.value,
                                contentType = contentType,
                                byteCount = contentLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            )
                            return@execute CoverArtLookup.PermanentFailure(
                                "Размер изображения ($contentLength байт) превышает лимит в $MAX_IMAGE_SIZE_BYTES байт"
                            )
                        }

                        // 2. Безопасное потоковое чтение
                        val channel = response.bodyAsChannel()
                        val bytes = readChannelBounded(channel, MAX_IMAGE_SIZE_BYTES)
                        if (bytes == null) {
                            NetworkTraceLog.metadataResponse(
                                trace = requestTrace,
                                statusCode = response.status.value,
                                contentType = contentType,
                                byteCount = MAX_IMAGE_SIZE_BYTES + 1,
                            )
                            return@execute CoverArtLookup.PermanentFailure(
                                "Размер изображения превышает лимит в $MAX_IMAGE_SIZE_BYTES байт"
                            )
                        }

                        // 3. Проверка сигнатуры файла (Magic Bytes)
                        if (!isSupportedImageSignature(bytes)) {
                            NetworkTraceLog.metadataResponse(
                                trace = requestTrace,
                                statusCode = response.status.value,
                                contentType = contentType,
                                byteCount = bytes.size,
                            )
                            return@execute CoverArtLookup.PermanentFailure(
                                "Файл не является поддерживаемым изображением (неверная сигнатура байтов)"
                            )
                        }

                        NetworkTraceLog.metadataResponse(
                            trace = requestTrace,
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
                            trace = requestTrace,
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
                        NetworkTraceLog.metadataResponse(
                            trace = requestTrace,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                            note = redirectUrl?.let { "location=$it" } ?: "location=missing",
                        )
                        if (redirectUrl == null) {
                            CoverArtLookup.RetryableFailure("Сервер обложек вернул пустой redirect")
                        } else {
                            redirectTarget = redirectUrl
                            CoverArtLookup.NoMatch
                        }
                    }
                    HttpStatusCode.TooManyRequests,
                    HttpStatusCode.ServiceUnavailable -> {
                        NetworkTraceLog.metadataResponse(
                            trace = requestTrace,
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
                            trace = requestTrace,
                            statusCode = response.status.value,
                            contentType = response.headers[HttpHeaders.ContentType],
                        )
                        CoverArtLookup.RetryableFailure(
                            message = "$serviceName ответил HTTP ${response.status.value}",
                        )
                    }
                }
            }
            val nextUrl = redirectTarget
            if (nextUrl != null) {
                downloadCover(
                    albumId = albumId,
                    sourceUrl = nextUrl,
                    serviceName = serviceName,
                    allowedHosts = allowedHosts,
                    limiter = limiter,
                    musicBrainzReleaseGroupMbid = musicBrainzReleaseGroupMbid,
                    redirectDepth = redirectDepth + 1,
                )
            } else {
                lookupResult
            }
        } catch (error: CancellationException) {
            NetworkTraceLog.cancelled(requestTrace)
            throw error
        } catch (error: Throwable) {
            NetworkTraceLog.failure(
                trace = requestTrace,
                message = error.message ?: "Ошибка сети или TLS",
            )
            CoverArtLookup.RetryableFailure("Ошибка сети или TLS при загрузке обложки")
        }
    }

    private suspend fun readChannelBounded(
        channel: io.ktor.utils.io.ByteReadChannel,
        maxSizeBytes: Int,
    ): ByteArray? {
        val chunks = mutableListOf<ByteArray>()
        val buffer = ByteArray(8192)
        var totalBytesRead = 0

        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break
            totalBytesRead += read
            if (totalBytesRead > maxSizeBytes) {
                channel.cancel(null)
                return null
            }
            chunks.add(buffer.copyOf(read))
        }

        val result = ByteArray(totalBytesRead)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    }

    private fun isSupportedImageSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return bytes.startsWith(JPEG_SIGNATURE) ||
                bytes.startsWith(PNG_SIGNATURE) ||
                bytes.startsWith(GIF_SIGNATURE) ||
                (bytes.size >= 12 && bytes.startsWith(WEBP_RIFF_SIGNATURE) && bytes.hasWebpHeaderAt8())
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun ByteArray.hasWebpHeaderAt8(): Boolean {
        return size >= 12 &&
                this[8] == 'W'.code.toByte() &&
                this[9] == 'E'.code.toByte() &&
                this[10] == 'B'.code.toByte() &&
                this[11] == 'P'.code.toByte()
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
        val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val GIF_SIGNATURE = byteArrayOf(0x47, 0x49, 0x46)
        val WEBP_RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        const val USER_AGENT = "DreamPlayer/1.0 (https://github.com/milkdev/DreamPlayer)"
        const val MIN_MUSICBRAINZ_SCORE = 90
        const val MAX_REDIRECTS = 4
        const val MUSICBRAINZ_INTERVAL_MS = 1_100L
        const val COVER_DOWNLOAD_INTERVAL_MS = 600L
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        const val MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024

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
    return "https://${NetworkHosts.COVER_ART_ARCHIVE}/release-group/$mbid/front-500"
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
    return if (startsWith("http://${NetworkHosts.INTERNET_ARCHIVE}", ignoreCase = true)) {
        "https://${NetworkHosts.INTERNET_ARCHIVE}${removePrefix("http://${NetworkHosts.INTERNET_ARCHIVE}")}"
    } else {
        this
    }
}