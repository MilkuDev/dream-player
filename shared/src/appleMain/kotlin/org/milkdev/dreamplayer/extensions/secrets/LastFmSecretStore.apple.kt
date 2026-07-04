@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.extensions.secrets

actual object LastFmSecretStore {
    actual val supportsSecrets: Boolean
        get() = TODO("Not yet implemented")

    actual suspend fun isApiKeyConfigured(): Boolean {
        TODO("Not yet implemented")
    }

    actual suspend fun getApiKey(): String? {
        TODO("Not yet implemented")
    }

    actual suspend fun setApiKey(apiKey: String) {
    }

    actual suspend fun clearApiKey() {
    }
}