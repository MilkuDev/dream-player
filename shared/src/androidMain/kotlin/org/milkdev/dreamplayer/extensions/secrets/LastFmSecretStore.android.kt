@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.extensions.secrets

import androidx.datastore.preferences.core.stringPreferencesKey

actual object LastFmSecretStore {
    actual val supportsSecrets: Boolean = true

    actual suspend fun isApiKeyConfigured(): Boolean {
        return getApiKey()?.isNotBlank() == true
    }

    actual suspend fun getApiKey(): String? {
        return AndroidEncryptedSecretStore.getString(ApiKey)
    }

    actual suspend fun setApiKey(apiKey: String) {
        AndroidEncryptedSecretStore.setString(ApiKey, apiKey)
    }

    actual suspend fun clearApiKey() {
        AndroidEncryptedSecretStore.clearString(ApiKey)
    }

    private val ApiKey = stringPreferencesKey("lastfm_api_key")
}
