package org.milkdev.dreamplayer.diagnostics

internal object DiagnosticRedaction {
    private val sensitiveNames = setOf(
        "api-key",
        "api_key",
        "apikey",
        "authorization",
        "client-secret",
        "client_secret",
        "key",
        "password",
        "secret",
        "signature",
        "token",
        "access-token",
        "access_token",
    )
    private val sensitiveQueryPattern = Regex(
        "(?i)([?&](?:api[_-]?key|key|token|access[_-]?token|authorization|client[_-]?secret|" +
            "secret|password|signature)=)[^&#\\s]*"
    )
    private val authorizationPattern = Regex(
        "(?i)(\\bauthorization\\s*[:=]\\s*)(?:bearer\\s+)?[^&,\\s}\\]]+"
    )
    private val sensitiveAssignmentPattern = Regex(
        "(?i)\\b(api[_-]?key|key|token|access[_-]?token|client[_-]?secret|secret|password|" +
            "signature)(\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^&,\\s}\\]]+)"
    )
    private val whitespace = Regex("\\s+")

    fun isSensitiveName(name: String): Boolean {
        return name.trim().lowercase() in sensitiveNames
    }

    fun sanitizeValue(name: String, value: String): String {
        return if (isSensitiveName(name)) REDACTED else sanitizeText(value)
    }

    fun sanitizeText(value: String): String {
        return value
            .let { sensitiveQueryPattern.replace(it) { match -> "${match.groupValues[1]}$REDACTED" } }
            .let { authorizationPattern.replace(it) { match -> "${match.groupValues[1]}$REDACTED" } }
            .let {
                sensitiveAssignmentPattern.replace(it) { match ->
                    "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
                }
            }
            .replace(whitespace, " ")
            .trim()
    }

    const val REDACTED = "***"
}
