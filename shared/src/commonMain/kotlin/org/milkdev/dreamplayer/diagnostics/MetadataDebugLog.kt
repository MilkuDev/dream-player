package org.milkdev.dreamplayer.diagnostics

object MetadataDebugLog {
    fun event(
        event: String,
        vararg fields: Pair<String, Any?>,
    ) {
        AppDebugLog.trace(
            category = TraceCategory.Metadata,
            event = event.toDiagnosticToken(lowercase = true),
            message = fields.joinToString(" ") { (name, value) ->
                val safeName = name.toDiagnosticToken(lowercase = false)
                val safeValue = if (DiagnosticRedaction.isSensitiveName(safeName)) {
                    DiagnosticRedaction.REDACTED
                } else {
                    value.toDiagnosticValue()
                }
                "$safeName=$safeValue"
            },
        )
    }
}

private fun String.toDiagnosticToken(lowercase: Boolean): String {
    val normalized = trim().let { if (lowercase) it.lowercase() else it }
    return normalized
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character == '_' || character == '-' || character == '.' -> character
                else -> '_'
            }
        }
        .joinToString("")
        .take(MAX_DIAGNOSTIC_TOKEN_LENGTH)
        .ifBlank { "unknown" }
}

private fun Any?.toDiagnosticValue(): String {
    val raw = when (this) {
        null -> "null"
        is Enum<*> -> name.lowercase()
        else -> toString()
    }
    return DiagnosticRedaction.sanitizeText(raw)
        .replace(' ', '_')
        .take(MAX_DIAGNOSTIC_VALUE_LENGTH)
        .ifBlank { "empty" }
}

private const val MAX_DIAGNOSTIC_TOKEN_LENGTH = 64
private const val MAX_DIAGNOSTIC_VALUE_LENGTH = 120
