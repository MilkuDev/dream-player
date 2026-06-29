package org.milkdev.dreamplayer.extensions.network

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviderDescriptor
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders

class AiPromptService(
    private val client: HttpClient = httpClient,
) {
    suspend fun sendPrompt(
        provider: AiPlaylistProviderDescriptor,
        apiKey: String,
        model: String,
        prompt: String,
    ): String {
        val endpoint = AiNetworkPolicy.recommendationEndpoint(provider, model)
        SecureNetworkPolicy.requireAllowed(endpoint)

        val response = client.post(endpoint.url) {
            applyAiApiKey(provider, apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildAiPromptRequestBody(provider, model, prompt.trim()).toString())
        }.bodyAsText()

        return responseText(provider, response).ifBlank { response }
    }

    private fun responseText(
        provider: AiPlaylistProviderDescriptor,
        response: String,
    ): String {
        return when (provider.id) {
            AiPlaylistProviders.Gemini.id -> json.decodeFromString<GeminiPromptResponse>(response).textPayload()
            AiPlaylistProviders.DeepSeek.id -> json.decodeFromString<DeepSeekPromptResponse>(response).textPayload()
            else -> json.decodeFromString<OpenAiPromptResponse>(response).textPayload()
        }.orEmpty()
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

internal fun buildAiPromptRequestBody(
    provider: AiPlaylistProviderDescriptor,
    model: String,
    prompt: String,
): JsonObject = when (provider.id) {
    AiPlaylistProviders.Gemini.id -> buildJsonObject {
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(
                            buildJsonObject {
                                put("text", prompt)
                            }
                        )
                    }
                }
            )
        }
        putJsonObject("generationConfig") {
            put("temperature", 0.4)
            put("maxOutputTokens", 512)
        }
    }
    AiPlaylistProviders.DeepSeek.id -> buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            )
        }
        put("temperature", 0.4)
        put("stream", false)
    }
    else -> buildJsonObject {
        put("model", model)
        put("input", prompt)
        put("temperature", 0.4)
        put("max_output_tokens", 512)
    }
}

@Serializable
private data class OpenAiPromptResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OpenAiPromptOutput> = emptyList(),
) {
    fun textPayload(): String? {
        return outputText
            ?: output.asSequence()
                .flatMap { it.content.asSequence() }
                .mapNotNull { it.text }
                .firstOrNull { it.isNotBlank() }
    }
}

@Serializable
private data class OpenAiPromptOutput(
    val content: List<OpenAiPromptContent> = emptyList(),
)

@Serializable
private data class OpenAiPromptContent(
    val text: String? = null,
)

@Serializable
private data class GeminiPromptResponse(
    val candidates: List<GeminiPromptCandidate> = emptyList(),
) {
    fun textPayload(): String? {
        return candidates.asSequence()
            .flatMap { it.content?.parts.orEmpty().asSequence() }
            .mapNotNull { it.text }
            .firstOrNull { it.isNotBlank() }
    }
}

@Serializable
private data class GeminiPromptCandidate(
    val content: GeminiPromptContent? = null,
)

@Serializable
private data class GeminiPromptContent(
    val parts: List<GeminiPromptPart> = emptyList(),
)

@Serializable
private data class GeminiPromptPart(
    val text: String? = null,
)

@Serializable
private data class DeepSeekPromptResponse(
    val choices: List<DeepSeekPromptChoice> = emptyList(),
) {
    fun textPayload(): String? {
        return choices.asSequence()
            .mapNotNull { it.message?.content }
            .firstOrNull { it.isNotBlank() }
    }
}

@Serializable
private data class DeepSeekPromptChoice(
    val message: DeepSeekPromptMessage? = null,
)

@Serializable
private data class DeepSeekPromptMessage(
    val content: String? = null,
)
