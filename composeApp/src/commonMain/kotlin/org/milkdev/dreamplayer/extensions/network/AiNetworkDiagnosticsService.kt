package org.milkdev.dreamplayer.extensions.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviderDescriptor
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders

object AiNetworkPolicy {
    fun recommendationEndpoint(
        provider: AiPlaylistProviderDescriptor,
        model: String,
    ): SecureNetworkEndpoint {
        return when (provider.id) {
            AiPlaylistProviders.Gemini.id -> SecureNetworkEndpoint(
                serviceName = provider.displayName,
                url = "https://${NetworkHosts.Gemini}/v1beta/models/$model:generateContent",
                allowedHosts = setOf(NetworkHosts.Gemini),
            )
            AiPlaylistProviders.DeepSeek.id -> SecureNetworkEndpoint(
                serviceName = provider.displayName,
                url = "https://${NetworkHosts.DeepSeek}/chat/completions",
                allowedHosts = setOf(NetworkHosts.DeepSeek),
            )
            else -> SecureNetworkEndpoint(
                serviceName = provider.displayName,
                url = "https://${NetworkHosts.OpenAi}/v1/responses",
                allowedHosts = setOf(NetworkHosts.OpenAi),
            )
        }
    }

    fun authCheckEndpoint(provider: AiPlaylistProviderDescriptor): SecureNetworkEndpoint {
        return when (provider.id) {
            AiPlaylistProviders.Gemini.id -> SecureNetworkEndpoint(
                serviceName = provider.displayName,
                url = "https://${NetworkHosts.Gemini}/v1beta/models",
                allowedHosts = setOf(NetworkHosts.Gemini),
            )
            AiPlaylistProviders.DeepSeek.id -> SecureNetworkEndpoint(
                serviceName = provider.displayName,
                url = "https://${NetworkHosts.DeepSeek}/models",
                allowedHosts = setOf(NetworkHosts.DeepSeek),
            )
            else -> SecureNetworkEndpoint(
                serviceName = provider.displayName,
                url = "https://${NetworkHosts.OpenAi}/v1/models",
                allowedHosts = setOf(NetworkHosts.OpenAi),
            )
        }
    }
}

fun HttpRequestBuilder.applyAiApiKey(
    provider: AiPlaylistProviderDescriptor,
    apiKey: String,
) {
    when (provider.id) {
        AiPlaylistProviders.Gemini.id -> header("x-goog-api-key", apiKey)
        else -> header(HttpHeaders.Authorization, "Bearer $apiKey")
    }
}

class AiNetworkDiagnosticsService(
    private val client: HttpClient = httpClient,
) {
    suspend fun testApiKey(
        provider: AiPlaylistProviderDescriptor,
        apiKey: String,
    ): NetworkDiagnosticResult {
        val endpoint = AiNetworkPolicy.authCheckEndpoint(provider)
        val check = runCatching { SecureNetworkPolicy.requireAllowed(endpoint) }
            .getOrElse { error ->
                return blockedResult(endpoint, error)
            }

        return runCatching {
            client.get(endpoint.url) {
                applyAiApiKey(provider, apiKey)
            }
        }.fold(
            onSuccess = { response ->
                val statusCode = response.status.value
                val isSuccessful = statusCode in 200..299
                val isAuthFailure = statusCode == 401 || statusCode == 403
                val isRateLimited = statusCode == 429
                NetworkDiagnosticResult(
                    serviceName = provider.displayName,
                    status = when {
                        isSuccessful -> NetworkDiagnosticStatus.Success
                        isRateLimited -> NetworkDiagnosticStatus.Warning
                        else -> NetworkDiagnosticStatus.Failure
                    },
                    message = when {
                        isSuccessful -> "API доступен, ключ принят"
                        isAuthFailure -> "API доступен, но ключ отклонен"
                        isRateLimited -> "API доступен, но включен rate limit"
                        else -> "API ответил ошибкой"
                    },
                    host = check.host,
                    isHttps = check.isHttps,
                    isHostAllowed = check.isHostAllowed,
                    httpStatusCode = statusCode,
                )
            },
            onFailure = {
                NetworkDiagnosticResult(
                    serviceName = provider.displayName,
                    status = NetworkDiagnosticStatus.Failure,
                    message = "Ошибка сети или TLS",
                    host = check.host,
                    isHttps = check.isHttps,
                    isHostAllowed = check.isHostAllowed,
                )
            },
        )
    }

    private fun blockedResult(
        endpoint: SecureNetworkEndpoint,
        error: Throwable,
    ): NetworkDiagnosticResult {
        val check = runCatching { SecureNetworkPolicy.inspect(endpoint) }.getOrNull()
        return NetworkDiagnosticResult(
            serviceName = endpoint.serviceName,
            status = NetworkDiagnosticStatus.Failure,
            message = error.message ?: "Запрос заблокирован политикой безопасности",
            host = check?.host ?: "unknown",
            isHttps = check?.isHttps ?: false,
            isHostAllowed = check?.isHostAllowed ?: false,
        )
    }
}
