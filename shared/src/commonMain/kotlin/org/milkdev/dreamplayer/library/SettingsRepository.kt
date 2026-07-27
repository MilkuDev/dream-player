package org.milkdev.dreamplayer.library

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.IO
import org.milkdev.dreamplayer.database.settingsDataStore
import androidx.datastore.preferences.core.intPreferencesKey
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

// Playback state persistence
private val KEY_LAST_QUEUE_TRACK_IDS = stringPreferencesKey("last_queue_track_ids")
private val KEY_LAST_QUEUE_SHUFFLED_IDS = stringPreferencesKey("last_queue_shuffled_ids")
private val KEY_LAST_QUEUE_INDEX = intPreferencesKey("last_queue_index")
private val KEY_LAST_TRACK_ID = longPreferencesKey("last_track_id")
private val KEY_LAST_TRACK_POSITION_MS = longPreferencesKey("last_track_position_ms")
private val KEY_LAST_SHUFFLE_ENABLED = booleanPreferencesKey("last_shuffle_enabled")
private val KEY_LAST_REPEAT_MODE = stringPreferencesKey("last_repeat_mode")

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

    // ── Playback state persistence ──────────────────────────────────────────────

    data class SavedPlaybackState(
        val queueTrackIds: List<Long>,
        val queueShuffledIds: List<Long>?,
        val queueIndex: Int,
        val currentTrackId: Long?,
        val trackPositionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: String,
    )

    suspend fun savePlaybackState(state: SavedPlaybackState) {
        settingsDataStore.edit { preferences ->
            preferences[KEY_LAST_QUEUE_TRACK_IDS] = state.queueTrackIds.joinToString(",")
            preferences[KEY_LAST_QUEUE_SHUFFLED_IDS] = state.queueShuffledIds
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(",")
                .orEmpty()
            preferences[KEY_LAST_QUEUE_INDEX] = state.queueIndex
            val currentTrackId = state.currentTrackId
            if (currentTrackId == null) {
                preferences.remove(KEY_LAST_TRACK_ID)
            } else {
                preferences[KEY_LAST_TRACK_ID] = currentTrackId
            }
            preferences[KEY_LAST_TRACK_POSITION_MS] = state.trackPositionMs.coerceAtLeast(0L)
            preferences[KEY_LAST_SHUFFLE_ENABLED] = state.shuffleEnabled
            preferences[KEY_LAST_REPEAT_MODE] = state.repeatMode
        }
    }

    suspend fun saveTrackPositionOnly(trackId: Long, positionMs: Long) {
        settingsDataStore.edit { preferences ->
            if (preferences[KEY_LAST_TRACK_ID] == trackId) {
                preferences[KEY_LAST_TRACK_POSITION_MS] = positionMs.coerceAtLeast(0L)
            }
        }
    }

    /**
     * Читает сохранённое состояние плеера из DataStore (синхронно, [Dispatchers.IO]).
     * Если ключа нет — возвращает null.
     */
    suspend fun restorePlaybackState(): SavedPlaybackState? {
        return settingsDataStore.data.map { preferences ->
            val trackIds = preferences[KEY_LAST_QUEUE_TRACK_IDS]
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                ?: return@map null

            val shuffledIds = preferences[KEY_LAST_QUEUE_SHUFFLED_IDS]
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }

            val queueIndex = preferences[KEY_LAST_QUEUE_INDEX] ?: 0
            val shuffleEnabled = preferences[KEY_LAST_SHUFFLE_ENABLED] ?: false
            val activeIds = if (shuffleEnabled && !shuffledIds.isNullOrEmpty()) {
                shuffledIds
            } else {
                trackIds
            }

            SavedPlaybackState(
                queueTrackIds = trackIds,
                queueShuffledIds = shuffledIds?.takeIf { it.isNotEmpty() },
                queueIndex = queueIndex,
                currentTrackId = preferences[KEY_LAST_TRACK_ID]
                    ?: activeIds.getOrNull(queueIndex),
                trackPositionMs = preferences[KEY_LAST_TRACK_POSITION_MS] ?: 0L,
                shuffleEnabled = shuffleEnabled,
                repeatMode = preferences[KEY_LAST_REPEAT_MODE] ?: "Off",
            )
        }.first()
    }

    suspend fun clearPlaybackState() {
        settingsDataStore.edit { preferences ->
            preferences.remove(KEY_LAST_QUEUE_TRACK_IDS)
            preferences.remove(KEY_LAST_QUEUE_SHUFFLED_IDS)
            preferences.remove(KEY_LAST_QUEUE_INDEX)
            preferences.remove(KEY_LAST_TRACK_ID)
            preferences.remove(KEY_LAST_TRACK_POSITION_MS)
            preferences.remove(KEY_LAST_SHUFFLE_ENABLED)
            preferences.remove(KEY_LAST_REPEAT_MODE)
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
