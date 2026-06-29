@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.extensions.secrets

expect object LastFmSecretStore {
    val supportsSecrets: Boolean
    suspend fun isApiKeyConfigured(): Boolean
    suspend fun getApiKey(): String?
    suspend fun setApiKey(apiKey: String)
    suspend fun clearApiKey()
}
