package org.milkdev.dreamplayer.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.milkdev.dreamplayer.playback.AudioPlayer
import org.milkdev.dreamplayer.playback.DailyPlaylistUiState
import org.milkdev.dreamplayer.playback.PlaybackQueueController
import org.milkdev.dreamplayer.playback.PlaybackQueueSnapshot
import org.milkdev.dreamplayer.playback.PlaybackRepeatMode
import org.milkdev.dreamplayer.playback.PlaybackResolver
import org.milkdev.dreamplayer.playback.PlaybackSnapshot
import org.milkdev.dreamplayer.playback.PlayerPresentation
import org.milkdev.dreamplayer.playback.PlayerUiState
import org.milkdev.dreamplayer.playback.ResolvedPlaybackItem
import org.milkdev.dreamplayer.playback.Screen
import org.milkdev.dreamplayer.playback.matchesQueue
import org.milkdev.dreamplayer.playback.movedCopy
import org.milkdev.dreamplayer.playback.shuffledWithCurrentFirst
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private const val PageSize = 60

class PlayerViewModel {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val storeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val aiDailyPlaylistFeature = PlatformFeatureProvider.aiDailyPlaylistApi
    private val aiNetworkDiagnosticsService = AiNetworkDiagnosticsService()
    private val aiPromptService = AiPromptService()
    private val lastFmNetworkDiagnosticsService = LastFmNetworkDiagnosticsService()
    private val playbackQueueController = PlaybackQueueController()
    private var progressJob: Job? = null
    private var dailyPlaylistGenerationJob: Job? = null
    private var playlistTracksJob: Job? = null
    private var navigationState = AppNavigationState()
    private var checkedDailyPlaylistEpochDay: Long? = null
    private var trackPageCursor: LibraryPageCursor? = null
    private var albumPageCursor: LibraryPageCursor? = null
    private var artistPageCursor: LibraryPageCursor? = null
    private var genrePageCursor: LibraryPageCursor? = null
    private var searchPageCursor: LibraryPageCursor? = null
    private var playlistPickerPageCursor: LibraryPageCursor? = null

    init {
        _state.update {
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
                _state.update { currentState ->
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
                _state.update { currentState ->
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
                playbackQueueController.skipToIndex(playbackState.queue.currentIndex)
                _state.update { currentState ->
                    val queueTracks = when {
                        playbackState.queue.trackIds.isEmpty() -> emptyList()
                        currentState.isQueueSheetVisible -> currentState.findTracksForIds(playbackState.queue.trackIds)
                        else -> currentState.playbackQueue
                    }
                    val currentTrack = playbackState.currentTrackId?.let { trackId ->
                        queueTracks.firstOrNull { it.id == trackId }
                            ?: currentState.currentTrack?.takeIf { it.id == trackId }
                    }
                    val resolvedDurationMs = playbackState.totalDurationMs
                        .takeIf { it > 0L }
                        ?: currentTrack?.durationMs
                        ?: 0L
                    val trackChanged = currentState.currentTrack?.id != currentTrack?.id

                    val updatedState = currentState.copy(
                        currentTrack = currentTrack,
                        isPlaying = playbackState.isPlaying,
                        totalDurationMs = resolvedDurationMs,
                        playbackQueue = queueTracks,
                        currentQueueIndex = playbackState.queue.currentIndex,
                        queueVersion = playbackState.queue.queueVersion,
                        playbackProgressMs = when {
                            currentTrack == null -> 0L
                            trackChanged -> 0L
                            else -> currentState.playbackProgressMs.coerceIn(
                                0L,
                                resolvedDurationMs.coerceAtLeast(0L),
                            )
                        },
                    )

                    if (currentTrack == null) {
                        navigationState = navigationState.withoutPlaybackDestinations()
                    }

                    updatedState.withNavigationState(navigationState)
                }
            }
        }

        storeScope.launch {
            MusicLibrarySource.getRecentlyPlayedTracks().collect { tracks ->
                _state.update { it.copy(recentlyPlayedTracks = tracks) }
            }
        }

        storeScope.launch {
            val randomGenre = MusicLibrarySource.getRandomGenreWithTracks()
            if (randomGenre != null) {
                _state.update { it.copy(homeGenreTitle = randomGenre.name) }
                MusicLibrarySource.getTracksByGenre(randomGenre.id).collect { tracks ->
                    _state.update { it.copy(homeGenreTracks = tracks) }
                }
            }
        }

        storeScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            AudioPlayer.state
                .map { it.currentTrackId }
                .distinctUntilChanged()
                .collect { trackId ->
                    if (trackId != null && !_state.value.recentlyPlayedTracks.any { it.id == trackId }) {
                        MusicLibrarySource.addTrackToHistory(trackId)
                    }
                }
        }

        storeScope.launch {
            SettingsRepository.isBlurEnabled.collect { enabled ->
                _state.update { it.copy(isBlurEnabled = enabled) }
            }
        }

        storeScope.launch {
            SettingsRepository.isForceNightMode.collect { enabled ->
                _state.update { it.copy(isForceNightMode = enabled) }
            }
        }

        storeScope.launch {
            SettingsRepository.dailyPlaylistGenerationMode.collect { mode ->
                val effectiveMode = if (aiDailyPlaylistFeature.enabled) {
                    mode
                } else {
                    DailyPlaylistGenerationMode.LOCAL_DAILY
                }
                _state.update {
                    it.copy(dailyPlaylistGenerationMode = effectiveMode)
                }
            }
        }

        storeScope.launch {
            SettingsRepository.aiPlaylistSettings.collect { settings ->
                if (!aiDailyPlaylistFeature.enabled) {
                    _state.update {
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
                _state.update {
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
                _state.update { state ->
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
        startProgressUpdates()
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
                    _state.update { it.copy(isCurrentTrackFavorite = isFavorite) }
                }
        }
    }

    fun setTrackSortOrder(order: TrackSortOrder) {
        if (_state.value.trackSortOrder == order) return
        _state.update { it.copy(trackSortOrder = order) }
        if (order == TrackSortOrder.GENRE) {
            loadNextGenresPage(reset = true)
        } else {
            loadNextTracksPage(reset = true)
        }
    }

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        if (_state.value.albumSortOrder == order) return
        _state.update { it.copy(albumSortOrder = order) }
        if (order == AlbumSortOrder.GENRE) {
            loadNextGenresPage(reset = true)
        } else {
            loadNextAlbumsPage(reset = true)
        }
    }

    fun loadNextTracksPage(reset: Boolean = false) {
        val currentState = _state.value
        if (currentState.isTrackPageLoading) return
        if (!reset && !currentState.hasMoreTracks) return

        if (reset) {
            trackPageCursor = null
        }
        storeScope.launch {
            _state.update {
                it.copy(
                    isTrackPageLoading = true,
                    trackListItems = if (reset) emptyList() else it.trackListItems,
                    hasMoreTracks = if (reset) true else it.hasMoreTracks,
                )
            }
            val order = _state.value.trackSortOrder
            val page = MusicLibrarySource.getTrackPage(
                order = order,
                cursor = trackPageCursor,
                limit = PageSize,
            )
            trackPageCursor = page.nextCursor
            _state.update {
                it.copy(
                    trackListItems = if (reset) page.items else it.trackListItems + page.items,
                    hasMoreTracks = page.hasMore,
                    isTrackPageLoading = false,
                )
            }
        }
    }

    fun loadNextAlbumsPage(reset: Boolean = false) {
        val currentState = _state.value
        if (currentState.isAlbumPageLoading) return
        if (!reset && !currentState.hasMoreAlbums) return

        if (reset) {
            albumPageCursor = null
        }
        storeScope.launch {
            _state.update {
                it.copy(
                    isAlbumPageLoading = true,
                    albumListItems = if (reset) emptyList() else it.albumListItems,
                    hasMoreAlbums = if (reset) true else it.hasMoreAlbums,
                )
            }
            val page = MusicLibrarySource.getAlbumPage(
                order = _state.value.albumSortOrder,
                cursor = albumPageCursor,
                limit = PageSize,
            )
            albumPageCursor = page.nextCursor
            _state.update {
                it.copy(
                    albumListItems = if (reset) page.items else it.albumListItems + page.items,
                    hasMoreAlbums = page.hasMore,
                    isAlbumPageLoading = false,
                )
            }
        }
    }

    fun loadNextArtistsPage(reset: Boolean = false) {
        val currentState = _state.value
        if (currentState.isArtistPageLoading) return
        if (!reset && !currentState.hasMoreArtists) return

        if (reset) {
            artistPageCursor = null
        }
        storeScope.launch {
            _state.update {
                it.copy(
                    isArtistPageLoading = true,
                    artistListItems = if (reset) emptyList() else it.artistListItems,
                    hasMoreArtists = if (reset) true else it.hasMoreArtists,
                )
            }
            val page = MusicLibrarySource.getArtistPage(
                cursor = artistPageCursor,
                limit = PageSize,
            )
            artistPageCursor = page.nextCursor
            _state.update {
                it.copy(
                    artistListItems = if (reset) page.items else it.artistListItems + page.items,
                    hasMoreArtists = page.hasMore,
                    isArtistPageLoading = false,
                )
            }
        }
    }

    fun loadNextGenresPage(reset: Boolean = false) {
        val currentState = _state.value
        if (currentState.isGenrePageLoading) return
        if (!reset && !currentState.hasMoreGenres) return

        if (reset) {
            genrePageCursor = null
        }
        storeScope.launch {
            _state.update {
                it.copy(
                    isGenrePageLoading = true,
                    genreListItems = if (reset) emptyList() else it.genreListItems,
                    hasMoreGenres = if (reset) true else it.hasMoreGenres,
                )
            }
            val page = MusicLibrarySource.getGenrePage(
                cursor = genrePageCursor,
                limit = PageSize,
            )
            genrePageCursor = page.nextCursor
            _state.update {
                it.copy(
                    genreListItems = if (reset) page.items else it.genreListItems + page.items,
                    hasMoreGenres = page.hasMore,
                    isGenrePageLoading = false,
                )
            }
        }
    }

    private fun reloadLibraryPages() {
        loadNextTracksPage(reset = true)
        loadNextAlbumsPage(reset = true)
        loadNextArtistsPage(reset = true)
        loadNextGenresPage(reset = true)
        loadPlaylistPickerPage(reset = true)
    }

    private fun refreshLibrarySummary() {
        storeScope.launch {
            val summary = withContext(Dispatchers.IO) {
                MusicLibrarySource.getLibrarySummary()
            }
            _state.update { state ->
                state.copy(
                    librarySummary = summary,
                    lastFmSettings = state.lastFmSettings.copy(
                        pendingCount = summary.pendingMetadataCount,
                        coverPendingCount = summary.pendingMetadataCount,
                    ),
                )
            }
            scheduleDailyPlaylistGeneration(summary.trackCount)
        }
    }

    fun navigateTo(screen: Screen) {
        setNavigationState(
            navigationState.navigateTo(
                destination = screen.toAppDestination(),
                hasCurrentTrack = _state.value.currentTrack != null,
            )
        )
        if (screen == Screen.Queue) {
            refreshQueueDisplay(playbackQueueController.snapshot())
        }
    }

    fun navigateBack(): Boolean {
        val nextNavigationState = navigationState.navigateBack() ?: return false
        AppDebugLog.log(
            "navigate_back from=${navigationState.currentDestination.name} " +
                "to=${nextNavigationState.currentDestination.name}"
        )
        setNavigationState(nextNavigationState)
        return true
    }

    fun openPlayer() {
        AppDebugLog.log("open_player tracks=${_state.value.librarySummary.trackCount}")
        setNavigationState(
            navigationState.navigateTo(
                destination = AppDestination.Player,
                hasCurrentTrack = _state.value.currentTrack != null,
            )
        )
    }

    fun openQueueSheet() {
        AppDebugLog.log("open_queue_sheet tracks=${_state.value.playbackQueue.size}")
        setNavigationState(
            navigationState.navigateTo(
                destination = AppDestination.Queue,
                hasCurrentTrack = _state.value.currentTrack != null ,
            )
        )
        refreshQueueDisplay(playbackQueueController.snapshot())
    }

    fun openPlaylist(playlist: UserPlaylist) {
        AppDebugLog.log("open_playlist id=${playlist.id}")
        setNavigationState(
            navigationState.navigateTo(AppDestination.PlaylistDetails),
        ) {
            it.copy(
                selectedPlaylist = playlist,
                selectedPlaylistTracks = emptyList(),
                selectedLibraryCollection = null,
            )
        }
        observePlaylistTracks(playlist.id)
    }

    fun openAlbumDetails(album: AlbumListItem) {
        AppDebugLog.log("open_album_details id=${album.id}")
        playlistTracksJob?.cancel()
        playlistTracksJob = storeScope.launch {
            MusicLibrarySource.getTracksByAlbum(album.id).collect { tracks ->
                setNavigationState(
                    navigationState.navigateTo(AppDestination.LibraryCollectionDetails),
                ) { state ->
                    state.copy(
                        selectedPlaylist = null,
                        selectedPlaylistTracks = emptyList(),
                        selectedLibraryCollection = LibraryCollectionDetailsUiModel(
                            type = LibraryCollectionType.ALBUM,
                            title = album.title,
                            subtitle = "${album.artistName}${album.year?.let { year -> " • $year" } ?: ""}",
                            artworkUri = album.artworkUri,
                            tracks = tracks,
                        ),
                    )
                }
            }
        }
    }

    fun openDailyPlaylist() {
        val dailyPlaylist = SystemPlaylists.DailyPlaylist
        AppDebugLog.log("open_system_playlist id=${dailyPlaylist.id}")
        setNavigationState(
            navigationState.navigateTo(AppDestination.PlaylistDetails),
        ) { currentState ->
            currentState.copy(
                selectedPlaylist = UserPlaylist(
                    id = dailyPlaylist.id,
                    name = dailyPlaylist.name,
                    createdAt = dailyPlaylist.createdAt,
                    isSystem = true,
                    canEditTracks = dailyPlaylist.permissions.canEditTracks,
                    canRename = dailyPlaylist.permissions.canRename,
                ),
                selectedPlaylistTracks = (currentState.dailyPlaylistState as? DailyPlaylistUiState.Available)
                    ?.tracks
                    .orEmpty(),
                selectedLibraryCollection = null,
            )
        }
        observePlaylistTracks(dailyPlaylist.id)
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
        val track = _state.value.currentTrack ?: return
        val currentlyFavorite = _state.value.isCurrentTrackFavorite

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
        setNavigationState(navigationState.navigateTo(AppDestination.Search)) { currentState ->
            currentState.copy(
                librarySearch = currentState.librarySearch.copy(isActive = true),
            )
        }
        loadNextSearchPage(reset = true)
    }

    fun updateLibrarySearchQuery(query: String) {
        _state.update { currentState ->
            currentState.copy(
                librarySearch = currentState.librarySearch.copy(query = query),
            )
        }
        loadNextSearchPage(reset = true)
    }

    fun loadNextSearchPage(reset: Boolean = false) {
        val currentState = _state.value
        if (currentState.isSearchPageLoading) return
        if (!reset && !currentState.hasMoreSearchTracks) return

        if (reset) {
            searchPageCursor = null
        }
        storeScope.launch {
            _state.update {
                it.copy(
                    isSearchPageLoading = true,
                    searchTrackListItems = if (reset) emptyList() else it.searchTrackListItems,
                    hasMoreSearchTracks = if (reset) true else it.hasMoreSearchTracks,
                )
            }
            val state = _state.value
            val page = MusicLibrarySource.searchTrackPage(
                query = state.librarySearch.query,
                mode = state.librarySearch.mode,
                cursor = searchPageCursor,
                limit = PageSize,
            )
            searchPageCursor = page.nextCursor
            _state.update {
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
        playlistTracksJob?.cancel()
        playlistTracksJob = storeScope.launch {
            MusicLibrarySource.getTracksByArtist(artist.id).collect { tracks ->
                setNavigationState(
                    navigationState.navigateTo(AppDestination.LibraryCollectionDetails),
                ) {
                    it.copy(
                        selectedPlaylist = null,
                        selectedPlaylistTracks = emptyList(),
                        selectedLibraryCollection = LibraryCollectionDetailsUiModel(
                            type = LibraryCollectionType.ARTIST,
                            title = artist.name,
                            subtitle = "${artist.albumCount} альбомов • ${artist.trackCount} треков",
                            artworkUri = artist.artworkUri,
                            tracks = tracks,
                        ),
                    )
                }
            }
        }
    }

    fun openGenreDetails(genre: GenreListItem) {
        AppDebugLog.log("open_genre_details id=${genre.id}")
        playlistTracksJob?.cancel()
        playlistTracksJob = storeScope.launch {
            combine(
                MusicLibrarySource.getAlbumsByGenre(genre.id),
                MusicLibrarySource.getTracksByGenre(genre.id),
            ) { albums, tracks -> albums to tracks }
                .collect { (albums, tracks) ->
                    setNavigationState(
                        navigationState.navigateTo(AppDestination.LibraryCollectionDetails),
                    ) {
                        it.copy(
                            selectedPlaylist = null,
                            selectedPlaylistTracks = emptyList(),
                            selectedLibraryCollection = LibraryCollectionDetailsUiModel(
                                type = LibraryCollectionType.GENRE,
                                title = genre.name,
                                subtitle = "${genre.albumCount} альбомов • ${genre.trackCount} треков",
                                artworkUri = albums.firstOrNull()?.artworkUri,
                                albums = albums,
                                tracks = tracks,
                            ),
                        )
                    }
                }
        }
    }

    fun loadPlaylistPickerPage(reset: Boolean = false) {
        val currentState = _state.value
        if (currentState.isPlaylistPickerPageLoading) return
        if (!reset && !currentState.hasMorePlaylistPickerTracks) return

        if (reset) {
            playlistPickerPageCursor = null
        }
        storeScope.launch {
            _state.update {
                it.copy(
                    isPlaylistPickerPageLoading = true,
                    playlistPickerTrackItems = if (reset) emptyList() else it.playlistPickerTrackItems,
                    hasMorePlaylistPickerTracks = if (reset) true else it.hasMorePlaylistPickerTracks,
                )
            }
            val page = MusicLibrarySource.getTrackPage(
                order = TrackSortOrder.TRACK_NAME,
                cursor = playlistPickerPageCursor,
                limit = PageSize,
            )
            playlistPickerPageCursor = page.nextCursor
            _state.update {
                it.copy(
                    playlistPickerTrackItems = if (reset) page.items else it.playlistPickerTrackItems + page.items,
                    hasMorePlaylistPickerTracks = page.hasMore,
                    isPlaylistPickerPageLoading = false,
                )
            }
        }
    }

    suspend fun loadLibrary() {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            MusicLibrarySource.loadTracks()
            refreshLibrarySummary()
            reloadLibraryPages()
            _state.update { it.copy(isLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            AppDebugLog.log("library_load_error message=${e.message.orEmpty()}")
        }
    }

    fun shuffleDailyPlaylist() {
        val dailyTracks = (_state.value.dailyPlaylistState as? DailyPlaylistUiState.Available)
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

    fun playFromVisibleTracks(tracks: List<LibraryTrack>, track: LibraryTrack) {
        val trackIndex = tracks.indexOfFirst { it.id == track.id }
        if (trackIndex >= 0) {
            playPreparedQueue(tracks, trackIndex)
        } else {
            playPreparedQueue(listOf(track), 0)
        }
    }

    fun playFromSelectedPlaylist(track: LibraryTrack) {
        playFromVisibleTracks(_state.value.selectedPlaylistTracks, track)
    }

    fun playFromSelectedLibraryCollection(track: LibraryTrack) {
        playFromVisibleTracks(_state.value.selectedLibraryCollection?.tracks.orEmpty(), track)
    }

    fun playQueueItem(index: Int) {
        val snapshot = playbackQueueController.skipToIndex(index) ?: return
        applyQueueSnapshot(snapshot, PlaybackSnapshotApplyMode.Play)
    }

    fun pause() {
        AudioPlayer.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        AudioPlayer.resume()
        _state.update { it.copy(isPlaying = true) }
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
        _state.update { it.copy(playbackProgressMs = positionMs) }
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
            val shouldResolveDisplayQueue = _state.value.isQueueSheetVisible

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

            _state.update {
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
        }
    }

    fun toggleRepeat() {
        val nextState = _state.value.withRepeatToggled()
        _state.value = nextState
        AudioPlayer.setRepeatMode(nextState.repeatMode)
        AppDebugLog.log("repeat_toggle mode=${nextState.repeatMode}")
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
        navigationState = navigationState.withoutPlaybackDestinations()
        _state.update {
            it.copy(
                currentTrack = null,
                isPlaying = false,
                playbackProgressMs = 0L,
                totalDurationMs = 0L,
                playbackQueue = emptyList(),
                currentQueueIndex = -1,
                queueVersion = playbackQueueController.snapshot().queueVersion,
                isShuffleEnabled = false,
            ).withNavigationState(navigationState)
        }
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
            val providerId = _state.value.aiPlaylistProviderId
            AiPlaylistSecretStore.setApiKey(providerId, apiKey)
            refreshAiPlaylistApiKeyConfigured(providerId)
            _state.update {
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
            val providerId = _state.value.aiPlaylistProviderId
            AiPlaylistSecretStore.clearApiKey(providerId)
            refreshAiPlaylistApiKeyConfigured(providerId)
            _state.update {
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
            refreshAiPlaylistApiKeyConfigured(_state.value.aiPlaylistProviderId)
            _state.update {
                it.copy(aiPlaylistApiTestStatus = "Все сохраненные AI-ключи очищены")
            }
        }
    }

    fun testAiPlaylistApi() {
        if (!aiDailyPlaylistFeature.enabled) return

        storeScope.launch {
            _state.update {
                it.copy(aiPlaylistApiTestStatus = "Проверяю AI API...")
            }

            val status = withContext(Dispatchers.IO) {
                val currentState = _state.value
                val provider = AiPlaylistProviders.byId(currentState.aiPlaylistProviderId)
                val apiKey = AiPlaylistSecretStore.getApiKey(provider.id)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext "API-ключ не задан"

                runCatching {
                    aiNetworkDiagnosticsService.testApiKey(provider, apiKey)
                }.fold(
                    onSuccess = { it.toDisplayText() },
                    onFailure = { "Ошибка AI API: сеть или TLS недоступны" },
                )
            }

            _state.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun testAiPlaylistModelResponse() {
        if (!aiDailyPlaylistFeature.enabled) return

        storeScope.launch {
            _state.update {
                it.copy(aiPlaylistApiTestStatus = "Проверяю ответ модели...")
            }

            val status = withContext(Dispatchers.IO) {
                val currentState = _state.value
                val provider = AiPlaylistProviders.byId(currentState.aiPlaylistProviderId)
                val apiKey = AiPlaylistSecretStore.getApiKey(provider.id)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext "API-ключ не задан"
                val recommender = AiPlaylistRecommenderRegistry.get(provider.id)
                    ?: return@withContext "Провайдер не найден"

                runCatching {
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

            _state.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun forceGenerateDailyPlaylist() {
        storeScope.launch {
            _state.update {
                it.copy(aiPlaylistApiTestStatus = "Генерирую плейлист дня...")
            }

            val status = runCatching {
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

            _state.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun sendAiPrompt(prompt: String) {
        if (!aiDailyPlaylistFeature.enabled) return

        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            _state.update {
                it.copy(aiPlaylistApiTestStatus = "Промпт пустой")
            }
            return
        }

        storeScope.launch {
            _state.update {
                it.copy(aiPlaylistApiTestStatus = "Отправляю промпт...")
            }

            val status = withContext(Dispatchers.IO) {
                val currentState = _state.value
                val provider = AiPlaylistProviders.byId(currentState.aiPlaylistProviderId)
                val apiKey = AiPlaylistSecretStore.getApiKey(provider.id)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext "API-ключ не задан"

                runCatching {
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

            _state.update {
                it.copy(aiPlaylistApiTestStatus = status)
            }
        }
    }

    fun saveLastFmApiKey(apiKey: String) {
        if (!LastFmSecretStore.supportsSecrets) return

        storeScope.launch {
            LastFmSecretStore.setApiKey(apiKey)
            refreshLastFmApiKeyConfigured()
            _state.update { state ->
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
            _state.update { state ->
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
            _state.update { state ->
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

            _state.update { state ->
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
        _state.update {
            it.copy(
                playbackQueue = tracks,
                currentQueueIndex = snapshot.currentIndex,
                queueVersion = snapshot.queueVersion,
                currentTrack = tracks.getOrNull(snapshot.currentIndex),
                isShuffleEnabled = false,
                playbackProgressMs = 0L,
            )
        }
        applyQueueSnapshot(snapshot, PlaybackSnapshotApplyMode.Play)
    }

    private fun observePlaylistTracks(playlistId: Long) {
        playlistTracksJob?.cancel()
        playlistTracksJob = storeScope.launch {
            PlaylistRepository.tracksForPlaylist(playlistId).collect { tracks ->
                _state.update { currentState ->
                    if (currentState.selectedPlaylist?.id == playlistId) {
                        currentState.copy(selectedPlaylistTracks = tracks)
                    } else {
                        currentState
                    }
                }
            }
        }
    }

    private fun applyQueueSnapshot(
        queueSnapshot: PlaybackQueueSnapshot,
        mode: PlaybackSnapshotApplyMode,
        moveRequest: QueueMoveRequest? = null,
        shuffleApplyMode: ShuffleApplyMode = ShuffleApplyMode.QueueVisible,
    ) {
        storeScope.launch {
            val playbackSnapshot = PlaybackResolver.resolve(queueSnapshot)
            val currentQueueSnapshot = playbackQueueController.snapshot()
            if (!playbackSnapshot.matchesQueue(currentQueueSnapshot)) {
                AppDebugLog.log(
                    "playback_snapshot_stale requested=${queueSnapshot.queueVersion} " +
                            "current=${currentQueueSnapshot.queueVersion}"
                )
                return@launch
            }

            val currentTrack = playbackSnapshot.currentItem()?.toLibraryTrack()

            _state.update { currentState ->
                val resolvedQueue = if (shuffleApplyMode == ShuffleApplyMode.QueueVisible) {
                    currentState.findTracksForIds(currentQueueSnapshot.trackIds)
                } else {
                    currentState.playbackQueue
                }

                currentState.copy(
                    currentTrack = currentTrack,
                    isPlaying = if (mode == PlaybackSnapshotApplyMode.Play) true else currentState.isPlaying,
                    totalDurationMs = currentTrack?.durationMs ?: currentState.totalDurationMs,
                    playbackProgressMs = if (mode == PlaybackSnapshotApplyMode.Play) 0L else currentState.playbackProgressMs,
                    playbackQueue = resolvedQueue,
                    currentQueueIndex = currentQueueSnapshot.currentIndex,
                    queueVersion = currentQueueSnapshot.queueVersion,
                    isShuffleEnabled = playbackQueueController.isShuffleEnabled,
                )
            }

            when (mode) {
                PlaybackSnapshotApplyMode.Play -> AudioPlayer.play(playbackSnapshot)
                PlaybackSnapshotApplyMode.Update -> AudioPlayer.updateQueue(playbackSnapshot)
                PlaybackSnapshotApplyMode.Move -> {
                    val request = moveRequest ?: return@launch
                    AudioPlayer.moveQueueItem(request.fromIndex, request.toIndex, playbackSnapshot)
                }
            }
        }
    }

    private fun refreshQueueDisplay(queueSnapshot: PlaybackQueueSnapshot) {
        storeScope.launch {
            val currentQueueSnapshot = playbackQueueController.snapshot()
            if (!currentQueueSnapshot.matchesDisplayRequest(queueSnapshot)) return@launch

            val displayQueue = MusicLibrarySource.resolveDisplayQueue(currentQueueSnapshot.trackIds)
            val latestQueueSnapshot = playbackQueueController.snapshot()
            if (!latestQueueSnapshot.matchesDisplayRequest(currentQueueSnapshot)) return@launch

            _state.update { currentState ->
                if (!currentState.isQueueSheetVisible) {
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

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = storeScope.launch {
            while (isActive) {
                val currentState = _state.value
                val currentTrack = currentState.currentTrack
                if (currentTrack != null && !currentState.isQueueSheetVisible) {
                    val maxDuration = currentState.totalDurationMs
                        .takeIf { it > 0L } ?: currentTrack.durationMs
                    val nextProgress = AudioPlayer.getCurrentPosition().coerceIn(
                        0L,
                        maxDuration.coerceAtLeast(0L),
                    )

                    _state.update { state ->
                        if (state.playbackProgressMs == nextProgress) {
                            state
                        } else {
                            state.copy(playbackProgressMs = nextProgress)
                        }
                    }
                }
                delay(250.milliseconds)
            }
        }
    }

    private suspend fun refreshAiPlaylistApiKeyConfigured(providerId: String) {
        if (!aiDailyPlaylistFeature.enabled) {
            _state.update { it.copy(isAiPlaylistApiKeyConfigured = false) }
            return
        }

        val configuredState = withContext(Dispatchers.IO) {
            val selectedConfigured = AiPlaylistSecretStore.isApiKeyConfigured(providerId)
            val anyConfigured = AiPlaylistProviders.all.any { provider ->
                AiPlaylistSecretStore.isApiKeyConfigured(provider.id)
            }
            selectedConfigured to anyConfigured
        }
        _state.update { currentState ->
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
            _state.update { state ->
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
        _state.update { state ->
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

    private fun setNavigationState(
        nextNavigationState: AppNavigationState,
        stateTransform: (PlayerUiState) -> PlayerUiState = { it },
    ) {
        val previousNavigationState = navigationState
        if (
            previousNavigationState.contains(AppDestination.PlaylistDetails) &&
            !nextNavigationState.contains(AppDestination.PlaylistDetails)
        ) {
            playlistTracksJob?.cancel()
            playlistTracksJob = null
        }

        navigationState = nextNavigationState
        _state.update { currentState ->
            stateTransform(currentState).withNavigationState(navigationState)
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
            runCatching {
                dailyPlaylistRepository.checkAndGenerateDailyPlaylist(currentEpochDay)
            }
        }
    }
}

internal enum class AppDestination {
    Home,
    Library,
    PlaylistDetails,
    LibraryCollectionDetails,
    Search,
    Settings,
    AiDebugSettings,
    Player,
    Queue,
}

internal data class AppNavigationState(
    val backStack: List<AppDestination> = listOf(AppDestination.Home),
) {
    private val normalizedBackStack: List<AppDestination>
        get() = backStack.ifEmpty { listOf(AppDestination.Home) }

    val currentDestination: AppDestination
        get() = normalizedBackStack.last()

    val currentContentDestination: AppDestination
        get() = normalizedBackStack.asReversed()
            .firstOrNull { !it.isPlaybackDestination }
            ?: AppDestination.Home

    val canNavigateBack: Boolean
        get() = normalizedBackStack.size > 1

    fun contains(destination: AppDestination): Boolean {
        return destination in normalizedBackStack
    }

    fun navigateBack(): AppNavigationState? {
        if (!canNavigateBack) return null
        return AppNavigationState(normalizedBackStack.dropLast(1))
    }

    fun navigateTo(
        destination: AppDestination,
        hasCurrentTrack: Boolean = true,
    ): AppNavigationState {
        val stack = normalizedBackStack
        return when (destination) {
            AppDestination.Home -> AppNavigationState(listOf(AppDestination.Home))
            AppDestination.Library -> AppNavigationState(
                listOf(AppDestination.Home, AppDestination.Library)
            )
            AppDestination.Settings -> AppNavigationState(
                listOf(AppDestination.Home, AppDestination.Settings)
            )
            AppDestination.AiDebugSettings -> {
                val baseStack = if (stack.lastOrNull() == AppDestination.Settings) {
                    stack
                } else {
                    listOf(AppDestination.Home, AppDestination.Settings)
                }
                AppNavigationState(baseStack.pushUnique(AppDestination.AiDebugSettings))
            }
            AppDestination.Search -> AppNavigationState(
                stack.pushUnique(AppDestination.Search)
            )
            AppDestination.PlaylistDetails,
            AppDestination.LibraryCollectionDetails -> AppNavigationState(
                stack
                    .withoutPlaybackDestinations()
                    .withoutDestination(AppDestination.PlaylistDetails)
                    .withoutDestination(AppDestination.LibraryCollectionDetails)
                    .pushUnique(destination)
            )
            AppDestination.Player -> {
                if (!hasCurrentTrack) {
                    this
                } else {
                    AppNavigationState(
                        stack
                            .withoutDestination(AppDestination.Queue)
                            .pushUnique(AppDestination.Player)
                    )
                }
            }
            AppDestination.Queue -> {
                if (!hasCurrentTrack) {
                    this
                } else {
                    val playerStack = stack
                        .withoutDestination(AppDestination.Queue)
                        .pushUnique(AppDestination.Player)
                    AppNavigationState(playerStack.pushUnique(AppDestination.Queue))
                }
            }
        }
    }

    fun withoutPlaybackDestinations(): AppNavigationState {
        return AppNavigationState(normalizedBackStack.withoutPlaybackDestinations())
    }
}

private val AppDestination.isPlaybackDestination: Boolean
    get() = this == AppDestination.Player || this == AppDestination.Queue

private fun List<AppDestination>.pushUnique(destination: AppDestination): List<AppDestination> {
    return if (lastOrNull() == destination) this else this + destination
}

private fun List<AppDestination>.withoutDestination(destination: AppDestination): List<AppDestination> {
    return filterNot { it == destination }.ifEmpty { listOf(AppDestination.Home) }
}

private fun List<AppDestination>.withoutPlaybackDestinations(): List<AppDestination> {
    return filterNot { it.isPlaybackDestination }.ifEmpty { listOf(AppDestination.Home) }
}

private fun Screen.toAppDestination(): AppDestination {
    return when (this) {
        Screen.Home -> AppDestination.Home
        Screen.Library -> AppDestination.Library
        Screen.PlaylistDetails -> AppDestination.PlaylistDetails
        Screen.LibraryCollectionDetails -> AppDestination.LibraryCollectionDetails
        Screen.Player -> AppDestination.Player
        Screen.Queue -> AppDestination.Queue
        Screen.Settings -> AppDestination.Settings
        Screen.AiDebugSettings -> AppDestination.AiDebugSettings
        Screen.Search -> AppDestination.Search
    }
}

private fun AppDestination.toScreen(): Screen {
    return when (this) {
        AppDestination.Home -> Screen.Home
        AppDestination.Library -> Screen.Library
        AppDestination.PlaylistDetails -> Screen.PlaylistDetails
        AppDestination.LibraryCollectionDetails -> Screen.LibraryCollectionDetails
        AppDestination.Search -> Screen.Search
        AppDestination.Settings -> Screen.Settings
        AppDestination.AiDebugSettings -> Screen.AiDebugSettings
        AppDestination.Player -> Screen.Player
        AppDestination.Queue -> Screen.Queue
    }
}

internal fun PlayerUiState.withNavigationState(
    navigationState: AppNavigationState,
): PlayerUiState {
    val hasPlaylistDetails = navigationState.contains(AppDestination.PlaylistDetails)
    val hasLibraryCollectionDetails = navigationState.contains(AppDestination.LibraryCollectionDetails)
    val hasSearch = navigationState.contains(AppDestination.Search)
    val hasPlaybackDestination = currentTrack != null &&
        (
            navigationState.contains(AppDestination.Player) ||
                navigationState.contains(AppDestination.Queue)
        )

    return copy(
        currentScreen = navigationState.currentContentDestination.toScreen(),
        canNavigateBack = navigationState.canNavigateBack,
        selectedPlaylist = if (hasPlaylistDetails) selectedPlaylist else null,
        selectedPlaylistTracks = if (hasPlaylistDetails) selectedPlaylistTracks else emptyList(),
        selectedLibraryCollection = if (hasLibraryCollectionDetails) selectedLibraryCollection else null,
        librarySearch = if (hasSearch) {
            librarySearch.copy(isActive = true)
        } else {
            librarySearch.copy(
                isActive = false,
                query = "",
            )
        },
        playerPresentation = if (hasPlaybackDestination) {
            PlayerPresentation.Fullscreen
        } else {
            PlayerPresentation.Mini
        },
        isQueueSheetVisible = currentTrack != null &&
            navigationState.currentDestination == AppDestination.Queue,
    )
}

internal fun PlayerUiState.withNavigationTarget(screen: Screen): PlayerUiState {
    return when (screen) {
        Screen.Player -> withPlayerOpened()
        Screen.Queue -> withQueueSheetOpened()
        else -> copy(currentScreen = screen)
    }
}

internal fun PlayerUiState.withPlayerOpened(): PlayerUiState {
    if (currentTrack == null) return this

    return copy(playerPresentation = PlayerPresentation.Fullscreen)
}

internal fun PlayerUiState.withPlayerClosed(): PlayerUiState {
    return copy(
        playerPresentation = PlayerPresentation.Mini,
        isQueueSheetVisible = false,
    )
}

internal fun PlayerUiState.withQueueSheetOpened(): PlayerUiState {
    if (currentTrack == null) return this

    return copy(
        playerPresentation = PlayerPresentation.Fullscreen,
        isQueueSheetVisible = true,
    )
}

internal fun PlayerUiState.withShuffleEnabled(
    random: Random = Random.Default,
): PlayerUiState? {
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

internal fun PlayerUiState.withShuffleDisabled(
    originalQueue: List<LibraryTrack>?,
): PlayerUiState {
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

internal fun PlayerUiState.withRepeatToggled(): PlayerUiState {
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

private fun PlayerUiState.findTracksForIds(ids: LongArray): List<LibraryTrack> {
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
