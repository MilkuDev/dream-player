package org.milkdev.dreamplayer.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.milkdev.dreamplayer.database.DailyPlaylistGenerationMode
import org.milkdev.dreamplayer.database.SystemPlaylists
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistCandidate
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistHttpException
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistPromptPresets
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistRecommenderRegistry
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistRequest
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistSecretStore
import org.milkdev.dreamplayer.extensions.ai.buildAiPlaylistSystemPrompt
import org.milkdev.dreamplayer.extensions.network.AiNetworkDiagnosticsService
import org.milkdev.dreamplayer.extensions.network.AiPromptService
import org.milkdev.dreamplayer.extensions.network.LastFmApiTestStatus
import org.milkdev.dreamplayer.extensions.network.LastFmNetworkDiagnosticsService
import org.milkdev.dreamplayer.extensions.network.NetworkDiagnosticResult
import org.milkdev.dreamplayer.extensions.network.NetworkDiagnosticStatus
import org.milkdev.dreamplayer.extensions.network.NetworkHosts
import org.milkdev.dreamplayer.extensions.secrets.LastFmSecretStore
import org.milkdev.dreamplayer.features.PlatformFeatureProvider
import org.milkdev.dreamplayer.library.AlbumListItem
import org.milkdev.dreamplayer.library.ArtistListItem
import org.milkdev.dreamplayer.library.GenreListItem
import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.library.DailyPlaylistAiDebugInfo
import org.milkdev.dreamplayer.library.DailyPlaylistGenerationResult
import org.milkdev.dreamplayer.library.LibraryPageCursor
import org.milkdev.dreamplayer.library.MusicLibrarySource
import org.milkdev.dreamplayer.library.PlaylistRepository
import org.milkdev.dreamplayer.library.SettingsRepository
import org.milkdev.dreamplayer.library.ShuffleAnchor
import org.milkdev.dreamplayer.library.UserPlaylist
import org.milkdev.dreamplayer.library.dailyPlaylistRepository
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainPage
import org.milkdev.dreamplayer.playback.AudioPlayer
import org.milkdev.dreamplayer.playback.DailyPlaylistUiState
import org.milkdev.dreamplayer.playback.PlaybackQueueController
import org.milkdev.dreamplayer.playback.PlaybackQueueSnapshot
import org.milkdev.dreamplayer.playback.PlaybackRepeatMode
import org.milkdev.dreamplayer.playback.PlaybackResolver
import org.milkdev.dreamplayer.playback.PlaybackSnapshot
import org.milkdev.dreamplayer.playback.SavePointWorker
import org.milkdev.dreamplayer.playback.LibraryUiState
import org.milkdev.dreamplayer.playback.PlaybackUiState
import org.milkdev.dreamplayer.playback.ResolvedPlaybackItem
import org.milkdev.dreamplayer.playback.SettingsUiState
import org.milkdev.dreamplayer.playback.TrackAvailability
import org.milkdev.dreamplayer.playback.matchesQueue
import org.milkdev.dreamplayer.playback.movedCopy
import org.milkdev.dreamplayer.playback.shuffledWithCurrentFirst
import kotlin.random.Random
import kotlin.time.Clock

private const val PageSize = 60

class PlayerViewModel {
    private val storeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _libraryState = MutableStateFlow(LibraryUiState())
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsUiState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val _navigationState = MutableStateFlow(AppNavigationState())
    val navigationState: StateFlow<AppNavigationState> = _navigationState.asStateFlow()

    private val aiDailyPlaylistFeature = PlatformFeatureProvider.aiDailyPlaylistApi
    private val aiNetworkDiagnosticsService = AiNetworkDiagnosticsService()
    private val aiPromptService = AiPromptService()
    private val lastFmNetworkDiagnosticsService = LastFmNetworkDiagnosticsService()
    private val playbackQueueController = PlaybackQueueController()
    private val detailEntryStore = DetailEntryStore()
    private var dailyPlaylistGenerationJob: Job? = null
    private var detailContentJob: Job? = null
    private var searchPageLoadJob: Job? = null
    private var searchRequestGeneration: Long = 0L
    private var checkedDailyPlaylistEpochDay: Long? = null
    private var trackPageCursor: LibraryPageCursor? = null
    private var albumPageCursor: LibraryPageCursor? = null
    private var artistPageCursor: LibraryPageCursor? = null
    private var genrePageCursor: LibraryPageCursor? = null
    private var searchPageCursor: LibraryPageCursor? = null
    private var playlistPickerPageCursor: LibraryPageCursor? = null
    private var restoreAttempted = false
    private var pendingResumePositionMs: Long = 0L
    private val currentNavigationState: AppNavigationState
        get() = _navigationState.value

    private val savePointWorker = SavePointWorker(
        scope = storeScope,
        audioPlayerState = AudioPlayer.state,
        playbackTimeSource = AudioPlayer.playbackTimeSource,
    )

    init {
        _settingsState.update {
            it.copy(
                aiDailyPlaylistFeature = aiDailyPlaylistFeature,
                lastFmSettings = it.lastFmSettings.copy(
                    supportsSecrets = LastFmSecretStore.supportsSecrets,
                ),
            )
        }

        refreshLibrarySummary()
        reloadLibraryPages()

        storeScope.launch {
            PlaylistRepository.ensureSystemPlaylists()
        }

        storeScope.launch {
            MusicLibrarySource.dailyPlaylistTracks.collect { tracks ->
                _libraryState.update { currentState ->
                    currentState.copy(
                        dailyPlaylistState = if (tracks.isEmpty()) {
                            DailyPlaylistUiState.Empty
                        } else {
                            DailyPlaylistUiState.Available(tracks)
                        },
                        selectedPlaylistTracks = if (currentState.selectedPlaylist?.id == SystemPlaylists.DailyPlaylist.id) {
                            tracks
                        } else {
                            currentState.selectedPlaylistTracks
                        },
                    )
                }
            }
        }

        storeScope.launch {
            PlaylistRepository.visiblePlaylists.collect { playlists ->
                _libraryState.update { currentState ->
                    val selectedPlaylist = currentState.selectedPlaylist?.let { selected ->
                        playlists.firstOrNull { it.id == selected.id } ?: selected
                    }
                    currentState.copy(
                        playlists = playlists,
                        selectedPlaylist = selectedPlaylist,
                    )
                }
            }
        }

        storeScope.launch {
            AudioPlayer.state.collect { playbackState ->
                val queueControllerSnapshotBefore = playbackQueueController.snapshot()

                if (playbackState.queue.queueVersion == queueControllerSnapshotBefore.queueVersion) {
                    playbackQueueController.skipToIndex(playbackState.queue.currentIndex)
                }

                val currentQueueSnapshot = playbackQueueController.snapshot()

                val previousTrackId = _playbackState.value.currentTrack?.id
                val newTrackId = currentQueueSnapshot.currentTrackId
                if (previousTrackId != null && newTrackId != null && previousTrackId != newTrackId) {
                    savePlaybackState()
                }

                val queueTracks = when {
                    currentQueueSnapshot.trackIds.isEmpty() -> emptyList()
                    currentNavigationState.currentDestination == AppRoute.Queue -> {
                        withContext(Dispatchers.Default) {
                            _playbackState.value.findTracksForIds(currentQueueSnapshot.trackIds)
                        }
                    }
                    else -> _playbackState.value.playbackQueue
                }

                val currentTrack = currentQueueSnapshot.currentTrackId?.let { trackId ->
                    queueTracks.firstOrNull { it.id == trackId }
                        ?: _playbackState.value.currentTrack?.takeIf { it.id == trackId }
                }

                val resolvedDurationMs = playbackState.totalDurationMs
                    .takeIf { it > 0L }
                    ?: currentTrack?.durationMs
                    ?: 0L

                _playbackState.update { currentState ->
                    currentState.copy(
                        currentTrack = currentTrack,
                        isPlaying = playbackState.isPlaying,
                        totalDurationMs = resolvedDurationMs,
                        playbackQueue = queueTracks,
                        currentQueueIndex = currentQueueSnapshot.currentIndex,
                        queueVersion = currentQueueSnapshot.queueVersion,
                    )
                }

                if (currentTrack == null) {
                    publishNavigationState(currentNavigationState.removePlaybackOverlays())
                }
            }
        }

        storeScope.launch {
            MusicLibrarySource.getRecentlyPlayedTracks().collect { tracks ->
                _libraryState.update { it.copy(recentlyPlayedTracks = tracks) }
            }
        }

        storeScope.launch {
            val randomGenre = MusicLibrarySource.getRandomGenreWithTracks()
            if (randomGenre != null) {
                _libraryState.update { it.copy(homeGenreTitle = randomGenre.name) }
                MusicLibrarySource.getTracksByGenre(randomGenre.id).collect { tracks ->
                    _libraryState.update { it.copy(homeGenreTracks = tracks) }
                }
            }
        }

        storeScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            AudioPlayer.state
                .map { it.currentTrackId }
                .distinctUntilChanged()
                .collect { trackId ->
                    if (trackId != null && !_libraryState.value.recentlyPlayedTracks.any { it.id == trackId }) {
                        MusicLibrarySource.addTrackToHistory(trackId)
                    }
                }
        }

        storeScope.launch {
            SettingsRepository.isBlurEnabled.collect { enabled ->
                _settingsState.update { it.copy(isBlurEnabled = enabled) }
            }
        }

        storeScope.launch {
            SettingsRepository.isForceNightMode.collect { enabled ->
                _settingsState.update { it.copy(isForceNightMode = enabled) }
            }
        }

        storeScope.launch {
            SettingsRepository.dailyPlaylistGenerationMode.collect { mode ->
                val effectiveMode = if (aiDailyPlaylistFeature.enabled) {
                    mode
                } else {
                    DailyPlaylistGenerationMode.LOCAL_DAILY
                }
                _settingsState.update {
                    it.copy(dailyPlaylistGenerationMode = effectiveMode)
                }
            }
        }

        storeScope.launch {
            SettingsRepository.aiPlaylistSettings.collect { settings ->
                if (!aiDailyPlaylistFeature.enabled) {
                    _settingsState.update {
                        it.copy(
                            aiPlaylistProviderId = AiPlaylistProviders.OpenAi.id,
                            aiPlaylistModel = AiPlaylistProviders.OpenAi.defaultModelId,
                            aiPlaylistPromptPresetId = AiPlaylistPromptPresets.DEFAULT_ID,
                            aiPlaylistCustomSystemPrompt = "",
                            isAiPlaylistApiKeyConfigured = false,
                            isAnyAiPlaylistApiKeyConfigured = false,
                            aiPlaylistApiTestStatus = null,
                        )
                    }
                    return@collect
                }

                val provider = AiPlaylistProviders.byId(settings.providerId)
                _settingsState.update {
                    it.copy(
                        aiPlaylistProviderId = provider.id,
                        aiPlaylistModel = settings.model.ifBlank { provider.defaultModelId },
                        aiPlaylistPromptPresetId = settings.promptPresetId,
                        aiPlaylistCustomSystemPrompt = settings.customSystemPrompt,
                        aiPlaylistApiTestStatus = null,
                    )
                }
                refreshAiPlaylistApiKeyConfigured(provider.id)
            }
        }

        storeScope.launch {
            refreshLastFmApiKeyConfigured()
        }

        storeScope.launch {
            MusicLibrarySource.metadataSyncState.collect { syncState ->
                _settingsState.update { state ->
                    state.copy(
                        lastFmSettings = state.lastFmSettings.copy(
                            isMetadataSyncing = syncState.isSyncing,
                            pendingCount = syncState.pendingCount,
                            coverPendingCount = syncState.coverPendingCount,
                            lastFmPendingCount = syncState.lastFmPendingCount,
                            processedCount = syncState.processedCount,
                            lastMetadataSyncMessage = syncState.message,
                        )
                    )
                }
            }
        }
        savePointWorker.start()
        storeScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            AudioPlayer.state
                .map { it.currentTrackId }
                .distinctUntilChanged()
                .flatMapLatest { trackId ->
                    if (trackId == null) {
                        flowOf(false)
                    } else {
                        PlaylistRepository.observeIsFavorite(trackId)
                    }
                }
                .collect { isFavorite ->
                    _playbackState.update { it.copy(isCurrentTrackFavorite = isFavorite) }
                }
        }
    }

    fun onLibraryIntent(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.SelectCategory -> _libraryState.update { it.copy(currentCategory = intent.category) }
            is LibraryIntent.ChangeTrackSort -> setTrackSortOrder(intent.order)
            is LibraryIntent.ChangeAlbumSort -> setAlbumSortOrder(intent.order)
            is LibraryIntent.LoadNextTracks -> loadNextTracksPage()
            is LibraryIntent.LoadNextAlbums -> loadNextAlbumsPage()
            is LibraryIntent.LoadNextArtists -> loadNextArtistsPage()
            is LibraryIntent.LoadNextGenres -> loadNextGenresPage()

            is LibraryIntent.OpenAlbum -> openAlbumDetails(intent.album)
            is LibraryIntent.OpenArtist -> openArtistDetails(intent.artist)
            is LibraryIntent.OpenGenre -> openGenreDetails(intent.genre)
            is LibraryIntent.OpenPlaylist -> openPlaylist(intent.playlist)
            is LibraryIntent.CreatePlaylist -> createPlaylist(intent.name)
            is LibraryIntent.PlayTrack -> playFromLibrary(intent.track.id)
        }
    }

    fun setTrackSortOrder(order: TrackSortOrder) {
        if (_libraryState.value.trackSortOrder == order) return
        _libraryState.update { it.copy(trackSortOrder = order) }
        if (order == TrackSortOrder.GENRE) {
            loadNextGenresPage(reset = true)
        } else {
            loadNextTracksPage(reset = true)
        }
    }

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        if (_libraryState.value.albumSortOrder == order) return
        _libraryState.update { it.copy(albumSortOrder = order) }
        if (order == AlbumSortOrder.GENRE) {
            loadNextGenresPage(reset = true)
        } else {
            loadNextAlbumsPage(reset = true)
        }
    }

    fun loadNextTracksPage(reset: Boolean = false) {
        if (_libraryState.value.isTrackPageLoading) return
        if (!reset && !_libraryState.value.hasMoreTracks) return

        if (reset) {
            trackPageCursor = null
        }

        _libraryState.update { it.copy(isTrackPageLoading = true) }

        storeScope.launch {
            try {
                val order = _libraryState.value.trackSortOrder
                val page = withContext(Dispatchers.IO) {
                    MusicLibrarySource.getTrackPage(
                        order = order,
                        cursor = trackPageCursor,
                        limit = PageSize,
                    )
                }

                trackPageCursor = page.nextCursor
                _libraryState.update {
                    it.copy(
                        trackListItems = if (reset) page.items else it.trackListItems + page.items,
                        hasMoreTracks = page.hasMore,
                    )
                }
            } catch (e: Exception) {
                AppDebugLog.log("Error loading tracks: ${e.message}")
            } finally {
                _libraryState.update { it.copy(isTrackPageLoading = false) }
            }
        }
    }

    fun loadNextAlbumsPage(reset: Boolean = false) {
        if (_libraryState.value.isAlbumPageLoading) return
        if (!reset && !_libraryState.value.hasMoreAlbums) return

        if (reset) {
            albumPageCursor = null
        }

        _libraryState.update { it.copy(isAlbumPageLoading = true) }

        storeScope.launch {
            try {
                val order = _libraryState.value.albumSortOrder
                val page = withContext(Dispatchers.IO) {
                    MusicLibrarySource.getAlbumPage(
                        order = order,
                        cursor = albumPageCursor,
                        limit = PageSize,
                    )
                }

                albumPageCursor = page.nextCursor
                _libraryState.update {
                    it.copy(
                        albumListItems = if (reset) page.items else it.albumListItems + page.items,
                        hasMoreAlbums = page.hasMore,
                    )
                }
            } catch (e: Exception) {
                AppDebugLog.log("Error loading albums: ${e.message}")
            } finally {
                _libraryState.update { it.copy(isAlbumPageLoading = false) }
            }
        }
    }

    fun loadNextArtistsPage(reset: Boolean = false) {
        if (_libraryState.value.isArtistPageLoading) return
        if (!reset && !_libraryState.value.hasMoreArtists) return

        if (reset) {
            artistPageCursor = null
        }

        _libraryState.update { it.copy(isArtistPageLoading = true) }

        storeScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    MusicLibrarySource.getArtistPage(
                        cursor = artistPageCursor,
                        limit = PageSize,
                    )
                }

                artistPageCursor = page.nextCursor
                _libraryState.update {
                    it.copy(
                        artistListItems = if (reset) page.items else it.artistListItems + page.items,
                        hasMoreArtists = page.hasMore,
                    )
                }
            } catch (e: Exception) {
                AppDebugLog.log("Error loading artists: ${e.message}")
            } finally {
                _libraryState.update { it.copy(isArtistPageLoading = false) }
            }
        }
    }

    fun loadNextGenresPage(reset: Boolean = false) {
        if (_libraryState.value.isGenrePageLoading) return
        if (!reset && !_libraryState.value.hasMoreGenres) return

        if (reset) {
            genrePageCursor = null
        }

        _libraryState.update { it.copy(isGenrePageLoading = true) }

        storeScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    MusicLibrarySource.getGenrePage(
                        cursor = genrePageCursor,
                        limit = PageSize,
                    )
                }

                genrePageCursor = page.nextCursor
                _libraryState.update {
                    it.copy(
                        genreListItems = if (reset) page.items else it.genreListItems + page.items,
                        hasMoreGenres = page.hasMore,
                    )
                }
            } catch (e: Exception) {
                AppDebugLog.log("Error loading genres: ${e.message}")
            } finally {
                _libraryState.update { it.copy(isGenrePageLoading = false) }
            }
        }
    }

    fun loadPlaylistPickerPage(reset: Boolean = false) {
        if (_libraryState.value.isPlaylistPickerPageLoading) return
        if (!reset && !_libraryState.value.hasMorePlaylistPickerTracks) return

        if (reset) {
            playlistPickerPageCursor = null
        }

        _libraryState.update { it.copy(isPlaylistPickerPageLoading = true) }

        storeScope.launch {
            try {
                val order = TrackSortOrder.TRACK_NAME
                val page = withContext(Dispatchers.IO) {
                    MusicLibrarySource.getTrackPage(
                        order = order,
                        cursor = playlistPickerPageCursor,
                        limit = PageSize,
                    )
                }

                playlistPickerPageCursor = page.nextCursor
                _libraryState.update {
                    it.copy(
                        playlistPickerTrackItems = if (reset) page.items else it.playlistPickerTrackItems + page.items,
                        hasMorePlaylistPickerTracks = page.hasMore,
                    )
                }
            } catch (e: Exception) {
                AppDebugLog.log("Error loading playlist picker: ${e.message}")
            } finally {
                _libraryState.update { it.copy(isPlaylistPickerPageLoading = false) }
            }
        }
    }
    private fun reloadLibraryPages() {
        storeScope.launch {
            _libraryState.update { it.copy(isLoading = true) }
            
            coroutineScope {
                launch { loadNextTracksPage(reset = true) }
                launch { loadNextAlbumsPage(reset = true) }
                launch { loadNextArtistsPage(reset = true) }
                launch { loadNextGenresPage(reset = true) }
                launch { loadPlaylistPickerPage(reset = true) }
            }

            _libraryState.update { it.copy(isLoading = false) }
        }
    }

    private fun refreshLibrarySummary() {
        storeScope.launch {
            val summary = withContext(Dispatchers.IO) {
                MusicLibrarySource.getLibrarySummary()
            }
            _libraryState.update { it.copy(librarySummary = summary) }
            _settingsState.update { state ->
                state.copy(
                    lastFmSettings = state.lastFmSettings.copy(
                        pendingCount = summary.pendingMetadataCount,
                        coverPendingCount = summary.pendingMetadataCount,
                    ),
                )
            }
            scheduleDailyPlaylistGeneration(summary.trackCount)
        }
    }

    fun selectMainPage(page: MainPage) {
        publishNavigationState(currentNavigationState.selectMainPage(page))
    }

    fun openSettings() {
        publishNavigationState(currentNavigationState.push(AppRoute.Settings))
    }

    fun openAiDebugSettings() {
        publishNavigationState(currentNavigationState.push(AppRoute.AiDebugSettings))
    }

    fun navigateBack(): Boolean {
        val nextNavigationState = currentNavigationState.pop() ?: return false
        AppDebugLog.log(
            "navigate_back from=${currentNavigationState.currentDestination} " +
                "to=${nextNavigationState.currentDestination}"
        )
        publishNavigationState(nextNavigationState)
        return true
    }

    fun openPlayer() {
        AppDebugLog.log("open_player tracks=${_libraryState.value.librarySummary.trackCount}")
        if (_playbackState.value.currentTrack == null) return
        publishNavigationState(currentNavigationState.push(AppRoute.Player))
    }

    fun openQueueSheet() {
        AppDebugLog.log("open_queue_sheet tracks=${_playbackState.value.playbackQueue.size}")
        if (_playbackState.value.currentTrack == null) return
        val nextNavigationState = currentNavigationState.push(AppRoute.Queue)
        if (nextNavigationState === currentNavigationState) return
        publishNavigationState(nextNavigationState)
        refreshQueueDisplay(playbackQueueController.snapshot())
    }

    fun openPlaylist(playlist: UserPlaylist) {
        AppDebugLog.log("open_playlist id=${playlist.id}")
        openDetail(PlaylistDetailDescriptor(playlist))
    }

    fun openAlbumDetails(album: AlbumListItem) {
        AppDebugLog.log("open_album_details id=${album.id}")

        openDetail(
            LibraryCollectionDetailDescriptor(
                type = LibraryCollectionType.ALBUM,
                collectionId = album.id,
                title = album.title,
                subtitle = "${album.artistName}${album.year?.let { year -> " • $year" } ?: ""}",
                artworkUri = album.artworkUri,
            ),
        )
    }

    fun openDailyPlaylist() {
        val dailyPlaylist = SystemPlaylists.DailyPlaylist
        AppDebugLog.log("open_system_playlist id=${dailyPlaylist.id}")

        val selectedPlaylist = UserPlaylist(
            id = dailyPlaylist.id,
            name = dailyPlaylist.name,
            createdAt = dailyPlaylist.createdAt,
            isSystem = true,
            canEditTracks = dailyPlaylist.permissions.canEditTracks,
            canRename = dailyPlaylist.permissions.canRename,
        )

        openDetail(PlaylistDetailDescriptor(selectedPlaylist))
    }

    fun createPlaylist(name: String) {
        storeScope.launch {
            val playlistId = PlaylistRepository.createUserPlaylist(name) ?: return@launch
            AppDebugLog.log("playlist_created id=$playlistId")
        }
    }

    fun savePlaylistTracks(playlistId: Long, trackIds: List<Long>) {
        val systemPlaylist = SystemPlaylists.byId(playlistId)
        if (systemPlaylist != null && !systemPlaylist.permissions.canEditTracks) return

        storeScope.launch {
            PlaylistRepository.replacePlaylistTracks(playlistId, trackIds)
            AppDebugLog.log("playlist_tracks_saved id=$playlistId tracks=${trackIds.distinct().size}")
        }
    }

    fun favorite() {
        val track = _playbackState.value.currentTrack ?: return
        val currentlyFavorite = _playbackState.value.isCurrentTrackFavorite

        storeScope.launch {
            if (currentlyFavorite) {
                val removed = PlaylistRepository.removeTrackFromFavorites(track.id)
                AppDebugLog.log(
                    "favorite_track_removed track=${track.id} playlist=${SystemPlaylists.Favorites.id} removed=$removed"
                )
            } else {
                val added = PlaylistRepository.addTrackToFavorites(track.id)
                AppDebugLog.log(
                    "favorite_track_added track=${track.id} playlist=${SystemPlaylists.Favorites.id} added=$added"
                )
            }
        }
    }

    fun openLibrarySearch() {
        _libraryState.update { it.copy(librarySearch = it.librarySearch.copy(isActive = true)) }
        publishNavigationState(currentNavigationState.openSearch())
        loadNextSearchPage(reset = true)
    }

    fun closeLibrarySearch() {
        publishNavigationState(currentNavigationState.closeSearch())
    }

    fun updateLibrarySearchQuery(query: String) {
        _libraryState.update { currentState ->
            currentState.copy(
                librarySearch = currentState.librarySearch.copy(query = query),
            )
        }
        loadNextSearchPage(reset = true)
    }

    fun loadNextSearchPage(reset: Boolean = false) {
        val currentState = _libraryState.value
        if (!reset && currentState.isSearchPageLoading) return
        if (!reset && !currentState.hasMoreSearchTracks) return

        if (reset) {
            searchPageLoadJob?.cancel()
            searchRequestGeneration += 1L
            searchPageCursor = null
        }
        val requestGeneration = searchRequestGeneration
        val requestedCursor = searchPageCursor
        searchPageLoadJob = storeScope.launch {
            _libraryState.update {
                it.copy(
                    isSearchPageLoading = true,
                    searchTrackListItems = if (reset) emptyList() else it.searchTrackListItems,
                    hasMoreSearchTracks = if (reset) true else it.hasMoreSearchTracks,
                )
            }
            val searchState = _libraryState.value
            val page = MusicLibrarySource.searchTrackPage(
                query = searchState.librarySearch.query,
                mode = searchState.librarySearch.mode,
                cursor = requestedCursor,
                limit = PageSize,
            )
            if (
                requestGeneration != searchRequestGeneration ||
                !currentNavigationState.contains(AppRoute.Search)
            ) {
                return@launch
            }
            searchPageCursor = page.nextCursor
            _libraryState.update {
                it.copy(
                    searchTrackListItems = if (reset) page.items else it.searchTrackListItems + page.items,
                    hasMoreSearchTracks = page.hasMore,
                    isSearchPageLoading = false,
                )
            }
        }
    }

    fun openArtistDetails(artist: ArtistListItem) {
        AppDebugLog.log("open_artist_details id=${artist.id}")

        openDetail(
            LibraryCollectionDetailDescriptor(
                type = LibraryCollectionType.ARTIST,
                collectionId = artist.id,
                title = artist.name,
                subtitle = "${artist.albumCount} альбомов • ${artist.trackCount} треков",
                artworkUri = artist.artworkUri,
            ),
        )
    }

    fun openGenreDetails(genre: GenreListItem) {
        AppDebugLog.log("open_genre_details id=${genre.id}")

        openDetail(
            LibraryCollectionDetailDescriptor(
                type = LibraryCollectionType.GENRE,
                collectionId = genre.id,
                title = genre.name,
                subtitle = "${genre.albumCount} альбомов • ${genre.trackCount} треков",
                artworkUri = null,
            ),
        )
    }

    suspend fun loadLibrary() {
        _libraryState.update { it.copy(isLoading = true, error = null) }
        try {
            MusicLibrarySource.loadTracks()
            refreshLibrarySummary()
            reloadLibraryPages()
            restorePlaybackState()
            _libraryState.update { it.copy(isLoading = false) }
        } catch (e: Exception) {
            _libraryState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            AppDebugLog.log("library_load_error message=${e.message.orEmpty()}")
        }
    }

    fun shuffleDailyPlaylist() {
        val dailyTracks = (_libraryState.value.dailyPlaylistState as? DailyPlaylistUiState.Available)
            ?.tracks
            .orEmpty()

        if (dailyTracks.isEmpty()) return

        val preparedQueue = PlaylistRepository.prepareShuffledQueue(
            tracks = dailyTracks,
        ) ?: return

        playPreparedQueue(
            tracks = preparedQueue.tracks,
            startIndex = preparedQueue.startIndex,
        )
    }

    fun playFromLibrary(trackId: Long) {
        storeScope.launch {
            val order = _libraryState.value.trackSortOrder
            val allIds = MusicLibrarySource.getAllTrackIds(order)
            val startIndex = allIds.indexOf(trackId).coerceAtLeast(0)

            val snapshot = playbackQueueController.setQueue(
                trackIds = allIds,
                startIndex = startIndex,
            )
            _playbackState.update {
                it.copy(
                    currentQueueIndex = snapshot.currentIndex,
                    queueVersion = snapshot.queueVersion,
                    isShuffleEnabled = false,
                )
            }
            applyQueueSnapshot(snapshot, PlaybackSnapshotApplyMode.Play)
        }
    }

    fun playFromVisibleTracks(tracks: List<LibraryTrack>, track: LibraryTrack) {
        val trackIndex = tracks.indexOfFirst { it.id == track.id }
        if (trackIndex >= 0) {
            playPreparedQueue(tracks, trackIndex)
        } else {
            playPreparedQueue(listOf(track), 0)
        }
    }

    fun playFromSelectedPlaylist(track: LibraryTrack) {
        playFromVisibleTracks(_libraryState.value.selectedPlaylistTracks, track)
    }

    fun playFromSelectedLibraryCollection(track: LibraryTrack) {
        playFromVisibleTracks(_libraryState.value.selectedLibraryCollection?.tracks.orEmpty(), track)
    }

    fun playQueueItem(index: Int) {
        val snapshot = playbackQueueController.skipToIndex(index) ?: return
        applyQueueSnapshot(snapshot, PlaybackSnapshotApplyMode.Play)
    }

    fun pause() {
        AudioPlayer.pause()
        _playbackState.update { it.copy(isPlaying = false) }
        savePlaybackState()
    }

    fun resume() {
        if (AudioPlayer.state.value.currentTrackId == null) {
            val snapshot = playbackQueueController.snapshot()
            if (snapshot.trackIds.isNotEmpty()) {
                val restoredPosition = pendingResumePositionMs
                pendingResumePositionMs = 0L
                applyQueueSnapshot(
                    snapshot,
                    PlaybackSnapshotApplyMode.Play,
                    startPositionMs = restoredPosition,
                )
                return
            }
            return
        }
        AudioPlayer.resume()
        _playbackState.update { it.copy(isPlaying = true) }
    }

    fun playPrevious() {
        val snapshot = playbackQueueController.skipToPrevious() ?: return
        applyQueueSnapshot(snapshot, PlaybackSnapshotApplyMode.Play)
    }

    fun playNext() {
        val snapshot = playbackQueueController.skipToNext() ?: return
        applyQueueSnapshot(snapshot, PlaybackSnapshotApplyMode.Play)
    }

    fun seekTo(positionMs: Long) {
        AudioPlayer.seekTo(positionMs)
    }

    fun toggleShuffle() {
        storeScope.launch {
            val baseSnapshot = playbackQueueController.snapshot()
            val currentTrackId = baseSnapshot.currentTrackId ?: return@launch
            val wasShuffleEnabled = playbackQueueController.isShuffleEnabled
            val originalIds = if (wasShuffleEnabled) {
                playbackQueueController.originalTrackIdsSnapshot()
            } else {
                null
            }
            val shouldResolveDisplayQueue = currentNavigationState.currentDestination == AppRoute.Queue

            val orderedIds = withContext(Dispatchers.Default) {
                originalIds ?: baseSnapshot.trackIds.shuffledWithCurrentFirst(currentTrackId)
            }
            val snapshot = playbackQueueController.replaceActiveOrder(
                expectedQueueVersion = baseSnapshot.queueVersion,
                orderedTrackIds = orderedIds,
                currentTrackId = currentTrackId,
                shuffleEnabled = !wasShuffleEnabled,
                updateOriginalOrder = false,
            ) ?: return@launch

            _playbackState.update {
                it.copy(
                    isShuffleEnabled = playbackQueueController.isShuffleEnabled,
                    currentQueueIndex = snapshot.currentIndex,
                    queueVersion = snapshot.queueVersion,
                )
            }
            applyQueueSnapshot(
                queueSnapshot = snapshot,
                mode = PlaybackSnapshotApplyMode.Update,
                shuffleApplyMode = if (shouldResolveDisplayQueue) {
                    ShuffleApplyMode.QueueVisible
                } else {
                    ShuffleApplyMode.PlaybackOnly
                },
            )
            AppDebugLog.log(
                "shuffle_toggle enabled=${playbackQueueController.isShuffleEnabled} tracks=${snapshot.trackIds.size}"
            )
            savePlaybackState()
        }
    }

    fun toggleRepeat() {
        _playbackState.update { it.copy(repeatMode = it.repeatMode.next()) }
        AudioPlayer.setRepeatMode(_playbackState.value.repeatMode)
        savePlaybackState()
        AppDebugLog.log("repeat_toggle mode=${_playbackState.value.repeatMode}")
    }

    fun moveTrack(fromIndex: Int, toIndex: Int) {
        storeScope.launch {
            val baseSnapshot = playbackQueueController.snapshot()
            val currentTrackId = baseSnapshot.currentTrackId
            val wasShuffleEnabled = playbackQueueController.isShuffleEnabled
            val movedIds = withContext(Dispatchers.Default) {
                baseSnapshot.trackIds.movedCopy(fromIndex, toIndex)
            } ?: return@launch

            val snapshot = playbackQueueController.replaceActiveOrder(
                expectedQueueVersion = baseSnapshot.queueVersion,
                orderedTrackIds = movedIds,
                currentTrackId = currentTrackId,
                shuffleEnabled = wasShuffleEnabled,
                updateOriginalOrder = wasShuffleEnabled,
            ) ?: return@launch

            AppDebugLog.log("queue_move from=$fromIndex to=$toIndex tracks=${snapshot.trackIds.size}")
            applyQueueSnapshot(
                queueSnapshot = snapshot,
                mode = PlaybackSnapshotApplyMode.Move,
                moveRequest = QueueMoveRequest(fromIndex, toIndex),
                shuffleApplyMode = ShuffleApplyMode.QueueVisible,
            )
        }
    }

    fun clearQueue() {
        playbackQueueController.clear()
        AudioPlayer.stop()
        _playbackState.update {
            it.copy(
                currentTrack = null,
                isPlaying = false,
                totalDurationMs = 0L,
                playbackQueue = emptyList(),
                currentQueueIndex = -1,
                queueVersion = playbackQueueController.snapshot().queueVersion,
                isShuffleEnabled = false,
            )
        }
        publishNavigationState(currentNavigationState.removePlaybackOverlays())
        SettingsRepository.clearPlaybackState()
    }

    fun setBlurEnabled(enabled: Boolean) {
        SettingsRepository.setBlurEnabled(enabled)
    }

    fun setForceNightMode(enabled: Boolean) {
        SettingsRepository.setForceNightMode(enabled)
    }

    fun setDailyPlaylistGenerationMode(mode: DailyPlaylistGenerationMode) {
        if (!aiDailyPlaylistFeature.enabled && mode == DailyPlaylistGenerationMode.AI_API) return
        SettingsRepository.setDailyPlaylistGenerationMode(mode)
    }

    fun setAiPlaylistProvider(providerId: String) {
        if (!aiDailyPlaylistFeature.enabled) return
        SettingsRepository.setAiPlaylistProviderId(providerId)
    }

    fun setAiPlaylistModel(model: String) {
        if (!aiDailyPlaylistFeature.enabled) return
        SettingsRepository.setAiPlaylistModel(model)
    }

    fun setAiPlaylistPromptPreset(promptPresetId: String) {
        if (!aiDailyPlaylistFeature.enabled) return
        SettingsRepository.setAiPlaylistPromptPreset(promptPresetId)
    }

    fun setAiPlaylistCustomSystemPrompt(prompt: String) {
        if (!aiDailyPlaylistFeature.enabled) return
        SettingsRepository.setAiPlaylistCustomSystemPrompt(prompt)
    }

    fun saveAiPlaylistApiKey(apiKey: String) {
        if (!aiDailyPlaylistFeature.enabled) return

        storeScope.launch {
            val providerId = _settingsState.value.aiPlaylistProviderId
            AiPlaylistSecretStore.setApiKey(providerId, apiKey)
            refreshAiPlaylistApiKeyConfigured(providerId)
            _settingsState.update {
                it.copy(
                    aiPlaylistApiTestStatus =
                        "API-ключ сохранен. Поле ввода очищено намеренно; сохраненная копия зашифрована в хранилище приложения."
                )
            }
        }
    }

    fun clearAiPlaylistApiKey() {
        if (!aiDailyPlaylistFeature.enabled) return

        storeScope.launch {
            val providerId = _settingsState.value.aiPlaylistProviderId
            AiPlaylistSecretStore.clearApiKey(providerId)
            refreshAiPlaylistApiKeyConfigured(providerId)
            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = "API-ключ выбранного провайдера очищен")
            }
        }
    }

    fun clearAllAiPlaylistApiKeys() {
        if (!aiDailyPlaylistFeature.enabled) return

        storeScope.launch {
            AiPlaylistProviders.all.forEach { provider ->
                AiPlaylistSecretStore.clearApiKey(provider.id)
            }
            refreshAiPlaylistApiKeyConfigured(_settingsState.value.aiPlaylistProviderId)
            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = "Все сохраненные AI-ключи очищены")
            }
        }
    }

    fun testAiPlaylistApi() {
        if (!aiDailyPlaylistFeature.enabled) return

        storeScope.launch {
            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = "Проверяю AI API...")
            }

            val status = withContext(Dispatchers.IO) {
                val currentState = _settingsState.value
                val provider = AiPlaylistProviders.byId(currentState.aiPlaylistProviderId)
                val apiKey = AiPlaylistSecretStore.getApiKey(provider.id)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext "API-ключ не задан"

                suspendRunCatching {
                    aiNetworkDiagnosticsService.testApiKey(provider, apiKey)
                }.fold(
                    onSuccess = { it.toDisplayText() },
                    onFailure = { "Ошибка AI API: сеть или TLS недоступны" },
                )
            }

            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun testAiPlaylistModelResponse() {
        if (!aiDailyPlaylistFeature.enabled) return

        storeScope.launch {
            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = "Проверяю ответ модели...")
            }

            val status = withContext(Dispatchers.IO) {
                val currentState = _settingsState.value
                val provider = AiPlaylistProviders.byId(currentState.aiPlaylistProviderId)
                val apiKey = AiPlaylistSecretStore.getApiKey(provider.id)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext "API-ключ не задан"
                val recommender = AiPlaylistRecommenderRegistry.get(provider.id)
                    ?: return@withContext "Провайдер не найден"

                suspendRunCatching {
                    recommender.recommend(
                        AiPlaylistRequest(
                            apiKey = apiKey,
                            model = currentState.aiPlaylistModel.ifBlank { provider.defaultModelId },
                            systemPrompt = buildAiPlaylistSystemPrompt(
                                promptPresetId = currentState.aiPlaylistPromptPresetId,
                                customSystemPrompt = currentState.aiPlaylistCustomSystemPrompt,
                                limit = 2,
                            ),
                            candidates = aiResponseTestCandidates,
                            limit = 2,
                        )
                    )
                }.fold(
                    onSuccess = { recommendation ->
                        val rawText = recommendation.rawText.trim()
                        val unfilteredResponse = recommendation.rawProviderResponse
                            .takeIf { it.isNotBlank() }
                            ?: "пустой ответ"
                        when {
                            rawText.isBlank() -> {
                                "Модель ответила пустым текстом\n\n" +
                                    "Неотфильтрованный ответ модели:\n$unfilteredResponse"
                            }
                            recommendation.ids.isNotEmpty() -> {
                                "Модель ответила и вернула ids=${recommendation.ids.joinToString()}. " +
                                    "Фрагмент: ${rawText.safeStatusSnippet()}\n\n" +
                                    "Неотфильтрованный ответ модели:\n$unfilteredResponse"
                            }
                            else -> {
                                "Модель ответила, но JSON с ids не распознан. " +
                                    "Фрагмент: ${rawText.safeStatusSnippet()}\n\n" +
                                    "Неотфильтрованный ответ модели:\n$unfilteredResponse"
                            }
                        }
                    },
                    onFailure = { error ->
                        val body = (error as? AiPlaylistHttpException)
                            ?.responseBody
                            ?.takeIf { it.isNotBlank() }
                            ?: "нет body ответа"
                        "Ошибка ответа модели: ${error.message?.takeIf { it.isNotBlank() } ?: "сеть, TLS или формат ответа"}\n\n" +
                            "Неотфильтрованный ответ модели:\n$body"
                    },
                )
            }

            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun forceGenerateDailyPlaylist() {
        storeScope.launch {
            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = "Генерирую плейлист дня...")
            }

            val status = suspendRunCatching {
                val currentEpochDay = Clock.System.now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
                    .toEpochDays()
                dailyPlaylistRepository.checkAndGenerateDailyPlaylist(
                    currentEpochDay = currentEpochDay,
                    force = true,
                )
            }.fold(
                onSuccess = { result -> result.toDebugStatusText() },
                onFailure = { error ->
                    "Ошибка генерации плейлиста: ${error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка"}"
                },
            )

            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun sendAiPrompt(prompt: String) {
        if (!aiDailyPlaylistFeature.enabled) return

        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = "Промпт пустой")
            }
            return
        }

        storeScope.launch {
            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = "Отправляю промпт...")
            }

            val status = withContext(Dispatchers.IO) {
                val currentState = _settingsState.value
                val provider = AiPlaylistProviders.byId(currentState.aiPlaylistProviderId)
                val apiKey = AiPlaylistSecretStore.getApiKey(provider.id)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext "API-ключ не задан"

                suspendRunCatching {
                    aiPromptService.sendPrompt(
                        provider = provider,
                        apiKey = apiKey,
                        model = currentState.aiPlaylistModel.ifBlank { provider.defaultModelId },
                        prompt = trimmedPrompt,
                    )
                }.fold(
                    onSuccess = { answer ->
                        if (answer.isBlank()) {
                            "Модель ответила пустым текстом"
                        } else {
                            "Ответ модели:\n${answer.safeStatusSnippet(maxLength = 1600)}"
                        }
                    },
                    onFailure = { error ->
                        "Ошибка промпта: ${error.message?.takeIf { it.isNotBlank() } ?: "сеть, TLS или формат ответа"}"
                    },
                )
            }

            _settingsState.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun saveLastFmApiKey(apiKey: String) {
        if (!LastFmSecretStore.supportsSecrets) return

        storeScope.launch {
            LastFmSecretStore.setApiKey(apiKey)
            refreshLastFmApiKeyConfigured()
            _settingsState.update { state ->
                state.copy(
                    lastFmSettings = state.lastFmSettings.copy(
                        testStatus = lastFmStatus(
                            "API-ключ Last.fm сохранен."
                        ),
                    )
                )
            }
        }
    }

    fun clearLastFmApiKey() {
        if (!LastFmSecretStore.supportsSecrets) return

        storeScope.launch {
            LastFmSecretStore.clearApiKey()
            refreshLastFmApiKeyConfigured()
            _settingsState.update { state ->
                state.copy(
                    lastFmSettings = state.lastFmSettings.copy(
                        testStatus = lastFmStatus("API-ключ Last.fm очищен"),
                    )
                )
            }
        }
    }

    fun testLastFmApi() {
        if (!LastFmSecretStore.supportsSecrets) return

        storeScope.launch {
            _settingsState.update { state ->
                state.copy(
                    lastFmSettings = state.lastFmSettings.copy(
                        isTestInProgress = true,
                        testStatus = lastFmStatus("Проверяю Last.fm API..."),
                    )
                )
            }

            val status = withContext(Dispatchers.IO) {
                val apiKey = LastFmSecretStore.getApiKey()?.takeIf { it.isNotBlank() }
                    ?: return@withContext lastFmStatus(
                        message = "API-ключ Last.fm не задан",
                        status = NetworkDiagnosticStatus.Failure,
                    )
                lastFmNetworkDiagnosticsService.testApiKey(apiKey)
            }

            _settingsState.update { state ->
                state.copy(
                    lastFmSettings = state.lastFmSettings.copy(
                        isTestInProgress = false,
                        testStatus = status,
                    )
                )
            }
        }
    }

    fun startLastFmMetadataSync() {
        MusicLibrarySource.startMetadataSync()
    }

    fun startMusicBrainzCoverSync() {
        MusicLibrarySource.startMusicBrainzCoverSync()
    }

    private fun playPreparedQueue(tracks: List<LibraryTrack>, startIndex: Int) {
        val snapshot = playbackQueueController.setQueue(
            trackIds = tracks.map { it.id }.toLongArray(),
            startIndex = startIndex,
        )
        _playbackState.update {
            it.copy(
                playbackQueue = tracks,
                currentQueueIndex = snapshot.currentIndex,
                queueVersion = snapshot.queueVersion,
                currentTrack = tracks.getOrNull(snapshot.currentIndex),
                isShuffleEnabled = false,
            )
        }
        applyQueueSnapshot(snapshot, PlaybackSnapshotApplyMode.Play)
    }

    private fun openDetail(descriptor: DetailEntryDescriptor) {
        val nextNavigationState = currentNavigationState.push(descriptor.route)
        if (nextNavigationState === currentNavigationState) return

        val entry = nextNavigationState.currentContentEntry
        detailEntryStore.register(entry, descriptor)
        publishNavigationState(nextNavigationState)
    }

    private fun prepareDetailUi(
        entryId: Long,
        descriptor: DetailEntryDescriptor,
    ) {
        _libraryState.update { currentState ->
            when (descriptor) {
                is PlaylistDetailDescriptor -> currentState.copy(
                    selectedPlaylist = descriptor.playlist,
                    selectedPlaylistTracks = if (
                        descriptor.playlist.id == SystemPlaylists.DailyPlaylist.id
                    ) {
                        (currentState.dailyPlaylistState as? DailyPlaylistUiState.Available)
                            ?.tracks
                            .orEmpty()
                    } else {
                        emptyList()
                    },
                    selectedLibraryCollection = null,
                    activeDetailEntryId = entryId,
                )

                is LibraryCollectionDetailDescriptor -> currentState.copy(
                    selectedPlaylist = null,
                    selectedPlaylistTracks = emptyList(),
                    selectedLibraryCollection = LibraryCollectionDetailsUiModel(
                        type = descriptor.type,
                        title = descriptor.title,
                        subtitle = descriptor.subtitle,
                        artworkUri = descriptor.artworkUri,
                        tracks = emptyList(),
                    ),
                    activeDetailEntryId = entryId,
                )
            }
        }
    }

    private fun clearActiveDetailUi() {
        _libraryState.update { currentState ->
            currentState.copy(
                selectedPlaylist = null,
                selectedPlaylistTracks = emptyList(),
                selectedLibraryCollection = null,
                activeDetailEntryId = null,
            )
        }
    }

    private fun startDetailObservation(
        entryId: Long,
        descriptor: DetailEntryDescriptor,
    ) {
        detailContentJob = storeScope.launch {
            when (descriptor) {
                is PlaylistDetailDescriptor -> {
                    PlaylistRepository.tracksForPlaylist(descriptor.playlist.id).collect { tracks ->
                        updateActiveDetail(entryId) { currentState ->
                            currentState.copy(selectedPlaylistTracks = tracks)
                        }
                    }
                }

                is LibraryCollectionDetailDescriptor -> {
                    when (descriptor.type) {
                        LibraryCollectionType.ALBUM -> {
                            MusicLibrarySource.getTracksByAlbum(descriptor.collectionId).collect { tracks ->
                                updateActiveDetail(entryId) { currentState ->
                                    currentState.copy(
                                        selectedLibraryCollection = currentState.selectedLibraryCollection
                                            ?.copy(tracks = tracks),
                                    )
                                }
                            }
                        }

                        LibraryCollectionType.ARTIST -> {
                            MusicLibrarySource.getTracksByArtist(descriptor.collectionId).collect { tracks ->
                                updateActiveDetail(entryId) { currentState ->
                                    currentState.copy(
                                        selectedLibraryCollection = currentState.selectedLibraryCollection
                                            ?.copy(tracks = tracks),
                                    )
                                }
                            }
                        }

                        LibraryCollectionType.GENRE -> {
                            combine(
                                MusicLibrarySource.getAlbumsByGenre(descriptor.collectionId),
                                MusicLibrarySource.getTracksByGenre(descriptor.collectionId),
                            ) { albums, tracks -> albums to tracks }
                                .collect { (albums, tracks) ->
                                    updateActiveDetail(entryId) { currentState ->
                                        currentState.copy(
                                            selectedLibraryCollection = currentState.selectedLibraryCollection
                                                ?.copy(
                                                    artworkUri = albums.firstOrNull()?.artworkUri,
                                                    albums = albums,
                                                    tracks = tracks,
                                                ),
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    private fun updateActiveDetail(
        expectedEntryId: Long,
        transform: (LibraryUiState) -> LibraryUiState,
    ) {
        _libraryState.update { currentState ->
            currentState.updateForActiveDetail(
                expectedEntryId = expectedEntryId,
                currentContentEntryId = currentNavigationState.currentContentEntry.entryId,
                transform = transform,
            )
        }
    }

    private var snapshotUpdateJob: Job? = null

    private fun applyQueueSnapshot(
        queueSnapshot: PlaybackQueueSnapshot,
        mode: PlaybackSnapshotApplyMode,
        moveRequest: QueueMoveRequest? = null,
        shuffleApplyMode: ShuffleApplyMode = ShuffleApplyMode.QueueVisible,
        startPositionMs: Long = 0L,
    ) {
        val isUpdate = mode == PlaybackSnapshotApplyMode.Update

        if (isUpdate) {
            snapshotUpdateJob?.cancel()
        }

        val job = storeScope.launch {
            val playbackSnapshot = withContext(Dispatchers.Default) {
                PlaybackResolver.resolve(queueSnapshot)
            }

            val currentQueueSnapshot = playbackQueueController.snapshot()
            if (!playbackSnapshot.matchesQueue(currentQueueSnapshot)) return@launch

            val currentTrack = playbackSnapshot.currentItem()?.toLibraryTrack()

            val resolvedQueue = if (shuffleApplyMode == ShuffleApplyMode.QueueVisible) {
                withContext(Dispatchers.Default) {
                    _playbackState.value.findTracksForIds(currentQueueSnapshot.trackIds)
                }
            } else {
                _playbackState.value.playbackQueue
            }

            if (isUpdate && !isActive) return@launch

            val hasMissingTracks = resolvedQueue.size != currentQueueSnapshot.trackIds.size

            _playbackState.update { currentState ->
                currentState.copy(
                    currentTrack = currentTrack,
                    isPlaying = if (mode == PlaybackSnapshotApplyMode.Play) true else currentState.isPlaying,
                    totalDurationMs = currentTrack?.durationMs ?: currentState.totalDurationMs,
                    playbackQueue = resolvedQueue,
                    currentQueueIndex = currentQueueSnapshot.currentIndex,
                    queueVersion = currentQueueSnapshot.queueVersion,
                    isShuffleEnabled = playbackQueueController.isShuffleEnabled,
                )
            }

            if (shuffleApplyMode == ShuffleApplyMode.QueueVisible && hasMissingTracks) {
                refreshQueueDisplay(currentQueueSnapshot)
            }

            when (mode) {
                PlaybackSnapshotApplyMode.Play -> AudioPlayer.play(playbackSnapshot, startPositionMs)
                PlaybackSnapshotApplyMode.Update -> AudioPlayer.updateQueue(playbackSnapshot)
                PlaybackSnapshotApplyMode.Move -> {
                    val request = moveRequest ?: return@launch
                    AudioPlayer.moveQueueItem(request.fromIndex, request.toIndex, playbackSnapshot)
                }
            }
        }

        if (isUpdate) {
            snapshotUpdateJob = job
        }
    }

    private fun refreshQueueDisplay(queueSnapshot: PlaybackQueueSnapshot) {
        storeScope.launch {
            val currentQueueSnapshot = playbackQueueController.snapshot()
            if (!currentQueueSnapshot.matchesDisplayRequest(queueSnapshot)) return@launch

            val displayQueue = MusicLibrarySource.resolveDisplayQueue(currentQueueSnapshot.trackIds)
            val latestQueueSnapshot = playbackQueueController.snapshot()
            if (!latestQueueSnapshot.matchesDisplayRequest(currentQueueSnapshot)) return@launch

            _playbackState.update { currentState ->
                if (currentNavigationState.currentDestination != AppRoute.Queue) {
                    currentState
                } else {
                    currentState.copy(
                        playbackQueue = displayQueue,
                        currentQueueIndex = latestQueueSnapshot.currentIndex,
                        queueVersion = latestQueueSnapshot.queueVersion,
                        isShuffleEnabled = playbackQueueController.isShuffleEnabled,
                    )
                }
            }
        }
    }

    private fun savePlaybackState() {
        val snapshot = playbackQueueController.snapshot()
        if (snapshot.trackIds.isEmpty()) return
        val currentPlaybackState = _playbackState.value
        val positionMs = AudioPlayer.playbackTimeSource.snapshot().positionMs
        SettingsRepository.savePlaybackState(
            SettingsRepository.SavedPlaybackState(
                queueTrackIds = playbackQueueController.originalTrackIdsSnapshot().toList(),
                queueShuffledIds = if (playbackQueueController.isShuffleEnabled) {
                    snapshot.trackIds.toList()
                } else null,
                queueIndex = snapshot.currentIndex,
                trackPositionMs = positionMs,
                shuffleEnabled = playbackQueueController.isShuffleEnabled,
                repeatMode = currentPlaybackState.repeatMode.name,
            )
        )
    }

    private suspend fun restorePlaybackState() {
        if (restoreAttempted) return
        restoreAttempted = true

        val savedState = SettingsRepository.restorePlaybackState() ?: return
        val savedTrackIds = savedState.queueTrackIds.toLongArray()
        if (savedTrackIds.isEmpty()) return

        val resolvedItems = withContext(Dispatchers.Default) {
            MusicLibrarySource.resolvePlayableItems(savedTrackIds)
        }
        val availableIds = resolvedItems
            .filter { it.ref.availability != TrackAvailability.MISSING }
            .map { it.trackId }

        if (availableIds.isEmpty()) {
            SettingsRepository.clearPlaybackState()
            return
        }

        val filteredIds = availableIds.toLongArray()
        val queueIndex = savedState.queueIndex.coerceIn(0, filteredIds.lastIndex)
        val currentId = filteredIds.getOrNull(queueIndex) ?: return

        val snapshot = playbackQueueController.setQueue(filteredIds, queueIndex)

        if (savedState.shuffleEnabled) {
            val shuffledIds = savedState.queueShuffledIds
                ?.filter { it in availableIds.toSet() }
                ?.toLongArray()
            if (shuffledIds != null && shuffledIds.size == filteredIds.size) {
                playbackQueueController.replaceActiveOrder(
                    expectedQueueVersion = snapshot.queueVersion,
                    orderedTrackIds = shuffledIds,
                    currentTrackId = currentId,
                    shuffleEnabled = true,
                    updateOriginalOrder = false,
                )
            } else {
                playbackQueueController.shuffle()
            }
        }

        val finalSnapshot = playbackQueueController.snapshot()
        val displayQueue = withContext(Dispatchers.Default) {
            MusicLibrarySource.resolveDisplayQueue(finalSnapshot.trackIds)
        }
        val resolvedTrack = resolvedItems.firstOrNull { it.trackId == currentId }
        val libraryTrack = resolvedTrack?.let { item ->
            LibraryTrack(
                id = item.trackId,
                title = item.metadata.title,
                artistName = item.metadata.artistName,
                albumName = item.metadata.albumName,
                durationMs = item.metadata.durationMs,
                albumArtUri = item.metadata.albumArtUri,
            )
        }

        val repeatMode = try {
            PlaybackRepeatMode.valueOf(savedState.repeatMode)
        } catch (_: IllegalArgumentException) {
            PlaybackRepeatMode.Off
        }
        val maxDuration = displayQueue.firstOrNull { it.id == currentId }?.durationMs ?: 0L
        val clampedPosition = savedState.trackPositionMs.coerceIn(0L, maxDuration)

        pendingResumePositionMs = clampedPosition

        _playbackState.update {
            it.copy(
                currentTrack = libraryTrack,
                playbackQueue = displayQueue,
                currentQueueIndex = finalSnapshot.currentIndex,
                queueVersion = finalSnapshot.queueVersion,
                isShuffleEnabled = playbackQueueController.isShuffleEnabled,
                repeatMode = repeatMode,
                totalDurationMs = maxDuration,
                isPlaying = false,
            )
        }
    }

    private suspend fun refreshAiPlaylistApiKeyConfigured(providerId: String) {
        if (!aiDailyPlaylistFeature.enabled) {
            _settingsState.update { it.copy(isAiPlaylistApiKeyConfigured = false) }
            return
        }

        val configuredState = withContext(Dispatchers.IO) {
            val selectedConfigured = AiPlaylistSecretStore.isApiKeyConfigured(providerId)
            val anyConfigured = AiPlaylistProviders.all.any { provider ->
                AiPlaylistSecretStore.isApiKeyConfigured(provider.id)
            }
            selectedConfigured to anyConfigured
        }
        _settingsState.update { currentState ->
            if (currentState.aiPlaylistProviderId != providerId) {
                currentState.copy(isAnyAiPlaylistApiKeyConfigured = configuredState.second)
            } else {
                currentState.copy(
                    isAiPlaylistApiKeyConfigured = configuredState.first,
                    isAnyAiPlaylistApiKeyConfigured = configuredState.second,
                )
            }
        }
    }

    private suspend fun refreshLastFmApiKeyConfigured() {
        if (!LastFmSecretStore.supportsSecrets) {
            _settingsState.update { state ->
                state.copy(
                    lastFmSettings = state.lastFmSettings.copy(
                        supportsSecrets = false,
                        isApiKeyConfigured = false,
                        isTestInProgress = false,
                    )
                )
            }
            return
        }

        val isConfigured = withContext(Dispatchers.IO) {
            LastFmSecretStore.isApiKeyConfigured()
        }
        _settingsState.update { state ->
            state.copy(
                lastFmSettings = state.lastFmSettings.copy(
                    supportsSecrets = true,
                    isApiKeyConfigured = isConfigured,
                )
            )
        }
    }

    private fun lastFmStatus(
        message: String,
        status: NetworkDiagnosticStatus = NetworkDiagnosticStatus.Success,
    ): LastFmApiTestStatus {
        return LastFmApiTestStatus(
            NetworkDiagnosticResult(
                serviceName = "Last.fm",
                status = status,
                message = message,
                host = NetworkHosts.LAST_FM,
                isHttps = true,
                isHostAllowed = true,
            )
        )
    }

    private fun publishNavigationState(
        nextNavigationState: AppNavigationState,
    ) {
        val previousNavigationState = currentNavigationState
        if (nextNavigationState === previousNavigationState) return

        val contentChanged = previousNavigationState.currentContentEntry.entryId !=
            nextNavigationState.currentContentEntry.entryId
        val nextDetailDescriptor = if (
            contentChanged &&
            (
                nextNavigationState.currentContentEntry.route is AppRoute.Playlist ||
                    nextNavigationState.currentContentEntry.route is AppRoute.LibraryCollection
            )
        ) {
            checkNotNull(detailEntryStore.descriptorFor(nextNavigationState.currentContentEntry)) {
                "Detail navigation entry must have a descriptor before publication"
            }
        } else {
            null
        }

        if (contentChanged) {
            detailContentJob?.cancel()
            detailContentJob = null
            if (nextDetailDescriptor != null) {
                prepareDetailUi(
                    entryId = nextNavigationState.currentContentEntry.entryId,
                    descriptor = nextDetailDescriptor,
                )
            } else {
                clearActiveDetailUi()
            }
        }

        _navigationState.value = nextNavigationState

        if (
            previousNavigationState.contains(AppRoute.Search) &&
            !nextNavigationState.contains(AppRoute.Search)
        ) {
            searchPageLoadJob?.cancel()
            searchPageLoadJob = null
            searchRequestGeneration += 1L
            searchPageCursor = null
            _libraryState.update { currentState ->
                currentState.copy(
                    librarySearch = currentState.librarySearch.copy(
                        isActive = false,
                        query = "",
                    ),
                    searchTrackListItems = emptyList(),
                    hasMoreSearchTracks = false,
                    isSearchPageLoading = false,
                )
            }
        }

        detailEntryStore.retainEntries(nextNavigationState.backStack)

        if (contentChanged && nextDetailDescriptor != null) {
            startDetailObservation(
                entryId = nextNavigationState.currentContentEntry.entryId,
                descriptor = nextDetailDescriptor,
            )
        }
    }

    private fun scheduleDailyPlaylistGeneration(trackCount: Int) {
        if (trackCount <= 0) return

        val currentEpochDay = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toEpochDays()

        if (checkedDailyPlaylistEpochDay == currentEpochDay) return
        if (dailyPlaylistGenerationJob?.isActive == true) return

        checkedDailyPlaylistEpochDay = currentEpochDay
        dailyPlaylistGenerationJob = storeScope.launch {
            suspendRunCatching {
                dailyPlaylistRepository.checkAndGenerateDailyPlaylist(currentEpochDay)
            }
        }
    }
}

internal fun PlaybackUiState.withShuffleEnabled(
    random: Random = Random.Default,
): PlaybackUiState? {
    val currentTrackId = currentTrack?.id ?: return null
    val nextQueue = playbackQueue.shuffledWithTrackFirstOrNull(
        currentTrackId = currentTrackId,
        random = random,
    ) ?: return null

    return copy(
        playbackQueue = nextQueue,
        currentQueueIndex = 0,
        isShuffleEnabled = true,
    )
}

internal fun PlaybackUiState.withShuffleDisabled(
    originalQueue: List<LibraryTrack>?,
): PlaybackUiState {
    val currentTrackId = currentTrack?.id
    val restoredQueue = if (currentTrackId == null) {
        null
    } else {
        originalQueue?.takeIf { queue ->
            queue.any { track -> track.id == currentTrackId }
        }
    }

    return if (restoredQueue == null) {
        copy(isShuffleEnabled = false)
    } else {
        copy(
            playbackQueue = restoredQueue,
            currentQueueIndex = restoredQueue.indexOfFirst { it.id == currentTrackId },
            isShuffleEnabled = false,
        )
    }
}

internal fun PlaybackUiState.withRepeatToggled(): PlaybackUiState {
    return copy(repeatMode = repeatMode.next())
}

internal fun PlaybackRepeatMode.next(): PlaybackRepeatMode {
    return when (this) {
        PlaybackRepeatMode.Off -> PlaybackRepeatMode.Queue
        PlaybackRepeatMode.Queue -> PlaybackRepeatMode.One
        PlaybackRepeatMode.One -> PlaybackRepeatMode.Off
    }
}

internal fun List<LibraryTrack>.shuffledWithTrackFirstOrNull(
    currentTrackId: Long,
    random: Random = Random.Default,
): List<LibraryTrack>? {
    val preparedQueue = PlaylistRepository.prepareShuffledQueue(
        tracks = this,
        selectedTrackId = currentTrackId,
        anchor = ShuffleAnchor.KeepSelectedTrackFirst,
        random = random,
    ) ?: return null

    return preparedQueue.tracks.takeIf { tracks ->
        tracks.firstOrNull()?.id == currentTrackId
    }
}

internal fun List<LibraryTrack>.movedQueueItemOrNull(
    fromIndex: Int,
    toIndex: Int,
): List<LibraryTrack>? {
    if (fromIndex == toIndex) return null
    if (fromIndex !in indices || toIndex !in indices) return null

    return toMutableList().apply {
        val track = removeAt(fromIndex)
        add(toIndex, track)
    }.toList()
}

inline fun <R> suspendRunCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

private fun PlaybackSnapshot.currentItem(): ResolvedPlaybackItem? {
    return queue.currentTrackId?.let { currentTrackId ->
        items.firstOrNull { item -> item.trackId == currentTrackId }
    }
}

private fun ResolvedPlaybackItem.toLibraryTrack(): LibraryTrack {
    return LibraryTrack(
        id = trackId,
        title = metadata.title,
        artistName = metadata.artistName,
        albumName = metadata.albumName,
        durationMs = metadata.durationMs,
        albumArtUri = metadata.albumArtUri,
    )
}

private fun PlaybackUiState.findTracksForIds(ids: LongArray): List<LibraryTrack> {
    val queueById = playbackQueue.associateBy { it.id }
    val currentById = currentTrack?.let { mapOf(it.id to it) }.orEmpty()
    return buildList(ids.size) {
        ids.forEach { id ->
            val track = queueById[id] ?: currentById[id]
            if (track != null) {
                add(track)
            }
        }
    }
}

private fun PlaybackQueueSnapshot.matchesDisplayRequest(request: PlaybackQueueSnapshot): Boolean {
    return queueVersion == request.queueVersion &&
        currentTrackId == request.currentTrackId &&
        currentIndex == request.currentIndex &&
        trackIds.contentEquals(request.trackIds)
}

private enum class PlaybackSnapshotApplyMode {
    Play,
    Update,
    Move,
}

private enum class ShuffleApplyMode {
    PlaybackOnly,
    QueueVisible,
}

private data class QueueMoveRequest(
    val fromIndex: Int,
    val toIndex: Int,
)

private val aiResponseTestCandidates = listOf(
    AiPlaylistCandidate(
        id = 101,
        artist = "Test Artist",
        title = "Morning Signal",
        album = "Diagnostics",
    ),
    AiPlaylistCandidate(
        id = 102,
        artist = "Test Artist",
        title = "Night Signal",
        album = "Diagnostics",
    ),
    AiPlaylistCandidate(
        id = 103,
        artist = "Test Artist",
        title = "Calm Signal",
        album = "Diagnostics",
    ),
)

private fun String.safeStatusSnippet(maxLength: Int = 160): String {
    return replace(Regex("\\s+"), " ")
        .take(maxLength)
        .let { snippet -> if (length > maxLength) "$snippet..." else snippet }
}

private fun DailyPlaylistGenerationResult.toDebugStatusText(): String {
    if (!generated) {
        return "Не удалось сгенерировать плейлист: в библиотеке нет доступных треков"
    }

    val modeLabel = when (mode) {
        DailyPlaylistGenerationMode.AI_API -> "AI"
        DailyPlaylistGenerationMode.LOCAL_DAILY -> "рандомный локальный"
        null -> "неизвестный"
    }
    val fallbackText = fallbackReason
        ?.takeIf { it.isNotBlank() }
        ?.let { "\nПричина fallback: $it" }
        .orEmpty()
    val baseStatus = "Плейлист дня сгенерирован принудительно: $modeLabel, треков: $trackCount$fallbackText"
    val debugInfo = aiDebugInfo ?: return baseStatus

    return baseStatus + "\n\n" + debugInfo.toDebugStatusText()
}

private fun DailyPlaylistAiDebugInfo.toDebugStatusText(): String {
    val rawResponse = rawResponse.trim().takeIf { it.isNotBlank() }
        ?.safeStatusSnippet(maxLength = 1200)
        ?: "пустой ответ"
    val unfilteredResponse = unfilteredResponse
        .takeIf { it.isNotBlank() }
        ?: "нет body ответа"

    val errorText = errorMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { "Ошибка AI: ${it.safeStatusSnippet(maxLength = 1200)}\n" }
        .orEmpty()

    return "AI debug:\n" +
        "Провайдер: $providerId\n" +
        "Модель API: $model\n" +
        "Кандидатов отправлено: $candidateCount\n" +
        errorText +
        "Ответ нейросети: $rawResponse\n" +
        "Неотфильтрованный ответ модели:\n$unfilteredResponse\n" +
        "Распознано ids: ${parsedIds.toDebugIdList()}\n" +
        "Принято из AI: ${acceptedIds.toDebugIdList()}\n" +
        "Отброшено: ${rejectedIds.toDebugIdList()}\n" +
        "Добрано локально: ${fallbackIds.toDebugIdList()}\n" +
        "Итог плейлиста: ${selectedIds.toDebugIdList()}"
}

private fun List<Long>.toDebugIdList(maxItems: Int = 40): String {
    if (isEmpty()) return "[]"

    val suffix = if (size > maxItems) ", ... +${size - maxItems}" else ""
    return take(maxItems).joinToString(
        prefix = "[",
        postfix = "$suffix]",
    )
}
