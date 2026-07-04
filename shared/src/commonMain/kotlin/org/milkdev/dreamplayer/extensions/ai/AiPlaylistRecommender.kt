package org.milkdev.dreamplayer.extensions.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.milkdev.dreamplayer.extensions.network.httpClient
import org.milkdev.dreamplayer.extensions.network.AiNetworkPolicy
import org.milkdev.dreamplayer.extensions.network.SecureNetworkPolicy
import org.milkdev.dreamplayer.extensions.network.applyAiApiKey

interface AiPlaylistRecommender {
    val provider: AiPlaylistProviderDescriptor

    suspend fun recommend(request: AiPlaylistRequest): AiPlaylistRecommendation
}

object AiPlaylistRecommenderRegistry {
    private val recommenders: Map<String, AiPlaylistRecommender> = listOf(
        OpenAiPlaylistRecommender(),
        GeminiPlaylistRecommender(),
        DeepSeekPlaylistRecommender(),
    ).associateBy { it.provider.id }

    fun get(providerId: String): AiPlaylistRecommender? = recommenders[providerId]
}

private class OpenAiPlaylistRecommender(
    private val client: HttpClient = httpClient,
) : AiPlaylistRecommender {
    override val provider: AiPlaylistProviderDescriptor = AiPlaylistProviders.OpenAi

    override suspend fun recommend(request: AiPlaylistRequest): AiPlaylistRecommendation {
        val endpoint = AiNetworkPolicy.recommendationEndpoint(provider, request.model)
        SecureNetworkPolicy.requireAllowed(endpoint)

        val response = client.post(endpoint.url) {
            applyAiApiKey(provider, request.apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("model", request.model)
                    putJsonArray("input") {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_text")
                                            put("text", request.systemPrompt)
                                        }
                                    )
                                }
                            }
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_text")
                                            put("text", userPrompt(request))
                                        }
                                    )
                                }
                            }
                        )
                    }
                    putJsonObject("text") {
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("name", "daily_playlist_ids")
                            put("strict", true)
                            put("schema", playlistIdsSchema())
                        }
                    }
                    put("temperature", 0.4)
                }
            )
        }.successfulBody()

        val text = json.decodeFromString<OpenAiResponse>(response).textPayload()
            ?: response
        return AiPlaylistRecommendation(
            ids = AiPlaylistResponseParser.parseTrackIds(text),
            rawText = text,
            rawProviderResponse = response,
        )
    }
}

private class GeminiPlaylistRecommender(
    private val client: HttpClient = httpClient,
) : AiPlaylistRecommender {
    override val provider: AiPlaylistProviderDescriptor = AiPlaylistProviders.Gemini

    override suspend fun recommend(request: AiPlaylistRequest): AiPlaylistRecommendation {
        val endpoint = AiNetworkPolicy.recommendationEndpoint(provider, request.model)
        SecureNetworkPolicy.requireAllowed(endpoint)

        val response = client.post(endpoint.url) {
            applyAiApiKey(provider, request.apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildGeminiPlaylistRequestBody(request))
        }.successfulBody()

        val decodedResponse = json.decodeFromString<GeminiResponse>(response)
        val text = decodedResponse.textPayload() ?: response
        return AiPlaylistRecommendation(
            ids = AiPlaylistResponseParser.parseTrackIds(text),
            rawText = decodedResponse.debugTextPayload(fallback = response),
            rawProviderResponse = response,
        )
    }
}

private class DeepSeekPlaylistRecommender(
    private val client: HttpClient = httpClient,
) : AiPlaylistRecommender {
    override val provider: AiPlaylistProviderDescriptor = AiPlaylistProviders.DeepSeek

    override suspend fun recommend(request: AiPlaylistRequest): AiPlaylistRecommendation {
        val endpoint = AiNetworkPolicy.recommendationEndpoint(provider, request.model)
        SecureNetworkPolicy.requireAllowed(endpoint)

        val response = client.post(endpoint.url) {
            applyAiApiKey(provider, request.apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("model", request.model)
                    putJsonArray("messages") {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", request.systemPrompt)
                            }
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", userPrompt(request))
                            }
                        )
                    }
                    putJsonObject("response_format") {
                        put("type", "json_object")
                    }
                    put("temperature", 0.4)
                    put("stream", false)
                }
            )
        }.successfulBody()

        val text = json.decodeFromString<DeepSeekResponse>(response).textPayload()
            ?: response
        return AiPlaylistRecommendation(
            ids = AiPlaylistResponseParser.parseTrackIds(text),
            rawText = text,
            rawProviderResponse = response,
        )
    }
}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private suspend fun HttpResponse.successfulBody(): String {
    val body = bodyAsText()
    if (status.isSuccess()) return body

    throw AiPlaylistHttpException(
        statusCode = status.value,
        responseBody = body,
    )
}

private fun userPrompt(request: AiPlaylistRequest): String {
    return "Tracks are TSV rows: id<TAB>artist<TAB>title<TAB>album\n" +
        formatAiPlaylistCandidates(request.candidates) +
        "\nReturn {\"ids\":[...]}"
}

internal fun buildGeminiPlaylistRequestBody(request: AiPlaylistRequest): JsonObject = buildJsonObject {
    putJsonArray("contents") {
        add(
            buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(
                        buildJsonObject {
                            put("text", userPrompt(request))
                        }
                    )
                }
            }
        )
    }
    putJsonObject("systemInstruction") {
        putJsonArray("parts") {
            add(
                buildJsonObject {
                    put("text", request.systemPrompt)
                }
            )
        }
    }
    putJsonObject("generationConfig") {
        put("temperature", 0.4)
        put("maxOutputTokens", 4096)
        putJsonObject("thinkingConfig") {
            put("thinkingLevel", "minimal")
        }
        put("responseMimeType", "application/json")
        put("responseJsonSchema", playlistIdsSchema(maxItems = request.limit))
    }
}

private fun playlistIdsSchema(maxItems: Int? = null) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("ids") {
            put("type", "array")
            maxItems?.let { put("maxItems", it) }
            putJsonObject("items") {
                put("type", "integer")
            }
        }
    }
    put("required", buildJsonArray { add("ids") })
    put("additionalProperties", false)
}

@Serializable
private data class OpenAiResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OpenAiOutput> = emptyList(),
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
private data class OpenAiOutput(
    val content: List<OpenAiContent> = emptyList(),
)

@Serializable
private data class OpenAiContent(
    val text: String? = null,
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
) {
    fun textPayload(): String? {
        return candidates.asSequence()
            .flatMap { it.content?.parts.orEmpty().asSequence() }
            .mapNotNull { it.text }
            .firstOrNull { it.isNotBlank() }
    }

    fun debugTextPayload(fallback: String): String {
        val text = textPayload() ?: fallback
        val finishReasons = candidates
            .mapNotNull { it.finishReason }
            .distinct()
        if (finishReasons.isEmpty()) return text

        return text + "\n\nGemini finishReason: " + finishReasons.joinToString()
    }
}

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
private data class GeminiPart(
    val text: String? = null,
)

@Serializable
private data class DeepSeekResponse(
    val choices: List<DeepSeekChoice> = emptyList(),
) {
    fun textPayload(): String? {
        return choices.asSequence()
            .mapNotNull { it.message?.content }
            .firstOrNull { it.isNotBlank() }
    }
}

@Serializable
private data class DeepSeekChoice(
    val message: DeepSeekMessage? = null,
)

@Serializable
private data class DeepSeekMessage(
    val content: String? = null,
)
