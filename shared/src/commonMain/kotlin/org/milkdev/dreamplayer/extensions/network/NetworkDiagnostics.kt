@file:Suppress("SpellCheckingInspection")

package org.milkdev.dreamplayer.extensions.network

import io.ktor.http.URLProtocol
import io.ktor.http.Url

enum class NetworkDiagnosticStatus {
    Success,
    Warning,
    Failure,
}

data class NetworkDiagnosticResult(
    val serviceName: String,
    val status: NetworkDiagnosticStatus,
    val message: String,
    val host: String,
    val isHttps: Boolean,
    val isHostAllowed: Boolean,
    val httpStatusCode: Int? = null,
) {
    fun toDisplayText(): String {
        val httpStatus = httpStatusCode?.let { " HTTP $it." }.orEmpty()
        val channel = if (isHttps) "HTTPS" else "не HTTPS"
        val hostStatus = if (isHostAllowed) "host разрешен" else "host заблокирован"
        return "$serviceName: $message.$httpStatus Канал: $channel, $hostStatus ($host)"
    }
}

data class LastFmApiTestStatus(
    val result: NetworkDiagnosticResult,
) {
    fun toDisplayText(): String = result.toDisplayText()
}

data class LastFmSettingsUiState(
    val supportsSecrets: Boolean = false,
    val isApiKeyConfigured: Boolean = false,
    val isTestInProgress: Boolean = false,
    val testStatus: LastFmApiTestStatus? = null,
    val isMetadataSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val coverPendingCount: Int = 0,
    val lastFmPendingCount: Int = 0,
    val processedCount: Int = 0,
    val lastMetadataSyncMessage: String? = null,
)

data class SecureNetworkEndpoint(
    val serviceName: String,
    val url: String,
    val allowedHosts: Set<String>,
)

data class SecureNetworkCheck(
    val host: String,
    val isHttps: Boolean,
    val isHostAllowed: Boolean,
)

class NetworkSecurityException(message: String) : IllegalArgumentException(message)

object SecureNetworkPolicy {
    fun requireAllowed(endpoint: SecureNetworkEndpoint): SecureNetworkCheck {
        val check = inspect(endpoint)
        if (!check.isHttps) {
            throw NetworkSecurityException("${endpoint.serviceName}: ключ можно отправлять только по HTTPS")
        }
        if (!check.isHostAllowed) {
            throw NetworkSecurityException("${endpoint.serviceName}: host ${check.host} не входит в allowlist")
        }
        return check
    }

    fun requireAllowed(
        serviceName: String,
        url: String,
        allowedHosts: Set<String>,
    ): SecureNetworkCheck {
        return requireAllowed(
            SecureNetworkEndpoint(
                serviceName = serviceName,
                url = url,
                allowedHosts = allowedHosts,
            )
        )
    }

    fun inspect(endpoint: SecureNetworkEndpoint): SecureNetworkCheck {
        val parsedUrl = Url(endpoint.url)
        val host = parsedUrl.host.lowercase()
        return SecureNetworkCheck(
            host = host,
            isHttps = parsedUrl.protocol == URLProtocol.HTTPS,
            isHostAllowed = host in endpoint.allowedHosts.map { it.lowercase() },
        )
    }
}

object NetworkHosts {
    const val OPEN_AI = "api.openai.com"
    const val GEMINI = "generativelanguage.googleapis.com"
    const val DEEP_SEEK = "api.deepseek.com"
    const val LAST_FM = "ws.audioscrobbler.com"
    const val MUSIC_BRAINZ = "musicbrainz.org"
    const val COVER_ART_ARCHIVE = "coverartarchive.org"
    const val INTERNET_ARCHIVE = "archive.org"
}
