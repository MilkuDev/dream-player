package org.milkdev.dreamplayer.extensions.ai

data class AiPlaylistCandidate(
    val id: Long,
    val artist: String,
    val title: String,
    val album: String,
)

data class AiPlaylistProviderDescriptor(
    val id: String,
    val displayName: String,
    val defaultModelId: String,
)

data class AiPlaylistModelDescriptor(
    val displayName: String,
    val apiModel: String,
    val providerId: String,
    val isDefault: Boolean = false,
)

data class AiPlaylistPromptPreset(
    val id: String,
    val displayName: String,
    val systemPrompt: String,
    val isCustom: Boolean = false,
)

data class AiPlaylistSettings(
    val providerId: String = AiPlaylistProviders.OpenAi.id,
    val model: String = AiPlaylistProviders.OpenAi.defaultModelId,
    val promptPresetId: String = AiPlaylistPromptPresets.DEFAULT_ID,
    val customSystemPrompt: String = "",
)

data class AiPlaylistRequest(
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val candidates: List<AiPlaylistCandidate>,
    val limit: Int,
)

data class AiPlaylistRecommendation(
    val ids: List<Long>,
    val rawText: String,
    val rawProviderResponse: String,
)

class AiPlaylistHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException(
    "AI API returned HTTP $statusCode: ${responseBody.safeAiErrorSnippet()}"
)

private fun String.safeAiErrorSnippet(maxLength: Int = 600): String {
    val snippet = replace(Regex("\\s+"), " ").trim()
    return if (snippet.length > maxLength) {
        snippet.take(maxLength) + "..."
    } else {
        snippet
    }
}

object AiPlaylistProviders {
    val OpenAi = AiPlaylistProviderDescriptor(
        id = "openai",
        displayName = "OpenAI",
        defaultModelId = "gpt-5.4-mini",
    )
    val Gemini = AiPlaylistProviderDescriptor(
        id = "gemini",
        displayName = "Gemini",
        defaultModelId = "gemini-3.5-flash",
    )
    val DeepSeek = AiPlaylistProviderDescriptor(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultModelId = "deepseek-v4-flash",
    )

    val all: List<AiPlaylistProviderDescriptor> = listOf(OpenAi, Gemini, DeepSeek)

    fun byId(id: String?): AiPlaylistProviderDescriptor =
        all.firstOrNull { it.id == id } ?: OpenAi
}

object AiPlaylistModels {
    val all: List<AiPlaylistModelDescriptor> = listOf(
        AiPlaylistModelDescriptor(
            displayName = "GPT-5.4 Mini",
            apiModel = "gpt-5.4-mini",
            providerId = AiPlaylistProviders.OpenAi.id,
            isDefault = true,
        ),
        AiPlaylistModelDescriptor(
            displayName = "GPT-5.4",
            apiModel = "gpt-5.4",
            providerId = AiPlaylistProviders.OpenAi.id,
        ),
        AiPlaylistModelDescriptor(
            displayName = "GPT-5.5",
            apiModel = "gpt-5.5",
            providerId = AiPlaylistProviders.OpenAi.id,
        ),
        AiPlaylistModelDescriptor(
            displayName = "Gemini 3.5 Flash",
            apiModel = "gemini-3.5-flash",
            providerId = AiPlaylistProviders.Gemini.id,
            isDefault = true,
        ),
        AiPlaylistModelDescriptor(
            displayName = "Gemini 3.1 Flash-Lite",
            apiModel = "gemini-3.1-flash-lite",
            providerId = AiPlaylistProviders.Gemini.id,
        ),
        AiPlaylistModelDescriptor(
            displayName = "Gemini 3 Flash",
            apiModel = "gemini-3-flash",
            providerId = AiPlaylistProviders.Gemini.id,
        ),
        AiPlaylistModelDescriptor(
            displayName = "DeepSeek V4 Flash",
            apiModel = "deepseek-v4-flash",
            providerId = AiPlaylistProviders.DeepSeek.id,
            isDefault = true,
        ),
        AiPlaylistModelDescriptor(
            displayName = "DeepSeek V4 Pro",
            apiModel = "deepseek-v4-pro",
            providerId = AiPlaylistProviders.DeepSeek.id,
        ),
    )

    fun forProvider(providerId: String): List<AiPlaylistModelDescriptor> {
        val provider = AiPlaylistProviders.byId(providerId)
        return all.filter { it.providerId == provider.id }
    }

    fun defaultForProvider(providerId: String): AiPlaylistModelDescriptor {
        val provider = AiPlaylistProviders.byId(providerId)
        return forProvider(provider.id).firstOrNull { it.isDefault }
            ?: forProvider(provider.id).first()
    }

    fun byApiModel(providerId: String, apiModel: String?): AiPlaylistModelDescriptor {
        val normalizedApiModel = apiModel.orEmpty().trim()
        return forProvider(providerId).firstOrNull { it.apiModel == normalizedApiModel }
            ?: defaultForProvider(providerId)
    }
}

object AiPlaylistPromptPresets {
    const val DEFAULT_ID = "balanced_day"
    const val CUSTOM_ID = "custom"

    val Balanced = AiPlaylistPromptPreset(
        id = DEFAULT_ID,
        displayName = "Сбалансированный",
        systemPrompt = "Choose a balanced daily music playlist with variety across artists, albums, eras, and energy levels. Avoid clustering too many similar tracks together.",
    )
    val Energetic = AiPlaylistPromptPreset(
        id = "energetic_mix",
        displayName = "Энергичный",
        systemPrompt = "Choose an energetic daily music playlist for movement, focus, or an active mood. Prefer tracks that look upbeat from their artist, title, and album metadata.",
    )
    val Calm = AiPlaylistPromptPreset(
        id = "calm_focus",
        displayName = "Спокойный",
        systemPrompt = "Choose a calm daily music playlist for relaxed listening or focus. Prefer tracks that look softer, smoother, or less intense from their metadata.",
    )
    val Custom = AiPlaylistPromptPreset(
        id = CUSTOM_ID,
        displayName = "Свой",
        systemPrompt = "",
        isCustom = true,
    )

    val all: List<AiPlaylistPromptPreset> = listOf(Balanced, Energetic, Calm, Custom)

    fun byId(id: String?): AiPlaylistPromptPreset =
        all.firstOrNull { it.id == id } ?: Balanced
}

fun buildAiPlaylistSystemPrompt(
    promptPresetId: String,
    customSystemPrompt: String,
    limit: Int,
): String {
    val preset = AiPlaylistPromptPresets.byId(promptPresetId)
    val stylePrompt = if (preset.isCustom) {
        customSystemPrompt.trim().takeIf { it.isNotBlank() }
            ?: AiPlaylistPromptPresets.Balanced.systemPrompt
    } else {
        preset.systemPrompt
    }

    return stylePrompt.trim() + "\n\n" +
        "You are choosing from a local music library. Use only candidate ids from the user message. " +
        "Choose up to $limit ids. Return only JSON in the exact shape {\"ids\":[1,2,3]}. " +
        "Do not include markdown, comments, explanations, titles, artists, or ids that are not in the candidates."
}
