package org.milkdev.dreamplayer.extensions.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import org.milkdev.org.milkdev.dreamplayer.database.settingsDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.collections.remove
import kotlin.text.get
import kotlin.text.set

internal object AndroidEncryptedSecretStore {
    suspend fun getString(key: Preferences.Key<String>): String? {
        val encodedPayload = settingsDataStore.data.first()[key] ?: return null
        val payload = runCatching {
            Base64.decode(encodedPayload, Base64.NO_WRAP)
        }.getOrNull() ?: return null

        return runCatching {
            decrypt(payload).decodeToString()
        }.getOrNull()
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        val trimmedValue = value.trim()
        if (trimmedValue.isBlank()) {
            clearString(key)
            return
        }

        val payload = encrypt(trimmedValue.encodeToByteArray())
        settingsDataStore.edit { preferences ->
            preferences[key] set Base64.encodeToString(payload, Base64.NO_WRAP)
        }
    }

    suspend fun clearString(key: Preferences.Key<String>) {
        settingsDataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    private fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(bytes)
        val iv = cipher.iv

        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) error("Empty encrypted payload")

        val ivSize = payload.first().toInt()
        if (ivSize <= 0 || payload.size <= ivSize + 1) {
            error("Invalid encrypted payload")
        }

        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply {
            load(null)
        }
        val existingKey = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
        if (existingKey != null) return existingKey

        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    private const val AndroidKeyStore = "AndroidKeyStore"
    private const val KEY_ALIAS = "DreamPlayerAiPlaylistApiKeys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_SIZE_BITS = 128
}
