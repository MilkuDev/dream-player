package org.milkdev.dreamplayer.extensions.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

object AiPlaylistResponseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val fencedJsonRegex = Regex(
        pattern = """```(?:json)?\s*([\s\S]*?)```""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parseTrackIds(rawResponse: String): List<Long> {
        val payload = extractJsonPayload(rawResponse) ?: return emptyList()
        val element = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return emptyList()
        return element.extractIds()
    }

    fun extractJsonPayload(rawResponse: String): String? {
        val text = rawResponse
            .trim()
            .removePrefix("\uFEFF")
            .trim()

        if (text.isBlank()) return null

        fencedJsonRegex.find(text)
            ?.groups
            ?.get(1)
            ?.value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return extractFirstBalancedJson(text)
    }

    private fun JsonElement.extractIds(): List<Long> {
        return when (this) {
            is JsonArray -> mapNotNull { element -> element.asLongOrNull() }
            is JsonObject -> {
                val ids = this["ids"] ?: this["trackIds"] ?: return emptyList()
                ids.extractIds()
            }
            else -> emptyList()
        }
    }

    private fun JsonElement.asLongOrNull(): Long? {
        return (this as? JsonPrimitive)
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.toLongOrNull()
    }

    private fun extractFirstBalancedJson(text: String): String? {
        val startIndex = text.indexOfFirst { it == '{' || it == '[' }
        if (startIndex < 0) return null

        val stack = ArrayDeque<Char>()
        var inString = false
        var escaping = false

        for (index in startIndex until text.length) {
            val character = text[index]

            if (inString) {
                when {
                    escaping -> escaping = false
                    character == '\\' -> escaping = true
                    character == '"' -> inString = false
                }
                continue
            }

            when (character) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isEmpty() || stack.removeLast() != character) {
                        return null
                    }
                    if (stack.isEmpty()) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return null
    }
}
