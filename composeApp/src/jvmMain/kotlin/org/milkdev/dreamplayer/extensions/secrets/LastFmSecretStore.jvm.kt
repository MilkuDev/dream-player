@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.extensions.secrets

actual object LastFmSecretStore {
    actual val supportsSecrets: Boolean = false

    actual suspend fun isApiKeyConfigured(): Boolean = false

    actual suspend fun getApiKey(): String? = null

    actual suspend fun setApiKey(apiKey: String) = Unit

    actual suspend fun clearApiKey() = Unit
}
