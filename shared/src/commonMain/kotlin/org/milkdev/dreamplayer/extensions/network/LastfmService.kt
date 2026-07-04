package org.milkdev.dreamplayer.extensions.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.milkdev.dreamplayer.diagnostics.NetworkTraceLog
import org.milkdev.dreamplayer.extensions.data.LastFmAlbumResponse
import org.milkdev.dreamplayer.extensions.data.LastFmAlbumSearchResponse

enum class LastFmErrorType {
    InvalidApiKey,
    SuspendedApiKey,
    RateLimited,
    NotFound,
    Temporary,
    HttpError,
    Blocked,
    Network,
    InvalidResponse,
}

sealed interface LastFmResult<out T> {
    data class Success<T>(val value: T) : LastFmResult<T>

    data class Failure(
        val type: LastFmErrorType,
        val message: String,
        val code: Int? = null,
        val httpStatusCode: Int? = null,
    ) : LastFmResult<Nothing>
}

object LastFmNetworkPolicy {
    val ApiEndpoint = SecureNetworkEndpoint(
        serviceName = "Last.fm",
        url = "https://${NetworkHosts.LastFm}/2.0/",
        allowedHosts = setOf(NetworkHosts.LastFm),
    )
}

class LastFmService(
    private val client: HttpClient = httpClient,
) {
    suspend fun testApiKey(apiKey: String): LastFmResult<Unit> {
        val response = requestText(
            apiKey = apiKey,
            method = "chart.gettopartists",
            traceParameters = listOf("limit" to "1"),
        ) {
            parameter("limit", "1")
        }

        return when (response) {
            is LastFmResult.Success -> LastFmResult.Success(Unit)
            is LastFmResult.Failure -> response
        }
    }

    suspend fun getAlbumInfo(
        apiKey: String,
        artist: String,
        album: String,
    ): LastFmResult<LastFmAlbumResponse> {
        val response = requestText(
            apiKey = apiKey,
            method = "album.getinfo",
            traceParameters = listOf(
                "artist" to artist,
                "album" to album,
                "autocorrect" to "1",
            ),
        ) {
            parameter("artist", artist)
            parameter("album", album)
            parameter("autocorrect", "1")
        }

        return when (response) {
            is LastFmResult.Success -> decodeResponse(response.value)
            is LastFmResult.Failure -> response
        }
    }

    suspend fun getAlbumInfoByMbid(
        apiKey: String,
        mbid: String,
    ): LastFmResult<LastFmAlbumResponse> {
        val response = requestText(
            apiKey = apiKey,
            method = "album.getinfo",
            traceParameters = listOf(
                "mbid" to mbid,
                "autocorrect" to "1",
            ),
        ) {
            parameter("mbid", mbid)
            parameter("autocorrect", "1")
        }

        return when (response) {
            is LastFmResult.Success -> decodeResponse(response.value)
            is LastFmResult.Failure -> response
        }
    }

    suspend fun searchAlbums(
        apiKey: String,
        album: String,
        limit: Int = 10,
    ): LastFmResult<LastFmAlbumSearchResponse> {
        val safeLimit = limit.coerceIn(1, 30).toString()
        val response = requestText(
            apiKey = apiKey,
            method = "album.search",
            traceParameters = listOf(
                "album" to album,
                "limit" to safeLimit,
            ),
        ) {
            parameter("album", album)
            parameter("limit", safeLimit)
        }

        return when (response) {
            is LastFmResult.Success -> decodeResponse(response.value)
            is LastFmResult.Failure -> response
        }
    }

    private suspend fun requestText(
        apiKey: String,
        method: String,
        traceParameters: List<Pair<String, String>>,
        extraParameters: HttpRequestBuilder.() -> Unit = {},
    ): LastFmResult<String> {
        val endpoint = LastFmNetworkPolicy.ApiEndpoint
        runCatching { SecureNetworkPolicy.requireAllowed(endpoint) }
            .getOrElse { error ->
                NetworkTraceLog.failure(
                    serviceName = endpoint.serviceName,
                    message = error.message ?: "Запрос заблокирован политикой безопасности",
                )
                return LastFmResult.Failure(
                    type = LastFmErrorType.Blocked,
                    message = error.message ?: "Запрос Last.fm заблокирован политикой безопасности",
                )
            }

        NetworkTraceLog.request(
            serviceName = endpoint.serviceName,
            method = "GET",
            url = endpoint.url,
            parameters = listOf(
                "method" to method,
                "format" to "json",
            ) + traceParameters,
        )

        return runCatching {
            client.get(endpoint.url) {
                parameter("method", method)
                parameter("api_key", apiKey.trim())
                parameter("format", "json")
                extraParameters()
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
                parseApiError(text, response.status.value)
                    ?: if (response.status.value in 200..299) {
                        LastFmResult.Success(text)
                    } else {
                        LastFmResult.Failure(
                            type = LastFmErrorType.HttpError,
                            message = "Last.fm ответил HTTP ${response.status.value}",
                            httpStatusCode = response.status.value,
                        )
                    }
            },
            onFailure = { error ->
                NetworkTraceLog.failure(
                    serviceName = endpoint.serviceName,
                    message = error.message ?: "Ошибка сети или TLS",
                )
                LastFmResult.Failure(
                    type = LastFmErrorType.Network,
                    message = "Ошибка сети или TLS при обращении к Last.fm",
                )
            },
        )
    }

    private inline fun <reified T> decodeResponse(text: String): LastFmResult<T> {
        return runCatching {
            json.decodeFromString<T>(text)
        }.fold(
            onSuccess = { LastFmResult.Success(it) },
            onFailure = {
                LastFmResult.Failure(
                    type = LastFmErrorType.InvalidResponse,
                    message = "Last.fm вернул неожиданный ответ",
                )
            },
        )
    }

    private fun parseApiError(
        text: String,
        httpStatusCode: Int,
    ): LastFmResult.Failure? {
        val error = runCatching {
            json.decodeFromString<LastFmErrorResponse>(text)
        }.getOrNull()?.takeIf { it.code != null } ?: return null

        val code = error.code
        return LastFmResult.Failure(
            type = when (code) {
                10 -> LastFmErrorType.InvalidApiKey
                26 -> LastFmErrorType.SuspendedApiKey
                29 -> LastFmErrorType.RateLimited
                6 -> LastFmErrorType.NotFound
                11, 16 -> LastFmErrorType.Temporary
                else -> LastFmErrorType.HttpError
            },
            message = when (code) {
                10 -> "Last.fm отклонил API-ключ"
                26 -> "Last.fm ключ приостановлен"
                29 -> "Last.fm включил rate limit"
                6 -> "Last.fm не нашел альбом"
                11, 16 -> "Last.fm временно недоступен"
                else -> error.message ?: "Last.fm ответил ошибкой"
            },
            code = code,
            httpStatusCode = httpStatusCode,
        )
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

@Serializable
private data class LastFmErrorResponse(
    @SerialName("error") val code: Int? = null,
    val message: String? = null,
)
