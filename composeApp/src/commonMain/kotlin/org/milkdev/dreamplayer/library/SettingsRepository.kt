package org.milkdev.dreamplayer.library

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.IO
import org.milkdev.dreamplayer.database.settingsDataStore
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistModels
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistPromptPresets
import org.milkdev.dreamplayer.database.DailyPlaylistGenerationMode
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistSettings
import org.milkdev.dreamplayer.features.PlatformFeatureProvider

private val KEY_DAILY_PLAYLIST_FIRST_GENERATION_EPOCH_DAY =
    longPreferencesKey("daily_playlist_first_generation_epoch_day")

private val KEY_DAILY_PLAYLIST_LAST_GENERATION_EPOCH_DAY =
    longPreferencesKey("daily_playlist_last_generation_epoch_day")

private val KEY_DAILY_PLAYLIST_GENERATION_MODE =
    stringPreferencesKey("daily_playlist_generation_mode")

private val KEY_DAILY_PLAYLIST_AI_PROVIDER_ID =
    stringPreferencesKey("daily_playlist_ai_provider_id")

private val KEY_DAILY_PLAYLIST_AI_MODEL =
    stringPreferencesKey("daily_playlist_ai_model")

private val KEY_DAILY_PLAYLIST_AI_PROMPT_PRESET_ID =
    stringPreferencesKey("daily_playlist_ai_prompt_preset_id")

private val KEY_DAILY_PLAYLIST_AI_CUSTOM_SYSTEM_PROMPT =
    stringPreferencesKey("daily_playlist_ai_custom_system_prompt")

private val KEY_BLUR_ENABLED = booleanPreferencesKey("blur_enabled")
private val KEY_FORCE_NIGHT_MODE = booleanPreferencesKey("force_night_mode")

object SettingsRepository {
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val dailyPlaylistState: Flow<DailyPlaylistState> = settingsDataStore.data
        .map { preferences ->
            DailyPlaylistState(
                firstGenerationEpochDay = preferences[KEY_DAILY_PLAYLIST_FIRST_GENERATION_EPOCH_DAY],
                lastGenerationEpochDay = preferences[KEY_DAILY_PLAYLIST_LAST_GENERATION_EPOCH_DAY]
            )
        }

    val dailyPlaylistGenerationMode: Flow<DailyPlaylistGenerationMode> = settingsDataStore.data
        .map { preferences ->
            preferences[KEY_DAILY_PLAYLIST_GENERATION_MODE].toDailyPlaylistGenerationMode()
        }

    val aiPlaylistSettings: Flow<AiPlaylistSettings> = settingsDataStore.data
        .map { preferences ->
            val provider = AiPlaylistProviders.byId(preferences[KEY_DAILY_PLAYLIST_AI_PROVIDER_ID])
            val model = AiPlaylistModels.byApiModel(
                providerId = provider.id,
                apiModel = preferences[KEY_DAILY_PLAYLIST_AI_MODEL],
            )
            val promptPreset = AiPlaylistPromptPresets.byId(
                preferences[KEY_DAILY_PLAYLIST_AI_PROMPT_PRESET_ID],
            )
            AiPlaylistSettings(
                providerId = provider.id,
                model = model.apiModel,
                promptPresetId = promptPreset.id,
                customSystemPrompt = preferences[KEY_DAILY_PLAYLIST_AI_CUSTOM_SYSTEM_PROMPT].orEmpty(),
            )
        }

    fun setDailyPlaylistGenerationMode(mode: DailyPlaylistGenerationMode) {
        storeScope.launch {
            val effectiveMode = if (PlatformFeatureProvider.aiDailyPlaylistApi.enabled) {
                mode
            } else {
                DailyPlaylistGenerationMode.LOCAL_DAILY
            }
            settingsDataStore.edit { preferences ->
                preferences[KEY_DAILY_PLAYLIST_GENERATION_MODE] = effectiveMode.name
            }
        }
    }

    fun setAiPlaylistProviderId(providerId: String) {
        if (!PlatformFeatureProvider.aiDailyPlaylistApi.enabled) return

        val provider = AiPlaylistProviders.byId(providerId)
        storeScope.launch {
            settingsDataStore.edit { preferences ->
                preferences[KEY_DAILY_PLAYLIST_AI_PROVIDER_ID] = provider.id
                preferences[KEY_DAILY_PLAYLIST_AI_MODEL] = AiPlaylistModels.defaultForProvider(provider.id).apiModel
            }
        }
    }

    fun setAiPlaylistModel(model: String) {
        if (!PlatformFeatureProvider.aiDailyPlaylistApi.enabled) return

        storeScope.launch {
            settingsDataStore.edit { preferences ->
                val provider = AiPlaylistProviders.byId(preferences[KEY_DAILY_PLAYLIST_AI_PROVIDER_ID])
                preferences[KEY_DAILY_PLAYLIST_AI_MODEL] =
                    AiPlaylistModels.byApiModel(provider.id, model).apiModel
            }
        }
    }

    fun setAiPlaylistPromptPreset(promptPresetId: String) {
        if (!PlatformFeatureProvider.aiDailyPlaylistApi.enabled) return

        val promptPreset = AiPlaylistPromptPresets.byId(promptPresetId)
        storeScope.launch {
            settingsDataStore.edit { preferences ->
                preferences[KEY_DAILY_PLAYLIST_AI_PROMPT_PRESET_ID] = promptPreset.id
            }
        }
    }

    fun setAiPlaylistCustomSystemPrompt(prompt: String) {
        if (!PlatformFeatureProvider.aiDailyPlaylistApi.enabled) return

        storeScope.launch {
            settingsDataStore.edit { preferences ->
                preferences[KEY_DAILY_PLAYLIST_AI_CUSTOM_SYSTEM_PROMPT] = prompt
            }
        }
    }

    fun setDailyPlaylistFirstGenerationEpochDay(epochDay: Long) {
        storeScope.launch {
            settingsDataStore.edit { preferences ->
                preferences[KEY_DAILY_PLAYLIST_FIRST_GENERATION_EPOCH_DAY] = epochDay
            }
        }
    }

    fun setDailyPlaylistLastGenerationEpochDay(epochDay: Long) {
        storeScope.launch {
            settingsDataStore.edit { preferences ->
                preferences[KEY_DAILY_PLAYLIST_LAST_GENERATION_EPOCH_DAY] = epochDay
            }
        }
    }

    val isBlurEnabled: Flow<Boolean> = settingsDataStore.data
        .map { preferences -> preferences[KEY_BLUR_ENABLED] ?: true }

    val isForceNightMode: Flow<Boolean> = settingsDataStore.data
        .map { preferences -> preferences[KEY_FORCE_NIGHT_MODE] ?: false }

    fun setBlurEnabled(enabled: Boolean) {
        storeScope.launch {
            settingsDataStore.edit { preferences ->
                preferences[KEY_BLUR_ENABLED] = enabled
            }
        }
    }

    fun setForceNightMode(enabled: Boolean) {
        storeScope.launch {
            settingsDataStore.edit { preferences ->
                preferences[KEY_FORCE_NIGHT_MODE] = enabled
            }
        }
    }

    private fun String?.toDailyPlaylistGenerationMode(): DailyPlaylistGenerationMode {
        return when (this) {
            DailyPlaylistGenerationMode.AI_API.name,
            "AI_ONLY" -> DailyPlaylistGenerationMode.AI_API
            else -> DailyPlaylistGenerationMode.LOCAL_DAILY
        }
    }
}
