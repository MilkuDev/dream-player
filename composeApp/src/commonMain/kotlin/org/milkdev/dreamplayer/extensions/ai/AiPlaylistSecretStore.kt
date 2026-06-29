@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.extensions.ai

expect object AiPlaylistSecretStore {
    val supportsSecrets: Boolean

    suspend fun isApiKeyConfigured(providerId: String): Boolean

    suspend fun getApiKey(providerId: String): String?

    suspend fun setApiKey(providerId: String, apiKey: String)

    suspend fun clearApiKey(providerId: String)
}
