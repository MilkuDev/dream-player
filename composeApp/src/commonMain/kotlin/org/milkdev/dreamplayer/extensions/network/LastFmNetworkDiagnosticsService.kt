package org.milkdev.dreamplayer.extensions.network

class LastFmNetworkDiagnosticsService(
    private val service: LastFmService = LastFmService(),
) {
    suspend fun testApiKey(apiKey: String): LastFmApiTestStatus {
        val check = SecureNetworkPolicy.inspect(LastFmNetworkPolicy.ApiEndpoint)
        val result = when (val response = service.testApiKey(apiKey)) {
            is LastFmResult.Success -> NetworkDiagnosticResult(
                serviceName = LastFmNetworkPolicy.ApiEndpoint.serviceName,
                status = NetworkDiagnosticStatus.Success,
                message = "API доступен, ключ принят",
                host = check.host,
                isHttps = check.isHttps,
                isHostAllowed = check.isHostAllowed,
                httpStatusCode = 200,
            )
            is LastFmResult.Failure -> NetworkDiagnosticResult(
                serviceName = LastFmNetworkPolicy.ApiEndpoint.serviceName,
                status = response.type.toDiagnosticStatus(),
                message = response.message,
                host = check.host,
                isHttps = check.isHttps,
                isHostAllowed = check.isHostAllowed,
                httpStatusCode = response.httpStatusCode,
            )
        }

        return LastFmApiTestStatus(result)
    }

    private fun LastFmErrorType.toDiagnosticStatus(): NetworkDiagnosticStatus {
        return when (this) {
            LastFmErrorType.RateLimited,
            LastFmErrorType.Temporary -> NetworkDiagnosticStatus.Warning
            LastFmErrorType.NotFound -> NetworkDiagnosticStatus.Failure
            else -> NetworkDiagnosticStatus.Failure
        }
    }
}
