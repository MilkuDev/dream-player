@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.extensions.ai

actual object AiPlaylistSecretStore {
    actual val supportsSecrets: Boolean = false

    actual suspend fun isApiKeyConfigured(providerId: String): Boolean = false

    actual suspend fun getApiKey(providerId: String): String? = null

    actual suspend fun setApiKey(providerId: String, apiKey: String) = Unit

    actual suspend fun clearApiKey(providerId: String) = Unit
}
