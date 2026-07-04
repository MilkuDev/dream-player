package org.milkdev.dreamplayer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.database.DailyPlaylistGenerationMode
import org.milkdev.dreamplayer.database.SystemPlaylists
import org.milkdev.dreamplayer.database.appDatabase
import org.milkdev.dreamplayer.database.dao.MusicDao
import org.milkdev.dreamplayer.database.dao.PlaylistDao
import org.milkdev.dreamplayer.database.entities.PlaylistEntity
import org.milkdev.dreamplayer.database.entities.PlaylistTrackCrossRef
import org.milkdev.dreamplayer.database.entities.TrackEntity
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistCandidate
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistHttpException
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistRecommenderRegistry
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistRequest
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistSecretStore
import org.milkdev.dreamplayer.extensions.ai.buildAiPlaylistSystemPrompt
import org.milkdev.dreamplayer.extensions.ai.resolveRecommendedAiPlaylistSelection
import org.milkdev.dreamplayer.features.PlatformFeatureProvider

data class DailyPlaylistGenerationResult(
    val generated: Boolean,
    val mode: DailyPlaylistGenerationMode? = null,
    val trackCount: Int = 0,
    val fallbackReason: String? = null,
    val aiDebugInfo: DailyPlaylistAiDebugInfo? = null,
)

data class DailyPlaylistAiDebugInfo(
    val providerId: String,
    val model: String,
    val candidateCount: Int,
    val rawResponse: String,
    val unfilteredResponse: String,
    val parsedIds: List<Long>,
    val acceptedIds: List<Long>,
    val rejectedIds: List<Long>,
    val fallbackIds: List<Long>,
    val selectedIds: List<Long>,
    val errorMessage: String? = null,
)

class DailyPlaylistRepository(
    private val musicDao: MusicDao,
    private val playlistDao: PlaylistDao,
    private val settingsRepository: SettingsRepository
) {
    suspend fun checkAndGenerateDailyPlaylist(
        currentEpochDay: Long,
        force: Boolean = false,
    ): DailyPlaylistGenerationResult =
        withContext(Dispatchers.IO) {
            val dailyPlaylist = SystemPlaylists.DailyPlaylist
            val existingPlaylist = playlistDao.getPlaylistById(dailyPlaylist.id)
            if (existingPlaylist == null) {
                playlistDao.upsertPlaylist(
                    PlaylistEntity(
                        id = dailyPlaylist.id,
                        name = dailyPlaylist.name,
                        createdAt = dailyPlaylist.createdAt,
                        isSystem = true,
                        editable = dailyPlaylist.permissions.canEditTracks,
                    )
                )
                AppDebugLog.log("daily_playlist_created")
            }

            val state = settingsRepository.dailyPlaylistState.first()
            val lastGenerated = state.lastGenerationEpochDay
            if (!force && lastGenerated != null && currentEpochDay <= lastGenerated) {
                return@withContext DailyPlaylistGenerationResult(generated = false)
            }

            val generationMode = settingsRepository.dailyPlaylistGenerationMode.first()
            val effectiveMode = if (PlatformFeatureProvider.aiDailyPlaylistApi.enabled) {
                generationMode
            } else {
                DailyPlaylistGenerationMode.LOCAL_DAILY
            }
            val generatedPlaylist = when (effectiveMode) {
                DailyPlaylistGenerationMode.AI_API -> {
                    when (val aiAttempt = generateAiPlaylist()) {
                        is AiPlaylistAttempt.Generated -> aiAttempt.playlist
                        is AiPlaylistAttempt.Failed -> {
                            val localTracks = generateLocalPlaylistTracks()
                            GeneratedDailyPlaylist(
                                tracks = localTracks,
                                mode = DailyPlaylistGenerationMode.LOCAL_DAILY,
                                fallbackReason = aiAttempt.reason,
                                aiDebugInfo = aiAttempt.debugInfo.withLocalFallbackTracks(localTracks),
                            )
                        }
                    }
                }
                DailyPlaylistGenerationMode.LOCAL_DAILY -> GeneratedDailyPlaylist(
                    tracks = generateLocalPlaylistTracks(),
                    mode = DailyPlaylistGenerationMode.LOCAL_DAILY,
                )
            }
            val playlistTracks = generatedPlaylist.tracks

            if (playlistTracks.isEmpty()) {
                return@withContext DailyPlaylistGenerationResult(
                    generated = false,
                    mode = generatedPlaylist.mode,
                    fallbackReason = generatedPlaylist.fallbackReason,
                    aiDebugInfo = generatedPlaylist.aiDebugInfo,
                )
            }

            val crossRefs = playlistTracks.mapIndexed { index, track ->
                PlaylistTrackCrossRef(
                    playlistId = dailyPlaylist.id,
                    trackId = track.id,
                    position = index
                )
            }

            playlistDao.replacePlaylistTracks(dailyPlaylist.id, crossRefs)

            if (state.firstGenerationEpochDay == null) {
                settingsRepository.setDailyPlaylistFirstGenerationEpochDay(currentEpochDay)
            }
            settingsRepository.setDailyPlaylistLastGenerationEpochDay(currentEpochDay)

            AppDebugLog.log(
                "daily_playlist_generated requestedMode=$effectiveMode mode=${generatedPlaylist.mode} " +
                    "tracks=${playlistTracks.size} force=$force"
            )
            DailyPlaylistGenerationResult(
                generated = true,
                mode = generatedPlaylist.mode,
                trackCount = playlistTracks.size,
                fallbackReason = generatedPlaylist.fallbackReason,
                aiDebugInfo = generatedPlaylist.aiDebugInfo,
            )
        }

    private suspend fun generateLocalPlaylistTracks(): List<TrackEntity> {
        return musicDao.getRandomPresentTracks(limit = PlaylistTrackLimit)
    }

    private suspend fun generateAiPlaylist(): AiPlaylistAttempt {
        val settings = settingsRepository.aiPlaylistSettings.first()
        val provider = AiPlaylistProviders.byId(settings.providerId)
        val model = settings.model.ifBlank { provider.defaultModelId }
        val apiKey = AiPlaylistSecretStore.getApiKey(provider.id)
            ?.takeIf { it.isNotBlank() }
            ?: return AiPlaylistAttempt.Failed(
                reason = "API-ключ для провайдера ${provider.displayName} не задан",
                debugInfo = emptyAiDebugInfo(
                    providerId = provider.id,
                    model = model,
                    errorMessage = "API-ключ не задан",
                ),
            )
        val recommender = AiPlaylistRecommenderRegistry.get(provider.id)
            ?: return AiPlaylistAttempt.Failed(
                reason = "Провайдер ${provider.displayName} не зарегистрирован в AiPlaylistRecommenderRegistry",
                debugInfo = emptyAiDebugInfo(
                    providerId = provider.id,
                    model = model,
                    errorMessage = "Провайдер не найден",
                ),
            )
        val candidates = musicDao.getRandomPresentTracks(limit = AiCandidateLimit)
        if (candidates.isEmpty()) {
            return AiPlaylistAttempt.Generated(
                GeneratedDailyPlaylist(
                    tracks = emptyList(),
                    mode = DailyPlaylistGenerationMode.AI_API,
                    aiDebugInfo = emptyAiDebugInfo(
                        providerId = provider.id,
                        model = model,
                        errorMessage = "В библиотеке нет кандидатов для AI-запроса",
                    ),
                )
            )
        }

        val recommendation = runCatching {
            recommender.recommend(
                AiPlaylistRequest(
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = buildAiPlaylistSystemPrompt(
                        promptPresetId = settings.promptPresetId,
                        customSystemPrompt = settings.customSystemPrompt,
                        limit = PlaylistTrackLimit,
                    ),
                    candidates = candidates.map { it.toAiPlaylistCandidate() },
                    limit = PlaylistTrackLimit,
                )
            )
        }.onFailure { error ->
            AppDebugLog.log(
                "daily_playlist_ai_error provider=${provider.id} message=${error.message.orEmpty()}"
            )
        }.getOrElse { error ->
            val message = error.message?.takeIf { it.isNotBlank() }
                ?: error::class.simpleName
                ?: "неизвестная ошибка"
            return AiPlaylistAttempt.Failed(
                reason = "AI-запрос к ${provider.displayName} не удался: $message",
                debugInfo = emptyAiDebugInfo(
                    providerId = provider.id,
                    model = model,
                    candidateCount = candidates.size,
                    rawResponse = message,
                    unfilteredResponse = (error as? AiPlaylistHttpException)?.responseBody ?: message,
                    errorMessage = message,
                ),
            )
        }

        val selection = resolveRecommendedAiPlaylistSelection(
            candidateIds = candidates.map { it.id },
            recommendedIds = recommendation.ids,
            limit = PlaylistTrackLimit,
        )
        val byId = candidates.associateBy { it.id }
        val selectedTracks = selection.selectedIds.mapNotNull { id -> byId[id] }
        val aiDebugInfo = DailyPlaylistAiDebugInfo(
            providerId = provider.id,
            model = model,
            candidateCount = candidates.size,
            rawResponse = recommendation.rawText,
            unfilteredResponse = recommendation.rawProviderResponse,
            parsedIds = recommendation.ids,
            acceptedIds = selection.acceptedAiIds,
            rejectedIds = selection.rejectedAiIds,
            fallbackIds = selection.fallbackIds,
            selectedIds = selection.selectedIds,
        )
        AppDebugLog.log(
            "daily_playlist_ai_generated provider=${provider.id} " +
                "recommended=${recommendation.ids.size} accepted=${selection.acceptedAiIds.size} " +
                "rejected=${selection.rejectedAiIds.size} fallback=${selection.fallbackIds.size} " +
                "tracks=${selectedTracks.size}"
        )
        if (selection.acceptedAiIds.isEmpty()) {
            val reason = aiEmptySelectionReason(
                providerName = provider.displayName,
                parsedIds = recommendation.ids,
                rejectedIds = selection.rejectedAiIds,
                rawResponse = recommendation.rawText,
            )
            return AiPlaylistAttempt.Failed(
                reason = reason,
                debugInfo = aiDebugInfo.copy(errorMessage = reason),
            )
        }
        return AiPlaylistAttempt.Generated(
            GeneratedDailyPlaylist(
                tracks = selectedTracks,
                mode = DailyPlaylistGenerationMode.AI_API,
                aiDebugInfo = aiDebugInfo,
            )
        )
    }

    private fun TrackEntity.toAiPlaylistCandidate(): AiPlaylistCandidate {
        return AiPlaylistCandidate(
            id = id,
            artist = artistName,
            title = title,
            album = albumName,
        )
    }

    private companion object {
        const val PlaylistTrackLimit = 30
        const val AiCandidateLimit = 200
    }

    private data class GeneratedDailyPlaylist(
        val tracks: List<TrackEntity>,
        val mode: DailyPlaylistGenerationMode,
        val fallbackReason: String? = null,
        val aiDebugInfo: DailyPlaylistAiDebugInfo? = null,
    )

    private sealed interface AiPlaylistAttempt {
        data class Generated(val playlist: GeneratedDailyPlaylist) : AiPlaylistAttempt
        data class Failed(
            val reason: String,
            val debugInfo: DailyPlaylistAiDebugInfo,
        ) : AiPlaylistAttempt
    }
}

private fun DailyPlaylistAiDebugInfo.withLocalFallbackTracks(
    tracks: List<TrackEntity>,
): DailyPlaylistAiDebugInfo {
    val localIds = tracks.map { it.id }
    return copy(
        fallbackIds = localIds,
        selectedIds = localIds,
    )
}

private fun aiEmptySelectionReason(
    providerName: String,
    parsedIds: List<Long>,
    rejectedIds: List<Long>,
    rawResponse: String,
): String {
    val responseSnippet = rawResponse
        .replace(Regex("\\s+"), " ")
        .take(500)
        .let { snippet -> if (rawResponse.length > 500) "$snippet..." else snippet }

    return if (parsedIds.isEmpty()) {
        "AI-ответ от $providerName не содержит распознанных id: JSON не разобран или поле ids пустое. " +
            "Приложение перешло на локальную генерацию. Сырой ответ: $responseSnippet"
    } else {
        "AI-ответ от $providerName не дал ни одного пригодного id: распознано ${parsedIds.size}, " +
            "отброшено ${rejectedIds.size} как неизвестные или дубли. " +
            "Приложение перешло на локальную генерацию."
    }
}

private fun emptyAiDebugInfo(
    providerId: String,
    model: String,
    candidateCount: Int = 0,
    rawResponse: String = "",
    unfilteredResponse: String = rawResponse,
    errorMessage: String? = null,
): DailyPlaylistAiDebugInfo {
    return DailyPlaylistAiDebugInfo(
        providerId = providerId,
        model = model,
        candidateCount = candidateCount,
        rawResponse = rawResponse,
        unfilteredResponse = unfilteredResponse,
        parsedIds = emptyList(),
        acceptedIds = emptyList(),
        rejectedIds = emptyList(),
        fallbackIds = emptyList(),
        selectedIds = emptyList(),
        errorMessage = errorMessage,
    )
}

val dailyPlaylistRepository: DailyPlaylistRepository by lazy {
    DailyPlaylistRepository(
        musicDao = appDatabase.musicDao(),
        playlistDao = appDatabase.playlistDao(),
        settingsRepository = SettingsRepository
    )
}
