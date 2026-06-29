package org.milkdev.dreamplayer.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

enum class NetworkTraceLogChannel {
    LastFm,
    OtherServices,
}

object NetworkTraceLog {
    private const val MAX_URL_LENGTH = 420
    private const val MAX_VALUE_LENGTH = 120
    private const val MAX_TEXT_PREVIEW_LENGTH = 360
    private const val MAX_JSON_STRING_LENGTH = 80
    private const val MAX_JSON_DEPTH = 3
    private const val MAX_JSON_ARRAY_ITEMS = 2
    private const val MAX_JSON_OBJECT_KEYS = 8

    private val sensitiveQueryPatterns = listOf(
        Regex("(?i)([?&](?:api_key|key|token|access_token)=)[^&]*"),
        Regex("(?i)([?&]authorization=)[^&]*"),
    )

    fun request(
        serviceName: String,
        method: String,
        url: String,
        parameters: List<Pair<String, String>> = emptyList(),
        channel: NetworkTraceLogChannel = channelFor(serviceName),
    ) {
        add(
            channel = channel,
            message = buildString {
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
    }

    fun textResponse(
        serviceName: String,
        statusCode: Int,
        body: String,
        contentType: String? = null,
        note: String? = null,
        channel: NetworkTraceLogChannel = channelFor(serviceName),
    ) {
        add(
            channel = channel,
            message = buildString {
                append(serviceName)
                append(" <- HTTP ")
                append(statusCode)
                contentType?.takeIf { it.isNotBlank() }?.let {
                    append(" type=")
                    append(it.ellipsize(MAX_VALUE_LENGTH))
                }
                note?.takeIf { it.isNotBlank() }?.let {
                    append(" ")
                    append(it.ellipsize(MAX_VALUE_LENGTH))
                }
                append(" body=")
                append(summarizeBody(body))
            },
        )
    }

    fun metadataResponse(
        serviceName: String,
        statusCode: Int,
        contentType: String? = null,
        byteCount: Int? = null,
        note: String? = null,
        channel: NetworkTraceLogChannel = channelFor(serviceName),
    ) {
        add(
            channel = channel,
            message = buildString {
                append(serviceName)
                append(" <- HTTP ")
                append(statusCode)
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
                    append(it.ellipsize(MAX_VALUE_LENGTH))
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
            message = "$serviceName !! ${message.replace(Regex("\\s+"), " ").ellipsize(MAX_TEXT_PREVIEW_LENGTH)}",
        )
    }

    private fun add(
        channel: NetworkTraceLogChannel,
        message: String,
    ) {
        LogStorage.addNetworkLog(channel, message)
    }

    private fun channelFor(serviceName: String): NetworkTraceLogChannel {
        return if (serviceName.contains("Last.fm", ignoreCase = true)) {
            NetworkTraceLogChannel.LastFm
        } else {
            NetworkTraceLogChannel.OtherServices
        }
    }

    private fun sanitizeUrl(url: String): String {
        return sensitiveQueryPatterns.fold(url) { current, pattern ->
            pattern.replace(current) { match -> "${match.groupValues[1]}***" }
        }
    }

    private fun sanitizeValue(
        name: String,
        value: String,
    ): String {
        val normalizedName = name.lowercase()
        return if (
            normalizedName.contains("key") ||
            normalizedName.contains("token") ||
            normalizedName.contains("authorization")
        ) {
            "***"
        } else {
            value.replace(Regex("\\s+"), " ")
        }
    }

    private fun summarizeBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "empty"

        val jsonElement = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        val sample = if (jsonElement != null) {
            compactJson(jsonElement).toString()
        } else {
            trimmed.replace(Regex("\\s+"), " ")
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
                    put(key, compactJson(value, depth + 1))
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
        val raw = primitive.toString()
        return if (raw.length > MAX_JSON_STRING_LENGTH) {
            JsonPrimitive(raw.ellipsize(MAX_JSON_STRING_LENGTH))
        } else {
            primitive
        }
    }

    private fun String.ellipsize(maxLength: Int): String {
        return if (length <= maxLength) this else take(maxLength - 3) + "..."
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
