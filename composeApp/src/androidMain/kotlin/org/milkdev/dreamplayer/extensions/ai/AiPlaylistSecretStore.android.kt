@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.extensions.ai

import androidx.datastore.preferences.core.stringPreferencesKey
import org.milkdev.dreamplayer.extensions.secrets.AndroidEncryptedSecretStore

actual object AiPlaylistSecretStore {
    actual val supportsSecrets: Boolean = true // TODO

    actual suspend fun isApiKeyConfigured(providerId: String): Boolean {
        return getApiKey(providerId)?.isNotBlank() == true
    }

    actual suspend fun getApiKey(providerId: String): String? {
        return AndroidEncryptedSecretStore.getString(providerKey(providerId))
    }

    actual suspend fun setApiKey(providerId: String, apiKey: String) {
        AndroidEncryptedSecretStore.setString(providerKey(providerId), apiKey)
    }

    actual suspend fun clearApiKey(providerId: String) {
        AndroidEncryptedSecretStore.clearString(providerKey(providerId))
    }

    private fun providerKey(providerId: String) =
        stringPreferencesKey("ai_api_key_${providerId
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }}")
}
