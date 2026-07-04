package org.milkdev.dreamplayer.extensions.ai

actual object AiPlaylistSecretStore {
    actual val supportsSecrets: Boolean
        get() = TODO("Not yet implemented")

    actual suspend fun isApiKeyConfigured(providerId: String): Boolean {
        TODO("Not yet implemented")
    }

    actual suspend fun getApiKey(providerId: String): String? {
        TODO("Not yet implemented")
    }

    actual suspend fun setApiKey(providerId: String, apiKey: String) {
    }

    actual suspend fun clearApiKey(providerId: String) {
    }
}