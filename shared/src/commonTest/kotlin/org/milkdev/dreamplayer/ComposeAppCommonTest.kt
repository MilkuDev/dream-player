package org.milkdev.dreamplayer

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistCandidate
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistModels
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistPromptPresets
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistRequest
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistResponseParser
import org.milkdev.dreamplayer.extensions.ai.buildAiPlaylistSystemPrompt
import org.milkdev.dreamplayer.extensions.ai.buildGeminiPlaylistRequestBody
import org.milkdev.dreamplayer.extensions.ai.formatAiPlaylistCandidates
import org.milkdev.dreamplayer.extensions.ai.resolveRecommendedAiPlaylistIds
import org.milkdev.dreamplayer.extensions.ai.resolveRecommendedAiPlaylistSelection
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.milkdev.dreamplayer.library.TrackSearchMode
import org.milkdev.dreamplayer.library.PlaylistRepository
import org.milkdev.dreamplayer.library.ShuffleAnchor
import org.milkdev.dreamplayer.library.filterTracksByQuery
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.features.PlatformFeatureProvider
import org.milkdev.dreamplayer.model.AppDestination
import org.milkdev.dreamplayer.model.AppNavigationState
import org.milkdev.dreamplayer.model.withNavigationTarget
import org.milkdev.dreamplayer.model.withNavigationState
import org.milkdev.dreamplayer.model.movedQueueItemOrNull
import org.milkdev.dreamplayer.model.withPlayerOpened
import org.milkdev.dreamplayer.model.withPlayerClosed
import org.milkdev.dreamplayer.model.withQueueSheetOpened
import org.milkdev.dreamplayer.model.withRepeatToggled
import org.milkdev.dreamplayer.model.withShuffleDisabled
import org.milkdev.dreamplayer.model.withShuffleEnabled
import org.milkdev.dreamplayer.playback.PlaybackRepeatMode
import org.milkdev.dreamplayer.playback.PlaybackQueueController
import org.milkdev.dreamplayer.playback.PlayerUiState
import org.milkdev.dreamplayer.playback.PlayerPresentation
import org.milkdev.dreamplayer.playback.Screen

class ComposeAppCommonTest {
    private val tracks = listOf(
        LibraryTrack(
            id = 1,
            title = "Nightcall",
            artistName = "Kavinsky",
            albumName = "Drive",
            durationMs = 261_000L,
            albumArtUri = null,
        ),
        LibraryTrack(
            id = 2,
            title = "Midnight City",
            artistName = "M83",
            albumName = "Hurry Up, We're Dreaming",
            durationMs = 244_000L,
            albumArtUri = null,
        ),
        LibraryTrack(
            id = 3,
            title = "Hello",
            artistName = "Adele",
            albumName = "25",
            durationMs = 296_000L,
            albumArtUri = null,
        ),
        LibraryTrack(
            id = 4,
            title = "Hella Good",
            artistName = "No Doubt",
            albumName = "Rock Steady",
            durationMs = 242_000L,
            albumArtUri = null,
        ),
    )

    @Test
    fun exactMatchIsCaseInsensitiveAndRanksFirst() {
        val results = filterTracksByQuery(
            tracks = tracks,
            query = "HELLO",
            config = TrackSearchMode.Title.config,
        )

        assertEquals("Hello", results.first().title)
    }

    @Test
    fun searchCanIncludeArtistWhenConfigured() {
        val artistEnabledResults = filterTracksByQuery(
            tracks = tracks,
            query = "kavinsky",
            config = TrackSearchMode.TitleAndArtist.config,
        )
        val titleOnlyResults = filterTracksByQuery(
            tracks = tracks,
            query = "kavinsky",
            config = TrackSearchMode.Title.config,
        )

        assertEquals("Nightcall", artistEnabledResults.first().title)
        assertTrue(titleOnlyResults.isEmpty())
    }

    @Test
    fun typoStillFindsClosestTrack() {
        val results = filterTracksByQuery(
            tracks = tracks,
            query = "helo",
            config = TrackSearchMode.Title.config,
        )

        assertEquals("Hello", results.first().title)
    }

    @Test
    fun combinedModeCanMatchTitleAndArtistTogether() {
        val results = filterTracksByQuery(
            tracks = tracks,
            query = "night kavinsky",
            config = TrackSearchMode.TitleAndArtist.config,
        )

        assertEquals("Nightcall", results.first().title)
    }

    @Test
    fun openingPlayerSwitchesToFullscreenWithoutChangingBaseScreen() {
        val state = PlayerUiState(
            currentScreen = Screen.Search,
            currentTrack = tracks.first(),
        )
            .withNavigationTarget(Screen.Player)

        assertEquals(Screen.Search, state.currentScreen)
        assertEquals(PlayerPresentation.Fullscreen, state.playerPresentation)
    }

    @Test
    fun closingPlayerReturnsToMiniAndClosesQueueSheet() {
        val state = PlayerUiState(
            currentScreen = Screen.Library,
            currentTrack = tracks.first(),
            playerPresentation = PlayerPresentation.Fullscreen,
            isQueueSheetVisible = true,
        ).withPlayerClosed()

        assertEquals(Screen.Library, state.currentScreen)
        assertEquals(PlayerPresentation.Mini, state.playerPresentation)
        assertEquals(false, state.isQueueSheetVisible)
    }

    @Test
    fun openingQueueSheetKeepsPlayerFullscreen() {
        val state = PlayerUiState(
            currentScreen = Screen.Home,
            currentTrack = tracks.first(),
        )
            .withPlayerOpened()
            .withQueueSheetOpened()

        assertEquals(PlayerPresentation.Fullscreen, state.playerPresentation)
        assertEquals(true, state.isQueueSheetVisible)
    }

    @Test
    fun openingPlayerWithoutCurrentTrackDoesNothing() {
        val state = PlayerUiState(currentScreen = Screen.Library)
            .withPlayerOpened()

        assertEquals(PlayerPresentation.Mini, state.playerPresentation)
        assertEquals(Screen.Library, state.currentScreen)
    }

    @Test
    fun navigationBackFromLibraryReturnsHomeThenStopsAtRoot() {
        val libraryNavigation = AppNavigationState()
            .navigateTo(AppDestination.Library)

        assertContentEquals(
            listOf(AppDestination.Home, AppDestination.Library),
            libraryNavigation.backStack,
        )
        assertEquals(true, libraryNavigation.canNavigateBack)

        val homeNavigation = libraryNavigation.navigateBack()

        assertNotNull(homeNavigation)
        assertContentEquals(listOf(AppDestination.Home), homeNavigation.backStack)
        assertEquals(false, homeNavigation.canNavigateBack)
        assertNull(homeNavigation.navigateBack())
    }

    @Test
    fun navigationBackFromAiDebugReturnsSettingsThenHome() {
        val debugNavigation = AppNavigationState()
            .navigateTo(AppDestination.Settings)
            .navigateTo(AppDestination.AiDebugSettings)

        assertContentEquals(
            listOf(
                AppDestination.Home,
                AppDestination.Settings,
                AppDestination.AiDebugSettings,
            ),
            debugNavigation.backStack,
        )

        val settingsNavigation = debugNavigation.navigateBack()
        assertNotNull(settingsNavigation)
        assertEquals(AppDestination.Settings, settingsNavigation.currentDestination)
        assertEquals(Screen.Settings, PlayerUiState().withNavigationState(settingsNavigation).currentScreen)

        val homeNavigation = settingsNavigation.navigateBack()
        assertNotNull(homeNavigation)
        assertEquals(AppDestination.Home, homeNavigation.currentDestination)
        assertEquals(false, homeNavigation.canNavigateBack)
    }

    @Test
    fun navigationBackFromQueueReturnsPlayerThenUnderlyingPlaylist() {
        val queueNavigation = AppNavigationState()
            .navigateTo(AppDestination.Library)
            .navigateTo(AppDestination.PlaylistDetails)
            .navigateTo(AppDestination.Player, hasCurrentTrack = true)
            .navigateTo(AppDestination.Queue, hasCurrentTrack = true)

        val queueState = PlayerUiState(currentTrack = tracks.first())
            .withNavigationState(queueNavigation)

        assertEquals(Screen.PlaylistDetails, queueState.currentScreen)
        assertEquals(PlayerPresentation.Fullscreen, queueState.playerPresentation)
        assertEquals(true, queueState.isQueueSheetVisible)

        val playerNavigation = queueNavigation.navigateBack()
        assertNotNull(playerNavigation)
        val playerState = queueState.withNavigationState(playerNavigation)

        assertEquals(Screen.PlaylistDetails, playerState.currentScreen)
        assertEquals(PlayerPresentation.Fullscreen, playerState.playerPresentation)
        assertEquals(false, playerState.isQueueSheetVisible)

        val playlistNavigation = playerNavigation.navigateBack()
        assertNotNull(playlistNavigation)
        val playlistState = playerState.withNavigationState(playlistNavigation)

        assertEquals(Screen.PlaylistDetails, playlistState.currentScreen)
        assertEquals(PlayerPresentation.Mini, playlistState.playerPresentation)
        assertEquals(false, playlistState.isQueueSheetVisible)
    }

    @Test
    fun navigationBackFromSearchReturnsToOpeningScreen() {
        val homeSearchNavigation = AppNavigationState()
            .navigateTo(AppDestination.Search)
        val librarySearchNavigation = AppNavigationState()
            .navigateTo(AppDestination.Library)
            .navigateTo(AppDestination.Search)

        assertEquals(Screen.Search, PlayerUiState().withNavigationState(homeSearchNavigation).currentScreen)
        assertEquals(
            Screen.Home,
            PlayerUiState().withNavigationState(homeSearchNavigation.navigateBack()!!).currentScreen,
        )
        assertEquals(
            Screen.Library,
            PlayerUiState().withNavigationState(librarySearchNavigation.navigateBack()!!).currentScreen,
        )
    }

    @Test
    fun shuffledQueueReturnsNullForEmptyPlaylist() {
        val queue = PlaylistRepository.prepareShuffledQueue(emptyList())

        assertNull(queue)
    }

    @Test
    fun shuffledQueueCanKeepSelectedTrackFirst() {
        val selectedTrack = tracks[2]

        val queue = PlaylistRepository.prepareShuffledQueue(
            tracks = tracks,
            selectedTrackId = selectedTrack.id,
            anchor = ShuffleAnchor.KeepSelectedTrackFirst,
            random = Random(42),
        )

        assertNotNull(queue)
        assertEquals(0, queue.startIndex)
        assertEquals(selectedTrack, queue.tracks.first())
        assertContentEquals(
            expected = tracks.map { it.id }.sorted(),
            actual = queue.tracks.map { it.id }.sorted(),
        )
    }

    @Test
    fun enablingShuffleMovesCurrentTrackToFirstQueuePosition() {
        val state = PlayerUiState(
            playbackQueue = tracks,
            currentTrack = tracks[2],
            currentQueueIndex = 2,
        )

        val shuffledState = state.withShuffleEnabled(random = Random(42))

        assertNotNull(shuffledState)
        assertEquals(true, shuffledState.isShuffleEnabled)
        assertEquals(0, shuffledState.currentQueueIndex)
        assertEquals(tracks[2], shuffledState.playbackQueue.first())
        assertContentEquals(
            expected = tracks.map { it.id }.sorted(),
            actual = shuffledState.playbackQueue.map { it.id }.sorted(),
        )
    }

    @Test
    fun disablingShuffleRestoresOriginalQueueOrder() {
        val shuffledState = PlayerUiState(
            playbackQueue = listOf(tracks[2], tracks[0], tracks[3], tracks[1]),
            currentTrack = tracks[2],
            currentQueueIndex = 0,
            isShuffleEnabled = true,
        )

        val restoredState = shuffledState.withShuffleDisabled(originalQueue = tracks)

        assertEquals(false, restoredState.isShuffleEnabled)
        assertEquals(2, restoredState.currentQueueIndex)
        assertContentEquals(
            expected = tracks.map { it.id },
            actual = restoredState.playbackQueue.map { it.id },
        )
    }

    @Test
    fun disablingShuffleKeepsCurrentQueueWhenOriginalQueueIsStale() {
        val shuffledState = PlayerUiState(
            playbackQueue = listOf(tracks[2], tracks[0], tracks[3], tracks[1]),
            currentTrack = tracks[2],
            currentQueueIndex = 0,
            isShuffleEnabled = true,
        )
        val staleOriginalQueue = listOf(tracks[0], tracks[1], tracks[3])

        val restoredState = shuffledState.withShuffleDisabled(originalQueue = staleOriginalQueue)

        assertEquals(false, restoredState.isShuffleEnabled)
        assertContentEquals(
            expected = shuffledState.playbackQueue.map { it.id },
            actual = restoredState.playbackQueue.map { it.id },
        )
    }

    @Test
    fun repeatModeCyclesFromOffToQueueToOneAndBackOff() {
        val queueRepeatState = PlayerUiState().withRepeatToggled()
        val oneRepeatState = queueRepeatState.withRepeatToggled()
        val offRepeatState = oneRepeatState.withRepeatToggled()

        assertEquals(PlaybackRepeatMode.Queue, queueRepeatState.repeatMode)
        assertEquals(PlaybackRepeatMode.One, oneRepeatState.repeatMode)
        assertEquals(PlaybackRepeatMode.Off, offRepeatState.repeatMode)
    }

    @Test
    fun movingQueueItemReturnsPlaybackOrderOnly() {
        val movedQueue = tracks.movedQueueItemOrNull(fromIndex = 0, toIndex = 2)

        assertNotNull(movedQueue)
        assertContentEquals(
            expected = listOf(2L, 3L, 1L, 4L),
            actual = movedQueue.map { it.id },
        )
    }

    @Test
    fun movingQueueItemIgnoresNoOpAndInvalidIndices() {
        assertNull(tracks.movedQueueItemOrNull(fromIndex = 1, toIndex = 1))
        assertNull(tracks.movedQueueItemOrNull(fromIndex = -1, toIndex = 1))
        assertNull(tracks.movedQueueItemOrNull(fromIndex = 1, toIndex = tracks.size))
    }

    @Test
    fun playbackQueueCanChangeWithoutMutatingLibraryTracks() {
        val state = PlayerUiState(
            tracks = tracks,
            playbackQueue = listOf(tracks[2], tracks[0], tracks[1]),
            currentTrack = tracks[2],
            currentQueueIndex = 0,
        )

        val movedQueue = state.playbackQueue.movedQueueItemOrNull(fromIndex = 2, toIndex = 1)
        assertNotNull(movedQueue)
        val updatedState = state.copy(playbackQueue = movedQueue)

        assertContentEquals(
            expected = listOf(1L, 2L, 3L, 4L),
            actual = updatedState.tracks.map { it.id },
        )
        assertContentEquals(
            expected = listOf(3L, 2L, 1L),
            actual = updatedState.playbackQueue.map { it.id },
        )
    }

    @Test
    fun queueControllerTracksVersionAndCurrentTrack() {
        val controller = PlaybackQueueController()

        val initial = controller.setQueue(longArrayOf(1L, 2L, 3L), startIndex = 1)
        val next = controller.skipToNext()

        assertNotNull(next)
        assertEquals(1L, initial.queueVersion)
        assertEquals(2L, initial.currentTrackId)
        assertEquals(initial.queueVersion, next.queueVersion)
        assertEquals(3L, next.currentTrackId)
    }

    @Test
    fun queueControllerShuffleKeepsCurrentTrackFirstAndUnshuffleRestoresIndex() {
        val controller = PlaybackQueueController()
        controller.setQueue(longArrayOf(1L, 2L, 3L, 4L), startIndex = 2)

        val shuffled = controller.shuffle(random = Random(42))
        assertNotNull(shuffled)
        assertEquals(3L, shuffled.currentTrackId)
        assertEquals(0, shuffled.currentIndex)

        val restored = controller.unshuffle()
        assertEquals(3L, restored.currentTrackId)
        assertEquals(2, restored.currentIndex)
        assertContentEquals(longArrayOf(1L, 2L, 3L, 4L), restored.trackIds)
    }

    @Test
    fun queueControllerMoveKeepsCurrentTrackStable() {
        val controller = PlaybackQueueController()
        controller.setQueue(longArrayOf(1L, 2L, 3L, 4L), startIndex = 2)

        val moved = controller.move(fromIndex = 0, toIndex = 3)

        assertNotNull(moved)
        assertEquals(3L, moved.currentTrackId)
        assertEquals(1, moved.currentIndex)
        assertContentEquals(longArrayOf(2L, 3L, 4L, 1L), moved.trackIds)
    }

    @Test
    fun queueControllerCanApplyPrecomputedShuffleWithoutLosingOriginalOrder() {
        val controller = PlaybackQueueController()
        val originalIds = longArrayOf(1L, 2L, 3L, 4L)
        val initial = controller.setQueue(originalIds, startIndex = 2)

        val shuffled = controller.replaceActiveOrder(
            expectedQueueVersion = initial.queueVersion,
            orderedTrackIds = longArrayOf(3L, 1L, 4L, 2L),
            currentTrackId = 3L,
            shuffleEnabled = true,
            updateOriginalOrder = false,
        )

        assertNotNull(shuffled)
        assertEquals(3L, shuffled.currentTrackId)
        assertEquals(0, shuffled.currentIndex)
        assertContentEquals(longArrayOf(3L, 1L, 4L, 2L), shuffled.trackIds)

        val restored = controller.unshuffle()
        assertEquals(3L, restored.currentTrackId)
        assertEquals(2, restored.currentIndex)
        assertContentEquals(originalIds, restored.trackIds)
    }

    @Test
    fun queueControllerRejectsStaleReplaceRequests() {
        val controller = PlaybackQueueController()
        val initial = controller.setQueue(longArrayOf(1L, 2L, 3L), startIndex = 1)
        controller.move(fromIndex = 0, toIndex = 2)

        val staleReplace = controller.replaceActiveOrder(
            expectedQueueVersion = initial.queueVersion,
            orderedTrackIds = longArrayOf(2L, 3L, 1L),
            currentTrackId = 2L,
            shuffleEnabled = true,
            updateOriginalOrder = false,
        )

        assertNull(staleReplace)
    }

    @Test
    fun queueControllerRejectsReplaceWhenCurrentTrackChanged() {
        val controller = PlaybackQueueController()
        val initial = controller.setQueue(longArrayOf(1L, 2L, 3L), startIndex = 1)
        controller.skipToNext()

        val staleReplace = controller.replaceActiveOrder(
            expectedQueueVersion = initial.queueVersion,
            orderedTrackIds = longArrayOf(2L, 1L, 3L),
            currentTrackId = 2L,
            shuffleEnabled = true,
            updateOriginalOrder = false,
        )

        assertNull(staleReplace)
        assertEquals(3L, controller.snapshot().currentTrackId)
    }

    @Test
    fun desktopAiPlaylistFeatureIsDisabledForJvmTarget() {
        assertEquals(false, PlatformFeatureProvider.aiDailyPlaylistApi.enabled)
        assertNotNull(PlatformFeatureProvider.aiDailyPlaylistApi.reason)
    }

    @Test
    fun aiCandidateFormatterUsesCompactTsvWithoutDuration() {
        val formatted =
            formatAiPlaylistCandidates(
                candidates = listOf(
                    AiPlaylistCandidate(
                        id = 10,
                        artist = "Artist\tName",
                        title = "Title\nName",
                        album = "Album\rName",
                    ),
                )
            )

        assertEquals("10\tArtist Name\tTitle Name\tAlbum Name", formatted)
        assertEquals(3, formatted.count { it == '\t' })
    }

    @Test
    fun aiCandidateFormatterLimitsCandidateCount() {
        val formatted =
            formatAiPlaylistCandidates(
                candidates = listOf(
                    AiPlaylistCandidate(
                        1,
                        "a",
                        "t1",
                        "x"
                    ),
                    AiPlaylistCandidate(
                        2,
                        "a",
                        "t2",
                        "x"
                    ),
                    AiPlaylistCandidate(
                        3,
                        "a",
                        "t3",
                        "x"
                    ),
                ),
                limit = 2,
            )

        assertEquals(listOf("1", "2"), formatted.lines().map { it.substringBefore('\t') })
    }

    @Test
    fun aiModelCatalogFiltersByProviderAndFallsBackToProviderDefault() {
        val geminiModels = AiPlaylistModels.forProvider(
            AiPlaylistProviders.Gemini.id)
        val fallback = AiPlaylistModels.byApiModel(
            providerId = AiPlaylistProviders.Gemini.id,
            apiModel = "old-or-unknown-model",
        )

        assertTrue(geminiModels.all { it.providerId == AiPlaylistProviders.Gemini.id })
        assertEquals("Gemini 3.5 Flash", fallback.displayName)
        assertEquals("gemini-3.5-flash", fallback.apiModel)
    }

    @Test
    fun aiModelCatalogKeepsDisplayNameSeparateFromApiModel() {
        val model = AiPlaylistModels.byApiModel(
            providerId = AiPlaylistProviders.DeepSeek.id,
            apiModel = "deepseek-v4-flash",
        )

        assertEquals("DeepSeek V4 Flash", model.displayName)
        assertEquals("deepseek-v4-flash", model.apiModel)
    }

    @Test
    fun aiSystemPromptCombinesPresetWithJsonContract() {
        val prompt =
            buildAiPlaylistSystemPrompt(
                promptPresetId = AiPlaylistPromptPresets.Energetic.id,
                customSystemPrompt = "",
                limit = 30,
            )

        assertTrue(prompt.contains("energetic", ignoreCase = true))
        assertTrue(prompt.contains("Choose up to 30 ids"))
        assertTrue(prompt.contains("""{"ids":[1,2,3]}"""))
    }

    @Test
    fun aiSystemPromptUsesCustomTextWhenCustomPresetSelected() {
        val prompt =
            buildAiPlaylistSystemPrompt(
                promptPresetId = AiPlaylistPromptPresets.CustomId,
                customSystemPrompt = "Pick sleepy synthwave only.",
                limit = 12,
            )

        assertTrue(prompt.startsWith("Pick sleepy synthwave only."))
        assertTrue(prompt.contains("Choose up to 12 ids"))
    }

    @Test
    fun geminiPlaylistRequestUsesMinimalThinkingAndStructuredJson() {
        val requestBody =
            buildGeminiPlaylistRequestBody(
                AiPlaylistRequest(
                    apiKey = "secret",
                    model = "gemini-3.5-flash",
                    systemPrompt = "Choose energetic tracks only.",
                    candidates = listOf(
                        AiPlaylistCandidate(
                            10,
                            "Noisestorm",
                            "Surge",
                            "Monstercat"
                        ),
                        AiPlaylistCandidate(
                            20,
                            "Tokyo Machine",
                            "PLAY",
                            "Monstercat"
                        ),
                    ),
                    limit = 30,
                )
            )

        val generationConfig = requestBody["generationConfig"]!!.jsonObject
        val thinkingConfig = generationConfig["thinkingConfig"]!!.jsonObject
        val schema = generationConfig["responseJsonSchema"]!!.jsonObject
        val idsSchema = schema["properties"]!!
            .jsonObject["ids"]!!
            .jsonObject
        val userText = requestBody["contents"]!!
            .jsonArray[0]
            .jsonObject["parts"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive
            .content
        val systemText = requestBody["systemInstruction"]!!
            .jsonObject["parts"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive
            .content

        assertEquals("minimal", thinkingConfig["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals("application/json", generationConfig["responseMimeType"]!!.jsonPrimitive.content)
        assertFalse("responseFormat" in generationConfig)
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertContentEquals(
            listOf("ids"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("array", idsSchema["type"]!!.jsonPrimitive.content)
        assertEquals("30", idsSchema["maxItems"]!!.jsonPrimitive.content)
        assertEquals("integer", idsSchema["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Choose energetic tracks only.", systemText)
        assertTrue(userText.contains("Tracks are TSV rows"))
        assertTrue(userText.contains("10\tNoisestorm\tSurge\tMonstercat"))
        assertFalse(userText.contains("Choose energetic tracks only."))
    }

    @Test
    fun aiResponseParserAcceptsCleanJsonObject() {
        val ids = AiPlaylistResponseParser.parseTrackIds("""{"ids":[1,2,3]}""")

        assertContentEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun aiResponseParserAcceptsLegacyTrackIdsAndMarkdownFence() {
        val ids = AiPlaylistResponseParser.parseTrackIds(
            """
            ```json
            {"trackIds":[4,5,6]}
            ```
            """.trimIndent()
        )

        assertContentEquals(listOf(4L, 5L, 6L), ids)
    }

    @Test
    fun aiResponseParserExtractsJsonFromNoisyTextAndArrays() {
        val objectIds = AiPlaylistResponseParser.parseTrackIds(
            "Sure, here is the playlist: {\"ids\":[7,8]} Enjoy."
        )
        val arrayIds = AiPlaylistResponseParser.parseTrackIds("```[9,10]```")

        assertContentEquals(listOf(7L, 8L), objectIds)
        assertContentEquals(listOf(9L, 10L), arrayIds)
    }

    @Test
    fun aiResponseParserReturnsEmptyListForBrokenPayload() {
        val ids = AiPlaylistResponseParser.parseTrackIds("not json {\"ids\":[1,")

        assertTrue(ids.isEmpty())
    }

    @Test
    fun recommendedAiPlaylistIdsDropDuplicatesInvalidIdsAndFillLocally() {
        val ids =
            resolveRecommendedAiPlaylistIds(
                candidateIds = listOf(1, 2, 3, 4, 5),
                recommendedIds = listOf(3, 99, 3, 1),
                limit = 4,
            )

        assertContentEquals(listOf(3L, 1L, 2L, 4L), ids)
    }

    @Test
    fun recommendedAiPlaylistSelectionReportsAcceptedRejectedAndFallbackIds() {
        val selection =
            resolveRecommendedAiPlaylistSelection(
                candidateIds = listOf(1, 2, 3, 4, 5),
                recommendedIds = listOf(3, 99, 3, 1),
                limit = 4,
            )

        assertContentEquals(listOf(3L, 1L, 2L, 4L), selection.selectedIds)
        assertContentEquals(listOf(3L, 1L), selection.acceptedAiIds)
        assertContentEquals(listOf(99L, 3L), selection.rejectedAiIds)
        assertContentEquals(listOf(2L, 4L), selection.fallbackIds)
    }

    @Test
    fun recommendedAiPlaylistIdsFallbackToLocalOrderWhenResponseIsEmpty() {
        val ids =
           resolveRecommendedAiPlaylistIds(
                candidateIds = listOf(1, 2, 3),
                recommendedIds = emptyList(),
                limit = 2,
            )

        assertContentEquals(listOf(1L, 2L), ids)
    }
}
