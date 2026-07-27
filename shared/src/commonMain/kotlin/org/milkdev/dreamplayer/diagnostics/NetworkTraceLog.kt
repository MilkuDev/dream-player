package org.milkdev.dreamplayer.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class NetworkTraceLogChannel {
    LastFm,
    OtherServices,
}

class NetworkRequestTrace internal constructor(
    val id: Long,
    internal val serviceName: String,
    internal val channel: NetworkTraceLogChannel,
    internal val startedAt: TimeMark,
)

@OptIn(ExperimentalAtomicApi::class)
object NetworkTraceLog {
    private const val MAX_URL_LENGTH = 420
    private const val MAX_VALUE_LENGTH = 120
    private const val MAX_TEXT_PREVIEW_LENGTH = 360
    private const val MAX_JSON_STRING_LENGTH = 80
    private const val MAX_JSON_DEPTH = 3
    private const val MAX_JSON_ARRAY_ITEMS = 2
    private const val MAX_JSON_OBJECT_KEYS = 8
    private val nextTraceId = AtomicLong(1L)

    fun request(
        serviceName: String,
        method: String,
        url: String,
        parameters: List<Pair<String, String>> = emptyList(),
        channel: NetworkTraceLogChannel = channelFor(serviceName),
    ): NetworkRequestTrace {
        val trace = NetworkRequestTrace(
            id = nextTraceId.fetchAndAdd(1L),
            serviceName = serviceName,
            channel = channel,
            startedAt = TimeSource.Monotonic.markNow(),
        )
        add(
            channel = channel,
            kind = LogEntryKind.NetworkRequest,
            message = buildString {
                append("[#")
                append(trace.id)
                append("] ")
                append(serviceName)
                append(" -> ")
                append(method.uppercase())
                append(' ')
                append(sanitizeUrl(url).ellipsize(MAX_URL_LENGTH))
                val safeParameters = parameters
                    .filterNot { (_, value) -> value.isBlank() }
                    .joinToString(separator = ", ") { (name, value) ->
                        "$name=${sanitizeValue(name, value).ellipsize(MAX_VALUE_LENGTH)}"
                    }
                if (safeParameters.isNotBlank()) {
                    append(" params=[")
                    append(safeParameters)
                    append(']')
                }
            },
        )
        return trace
    }

    fun textResponse(
        trace: NetworkRequestTrace,
        statusCode: Int,
        body: String,
        contentType: String? = null,
        note: String? = null,
    ) {
        add(
            channel = trace.channel,
            kind = LogEntryKind.NetworkResponse,
            message = buildString {
                append("[#")
                append(trace.id)
                append("] ")
                append(trace.serviceName)
                append(" <- HTTP ")
                append(statusCode)
                append(" durationMs=")
                append(trace.elapsedMilliseconds())
                contentType?.takeIf { it.isNotBlank() }?.let {
                    append(" type=")
                    append(it.ellipsize(MAX_VALUE_LENGTH))
                }
                note?.takeIf { it.isNotBlank() }?.let {
                    append(" ")
                    append(DiagnosticRedaction.sanitizeText(it).ellipsize(MAX_VALUE_LENGTH))
                }
                append(" body=")
                append(summarizeBody(body))
            },
        )
    }

    fun metadataResponse(
        trace: NetworkRequestTrace,
        statusCode: Int,
        contentType: String? = null,
        byteCount: Int? = null,
        note: String? = null,
    ) {
        add(
            channel = trace.channel,
            kind = LogEntryKind.NetworkResponse,
            message = buildString {
                append("[#")
                append(trace.id)
                append("] ")
                append(trace.serviceName)
                append(" <- HTTP ")
                append(statusCode)
                append(" durationMs=")
                append(trace.elapsedMilliseconds())
                contentType?.takeIf { it.isNotBlank() }?.let {
                    append(" type=")
                    append(it.ellipsize(MAX_VALUE_LENGTH))
                }
                byteCount?.let {
                    append(" bytes=")
                    append(it)
                }
                note?.takeIf { it.isNotBlank() }?.let {
                    append(" ")
                    append(DiagnosticRedaction.sanitizeText(it).ellipsize(MAX_VALUE_LENGTH))
                }
            },
        )
    }

    fun failure(
        serviceName: String,
        message: String,
        channel: NetworkTraceLogChannel = channelFor(serviceName),
    ) {
        add(
            channel = channel,
            kind = LogEntryKind.NetworkFailure,
            message = "[-] $serviceName !! ${
                DiagnosticRedaction.sanitizeText(message).ellipsize(MAX_TEXT_PREVIEW_LENGTH)
            }",
        )
    }

    fun failure(
        trace: NetworkRequestTrace,
        message: String,
    ) {
        add(
            channel = trace.channel,
            kind = LogEntryKind.NetworkFailure,
            message = "[#${trace.id}] ${trace.serviceName} !! durationMs=${trace.elapsedMilliseconds()} ${
                DiagnosticRedaction.sanitizeText(message).ellipsize(MAX_TEXT_PREVIEW_LENGTH)
            }",
        )
    }

    fun cancelled(trace: NetworkRequestTrace) {
        add(
            channel = trace.channel,
            kind = LogEntryKind.NetworkCancelled,
            message = "[#${trace.id}] ${trace.serviceName} <- CANCELLED durationMs=${trace.elapsedMilliseconds()}",
        )
    }

    private fun add(
        channel: NetworkTraceLogChannel,
        message: String,
        kind: LogEntryKind,
    ) {
        LogStorage.addNetworkLog(channel, message, kind)
    }

    private fun channelFor(serviceName: String): NetworkTraceLogChannel {
        return if (serviceName.contains("Last.fm", ignoreCase = true)) {
            NetworkTraceLogChannel.LastFm
        } else {
            NetworkTraceLogChannel.OtherServices
        }
    }

    private fun sanitizeUrl(url: String): String {
        return DiagnosticRedaction.sanitizeText(url)
    }

    private fun sanitizeValue(
        name: String,
        value: String,
    ): String {
        return DiagnosticRedaction.sanitizeValue(name, value)
    }

    private fun summarizeBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "empty"

        val jsonElement = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        val sample = if (jsonElement != null) {
            compactJson(jsonElement).toString()
        } else {
            DiagnosticRedaction.sanitizeText(trimmed)
        }.ellipsize(MAX_TEXT_PREVIEW_LENGTH)

        return "${if (jsonElement != null) "json" else "text"}[$sample; chars=${trimmed.length}]"
    }

    private fun compactJson(
        element: JsonElement,
        depth: Int = 0,
    ): JsonElement {
        if (depth >= MAX_JSON_DEPTH) return JsonPrimitive("...")

        return when (element) {
            is JsonObject -> buildJsonObject {
                element.entries.take(MAX_JSON_OBJECT_KEYS).forEach { (key, value) ->
                    put(
                        key,
                        if (DiagnosticRedaction.isSensitiveName(key)) {
                            JsonPrimitive(DiagnosticRedaction.REDACTED)
                        } else {
                            compactJson(value, depth + 1)
                        },
                    )
                }
                if (element.size > MAX_JSON_OBJECT_KEYS) {
                    put("...", JsonPrimitive("${element.size - MAX_JSON_OBJECT_KEYS} more keys"))
                }
            }
            is JsonArray -> buildJsonArray {
                element.take(MAX_JSON_ARRAY_ITEMS).forEach { item ->
                    add(compactJson(item, depth + 1))
                }
                if (element.size > MAX_JSON_ARRAY_ITEMS) {
                    add(JsonPrimitive("${element.size - MAX_JSON_ARRAY_ITEMS} more items"))
                }
            }
            is JsonPrimitive -> compactPrimitive(element)
        }
    }

    private fun compactPrimitive(primitive: JsonPrimitive): JsonPrimitive {
        if (!primitive.isString) return primitive
        val safeContent = DiagnosticRedaction.sanitizeText(primitive.content)
            .ellipsize(MAX_JSON_STRING_LENGTH)
        return JsonPrimitive(safeContent)
    }

    private fun String.ellipsize(maxLength: Int): String {
        return if (length <= maxLength) this else take(maxLength - 3) + "..."
    }

    private fun NetworkRequestTrace.elapsedMilliseconds(): Long {
        return startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
