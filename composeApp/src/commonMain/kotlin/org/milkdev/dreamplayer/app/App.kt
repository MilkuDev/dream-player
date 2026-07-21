package org.milkdev.dreamplayer.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.DeferredAnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.MutableContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DeferredTransitionState
import androidx.compose.animation.core.ExperimentalDeferredTransitionApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.pause
import org.milkdev.dreamplayer.generated.resources.play_arrow
import org.milkdev.dreamplayer.generated.resources.skip_next
import org.milkdev.dreamplayer.generated.resources.skip_previous
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.model.PlayerViewModel
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainDestination
import org.milkdev.dreamplayer.navigation.MainPage
import org.milkdev.dreamplayer.ui.*
import kotlin.math.roundToInt

private val playerViewModelInstance = PlayerViewModel()

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
    ExperimentalDeferredTransitionApi::class,
)
@Composable
fun App(
    playerViewModel: PlayerViewModel = playerViewModelInstance,
    isPermissionGranted: Boolean = true
) {
    val playbackState by playerViewModel.playbackState.collectAsState()
    val libraryState by playerViewModel.libraryState.collectAsState()
    val settingsState by playerViewModel.settingsState.collectAsState()
    val navigationState by playerViewModel.navigationState.collectAsState()
    val committedContentSnapshot = contentNavigationSnapshot(navigationState.backStack)
    val contentTransitionState = remember {
        DeferredTransitionState(committedContentSnapshot)
    }
    val contentTransition = rememberTransition(
        transitionState = contentTransitionState,
        label = "ContentNavigation",
    )
    val latestNavigationState by rememberUpdatedState(navigationState)
    var contentBackSession by remember { mutableStateOf<ContentBackSession?>(null) }
    val detailPresentationSnapshots = remember {
        mutableStateMapOf<Long, org.milkdev.dreamplayer.playback.LibraryUiState>()
    }

    SideEffect {
        libraryState.activeDetailEntryId?.let { entryId ->
            detailPresentationSnapshots[entryId] = libraryState
        }
    }

    LaunchedEffect(navigationState.backStack.map { it.entryId }) {
        val retainedEntryIds = navigationState.backStack.mapTo(mutableSetOf()) { it.entryId }
        detailPresentationSnapshots.keys.toList().forEach { entryId ->
            if (entryId !in retainedEntryIds) {
                detailPresentationSnapshots.remove(entryId)
            }
        }
    }

    LaunchedEffect(committedContentSnapshot) {
        if (contentBackSession == null) {
            contentTransitionState.animateTo(committedContentSnapshot)
        }
    }

    LaunchedEffect(navigationState.currentEntry.entryId) {
        val session = contentBackSession ?: return@LaunchedEffect
        if (navigationState.currentEntry.entryId != session.originTopEntryId) {
            contentTransitionState.animateTo(committedContentSnapshot)
            contentBackSession = null
            AppDebugLog.log(
                "predictive_back_settled surface=content result=stale_route " +
                    "entryId=${session.originTopEntryId}",
            )
        }
    }

    LaunchedEffect(contentBackSession?.phase) {
        val session = contentBackSession ?: return@LaunchedEffect
        if (session.phase != ContentBackPhase.Cancelling) return@LaunchedEffect

        val cancelProgress = Animatable(session.progress.coerceIn(0f, 1f))
        cancelProgress.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) {
            val currentSession = contentBackSession
            if (
                currentSession?.originTopEntryId == session.originTopEntryId &&
                currentSession.phase == ContentBackPhase.Cancelling
            ) {
                contentBackSession = currentSession.copy(progress = value)
            }
        }
        val currentSession = contentBackSession
        if (
            currentSession?.originTopEntryId == session.originTopEntryId &&
            currentSession.phase == ContentBackPhase.Cancelling
        ) {
            contentTransitionState.animateTo(session.origin)
        }
    }

    LaunchedEffect(
        contentBackSession?.phase,
        contentTransition.currentState,
        contentTransition.targetState,
        contentTransition.pendingTargetState,
        contentTransition.isRunning,
    ) {
        val session = contentBackSession ?: return@LaunchedEffect
        if (contentTransition.pendingTargetState != null || contentTransition.isRunning) {
            return@LaunchedEffect
        }
        when (session.phase) {
            ContentBackPhase.Tracking -> Unit

            ContentBackPhase.Cancelling -> {
                if (
                    contentTransition.currentState == session.origin &&
                    contentTransition.targetState == session.origin
                ) {
                    contentBackSession = null
                    AppDebugLog.log(
                        "predictive_back_settled surface=content result=cancelled " +
                            "entryId=${session.originTopEntryId}",
                    )
                }
            }

            ContentBackPhase.Committing -> {
                if (
                    contentTransition.currentState == session.preview &&
                    contentTransition.targetState == session.preview
                ) {
                    val didPop = playerViewModel.navigateBack(
                        expectedTopEntryId = session.originTopEntryId,
                    )
                    if (!didPop) {
                        contentTransitionState.animateTo(
                            contentNavigationSnapshot(latestNavigationState.backStack),
                        )
                    }
                    contentBackSession = null
                    AppDebugLog.log(
                        "predictive_back_settled surface=content " +
                            "result=${if (didPop) "committed" else "stale"} " +
                            "entryId=${session.originTopEntryId}",
                    )
                }
            }
        }
    }

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            playerViewModel.loadLibrary()
        }
    }

    val hazeState = rememberHazeState()

    AppTheme(darkTheme = settingsState.isForceNightMode || androidx.compose.foundation.isSystemInDarkTheme()) {
        val hasPlaybackOverlay = navigationState.currentDestination == AppRoute.Player ||
            navigationState.currentDestination == AppRoute.Queue
        PlatformBackHandler(
            enabled = navigationState.canNavigateBack &&
                !hasPlaybackOverlay &&
                (
                    contentBackSession?.phase == ContentBackPhase.Tracking ||
                        (contentBackSession == null && !contentTransition.isRunning)
                    ),
            onBackStarted = {
                val origin = latestNavigationState
                val preview = origin.previewBack()
                val session = preview?.let {
                    ContentBackSession(
                        originTopEntryId = origin.currentEntry.entryId,
                        origin = contentNavigationSnapshot(origin.backStack),
                        preview = contentNavigationSnapshot(it.backStack),
                    )
                }
                contentBackSession = session
                if (session != null) {
                    contentTransitionState.defer(session.preview)
                    AppDebugLog.log(
                        "predictive_back_start surface=content entryId=${session.originTopEntryId}",
                    )
                }
            },
            onBackProgressed = { event ->
                val session = contentBackSession ?: return@PlatformBackHandler
                if (
                    session.phase != ContentBackPhase.Tracking ||
                    latestNavigationState.currentEntry.entryId != session.originTopEntryId
                ) {
                    return@PlatformBackHandler
                }
                contentBackSession = session.copy(
                    progress = event.progress.coerceIn(0f, 1f),
                    swipeEdge = event.swipeEdge,
                )
            },
            onBackCancelled = {
                val session = contentBackSession ?: return@PlatformBackHandler
                if (session.phase == ContentBackPhase.Tracking) {
                    contentBackSession = session.copy(phase = ContentBackPhase.Cancelling)
                    AppDebugLog.log(
                        "predictive_back_cancel surface=content entryId=${session.originTopEntryId}",
                    )
                }
            },
            onBackCommitted = { hadProgress ->
                val session = contentBackSession
                if (session == null) {
                    playerViewModel.navigateBack()
                    return@PlatformBackHandler
                }
                if (!hadProgress) {
                    contentTransitionState.animateTo(session.origin)
                    contentBackSession = null
                    playerViewModel.navigateBack(expectedTopEntryId = session.originTopEntryId)
                    return@PlatformBackHandler
                }
                contentBackSession = session.copy(phase = ContentBackPhase.Committing)
                contentTransitionState.animateTo(session.preview)
                AppDebugLog.log(
                    "predictive_back_commit surface=content entryId=${session.originTopEntryId}",
                )
            },
        )

        val onContentBack: () -> Unit = {
            if (contentBackSession == null && !contentTransition.isRunning) {
                playerViewModel.navigateBack()
            }
        }

        val spacing = AppTheme.spacing
        val playerSidePadding = 16.dp
        val playerTopPadding = 16.dp

        val playerBarRadius = 32.dp
        val searchBarRadius = 24.dp
        val defaultDockRadius = 20.dp

        val activeTopRadius = when {
            playbackState.currentTrack != null -> playerBarRadius
            navigationState.currentContentEntry.route == AppRoute.Search -> searchBarRadius
            else -> defaultDockRadius
        }

        val animatedBlurContainerRadius by animateDpAsState(
            targetValue = activeTopRadius + playerSidePadding,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
            bottomBar = {
                if (
                    navigationState.currentContentEntry.route != AppRoute.Settings &&
                    navigationState.currentContentEntry.route != AppRoute.AiDebugSettings
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .disableClicks()
                            .clip(RoundedCornerShape(
                                topStart = animatedBlurContainerRadius,
                                topEnd = animatedBlurContainerRadius
                            ))
                            .then(
                                if (settingsState.isBlurEnabled) {
                                    Modifier.hazeEffect(hazeState) {
                                        blurRadius = 16.dp
                                    }
                                } else Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(playerTopPadding))

                        if (playbackState.currentTrack != null) {
                            PlayerBar(
                                currentTrack = playbackState.currentTrack,
                                isPlaying = playbackState.isPlaying,
                                onPreviousClick = { playerViewModel.playPrevious() },
                                onNextClick = { playerViewModel.playNext() },
                                onPlayPauseClick = {
                                    if (playbackState.isPlaying) playerViewModel.pause() else playerViewModel.resume()
                                },
                                onClick = { playerViewModel.openPlayer() },
                                modifier = Modifier.padding(horizontal = playerSidePadding),
                                barRadius = playerBarRadius
                            )
                        }

                        Spacer(modifier = Modifier.height(spacing.medium))

                        BottomDock(
                            activeMainDestination = navigationState.activeMainDestination,
                            librarySearch = libraryState.librarySearch,
                            onHomeClick = { playerViewModel.selectMainPage(MainPage.Home) },
                            onLibraryClick = { playerViewModel.selectMainPage(MainPage.Library) },
                            onOpenSearch = playerViewModel::openLibrarySearch,
                            onCloseSearch = playerViewModel::closeLibrarySearch,
                            onSearchQueryChange = playerViewModel::updateLibrarySearchQuery,
                        )
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val saveableStateHolder = rememberSaveableStateHolder()
                val latestBackStack by rememberUpdatedState(navigationState.backStack)
                val predictiveVeilColor = MaterialTheme.colorScheme.scrim
                contentTransition.DeferredAnimatedContent(
                    transitionSpec = {
                        val session = contentBackSession
                        when {
                            session == null -> navigationContentTransform(initialState, targetState)
                            session.phase == ContentBackPhase.Cancelling &&
                                contentTransition.pendingTargetState == null ->
                                predictiveBackCancelContentTransform()
                            else -> predictiveBackContentTransform(session.swipeEdge)
                        }
                    },
                    contentKey = { it.currentEntry.entryId },
                    mutableTransformSpec = {
                        val session = contentBackSession ?: return@DeferredAnimatedContent null
                        val progress = session.progress.coerceIn(0f, 1f)
                        val direction = if (session.swipeEdge == BackSwipeEdge.Right) -1 else 1
                        MutableContentTransform(targetVeilMatchParentSize = true) {
                            initialContentTransform { fullSize ->
                                scale = 1f - (0.08f * progress)
                                offset = IntOffset(
                                    x = (
                                        direction * fullSize.width * 0.05f * progress
                                        ).roundToInt(),
                                    y = 0,
                                )
                            }
                            targetContentTransform {
                                scale = 1f
                                veil = predictiveVeilColor.copy(alpha = 0.08f * (1f - progress))
                            }
                        }
                    },
                ) { targetSnapshot ->
                    val targetEntry = targetSnapshot.currentEntry
                    DisposableEffect(targetEntry.entryId) {
                        onDispose {
                            val hasStablePresentationIdentity =
                                targetEntry.route == AppRoute.Home || targetEntry.route == AppRoute.Library
                            if (
                                !hasStablePresentationIdentity &&
                                latestBackStack.none { it.entryId == targetEntry.entryId }
                            ) {
                                saveableStateHolder.removeState(targetEntry.entryId)
                            }
                        }
                    }
                    val activeBackSession = contentBackSession
                    val isPredictiveOrigin = activeBackSession?.origin
                        ?.currentEntry?.entryId == targetEntry.entryId
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isPredictiveOrigin) {
                                    Modifier.graphicsLayer {
                                        clip = true
                                        shape = RoundedCornerShape(28.dp)
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .background(MaterialTheme.colorScheme.background)
                            .then(
                                if (activeBackSession != null) {
                                    Modifier.clearAndSetSemantics { }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        saveableStateHolder.SaveableStateProvider(targetEntry.entryId) {
                            val entryContentPadding = remember(targetEntry.entryId) { paddingValues }
                            val entryLibraryState = if (
                                libraryState.activeDetailEntryId == targetEntry.entryId
                            ) {
                                libraryState
                            } else {
                                detailPresentationSnapshots[targetEntry.entryId]
                            }
                            when (targetEntry.route) {
                            AppRoute.Home -> HomeScreen(
                                libraryState = libraryState,
                                onShuffleDailyPlaylistClick = playerViewModel::shuffleDailyPlaylist,
                                onOpenDailyPlaylistClick = playerViewModel::openDailyPlaylist,
                                contentPadding = entryContentPadding,
                                onSettingsClick = playerViewModel::openSettings,
                                onTrackClick = playerViewModel::playFromVisibleTracks
                            )
                            AppRoute.Library -> LibraryScreen(
                                libraryState = libraryState,
                                currentTrack = playbackState.currentTrack,
                                onIntent = playerViewModel::onLibraryIntent,
                                onSettingsClick = playerViewModel::openSettings,
                                contentPadding = entryContentPadding,
                            )
                            is AppRoute.Playlist -> {
                                if (entryLibraryState != null) {
                                    PlaylistDetailsScreen(
                                        libraryState = entryLibraryState,
                                        currentTrackId = playbackState.currentTrack?.id,
                                        onBackClick = onContentBack,
                                        onTrackClick = playerViewModel::playFromSelectedPlaylist,
                                        onSaveTracks = playerViewModel::savePlaylistTracks,
                                        onLoadNextPickerTracks = { playerViewModel.loadPlaylistPickerPage() },
                                        contentPadding = entryContentPadding,
                                    )
                                }
                            }
                            is AppRoute.LibraryCollection -> {
                                if (entryLibraryState != null) {
                                    LibraryCollectionDetailsScreen(
                                        libraryState = entryLibraryState,
                                        currentTrackId = playbackState.currentTrack?.id,
                                        onBackClick = onContentBack,
                                        onTrackClick = playerViewModel::playFromSelectedLibraryCollection,
                                        onAlbumClick = playerViewModel::openAlbumDetails,
                                        contentPadding = entryContentPadding,
                                    )
                                }
                            }
                            AppRoute.Settings -> SettingsScreen(
                                settingsState = settingsState,
                                onBackClick = onContentBack,
                                onBlurToggle = { playerViewModel.setBlurEnabled(it) },
                                onNightModeToggle = { playerViewModel.setForceNightMode(it) },
                                onDailyPlaylistModeChange = { playerViewModel.setDailyPlaylistGenerationMode(it) },
                                onAiPlaylistProviderChange = { playerViewModel.setAiPlaylistProvider(it) },
                                onAiPlaylistModelChange = { playerViewModel.setAiPlaylistModel(it) },
                                onAiPlaylistPromptPresetChange = { playerViewModel.setAiPlaylistPromptPreset(it) },
                                onAiPlaylistCustomSystemPromptChange = { playerViewModel.setAiPlaylistCustomSystemPrompt(it) },
                                onAiPlaylistApiKeySave = { playerViewModel.saveAiPlaylistApiKey(it) },
                                onAiPlaylistApiKeyClear = { playerViewModel.clearAiPlaylistApiKey() },
                                onOpenAiDebugSettings = playerViewModel::openAiDebugSettings,
                                onLastFmApiKeySave = { playerViewModel.saveLastFmApiKey(it) },
                                onLastFmApiKeyClear = { playerViewModel.clearLastFmApiKey() },
                                onLastFmApiTest = { playerViewModel.testLastFmApi() },
                                onLastFmMetadataSync = { playerViewModel.startLastFmMetadataSync() },
                                onMusicBrainzCoverSync = { playerViewModel.startMusicBrainzCoverSync() },
                                contentPadding = entryContentPadding
                            )
                            AppRoute.AiDebugSettings -> AiDebugSettingsScreen(
                                settingsState = settingsState,
                                librarySummary = libraryState.librarySummary,
                                onBackClick = onContentBack,
                                onModeChange = { playerViewModel.setDailyPlaylistGenerationMode(it) },
                                onProviderChange = { playerViewModel.setAiPlaylistProvider(it) },
                                onModelChange = { playerViewModel.setAiPlaylistModel(it) },
                                onPromptPresetChange = { playerViewModel.setAiPlaylistPromptPreset(it) },
                                onCustomPromptChange = { playerViewModel.setAiPlaylistCustomSystemPrompt(it) },
                                onApiKeySave = { playerViewModel.saveAiPlaylistApiKey(it) },
                                onApiKeyClear = { playerViewModel.clearAiPlaylistApiKey() },
                                onAllApiKeysClear = { playerViewModel.clearAllAiPlaylistApiKeys() },
                                onApiTest = { playerViewModel.testAiPlaylistApi() },
                                onResponseTest = { playerViewModel.testAiPlaylistModelResponse() },
                                onForceGenerateDailyPlaylist = { playerViewModel.forceGenerateDailyPlaylist() },
                                onPromptSend = { playerViewModel.sendAiPrompt(it) },
                                contentPadding = entryContentPadding
                            )
                            AppRoute.Search -> LibrarySearchScreen(
                                libraryState = libraryState,
                                currentTrackId = playbackState.currentTrack?.id,
                                onTrackClick = playerViewModel::playFromVisibleTracks,
                                onBackClick = onContentBack,
                                onLoadNextSearch = { playerViewModel.loadNextSearchPage() },
                                contentPadding = entryContentPadding
                            )
                                AppRoute.Player,
                                AppRoute.Queue -> Unit
                            }
                        }
                    }
                }
            }
        }
            contentBackSession?.let { session ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { }
                        .pointerInput(session.originTopEntryId, session.phase) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { change ->
                                        change.consume()
                                    }
                                }
                            }
                        },
                )
            }
            PlayerOverlayHost(
                playbackState = playbackState,
                topEntryId = navigationState.currentEntry.entryId,
                isPlayerVisible = navigationState.contains(AppRoute.Player) ||
                    navigationState.contains(AppRoute.Queue),
                isQueueVisible = navigationState.currentDestination == AppRoute.Queue,
                onNavigateBack = { entryId ->
                    playerViewModel.navigateBack(expectedTopEntryId = entryId)
                },
                onOpenQueueSheet = playerViewModel::openQueueSheet,
                onPreviousClick = playerViewModel::playPrevious,
                onNextClick = playerViewModel::playNext,
                onPlayPauseClick = {
                    if (playbackState.isPlaying) playerViewModel.pause() else playerViewModel.resume()
                },
                onShuffleClick = playerViewModel::toggleShuffle,
                onRepeatClick = playerViewModel::toggleRepeat,
                onFavoriteClick = playerViewModel::favorite,
                onSeek = playerViewModel::seekTo,
                onQueueTrackClick = playerViewModel::playQueueItem,
                onMoveTrack = playerViewModel::moveTrack,
                onClearQueueClick = playerViewModel::clearQueue,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BottomDock(
    activeMainDestination: MainDestination,
    librarySearch: org.milkdev.dreamplayer.library.LibrarySearchState,
    onHomeClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = AppTheme.spacing.medium)
    ) {
        val searchBoundsState = rememberSharedContentState(key = "bottom_dock_search_bounds")
        val searchIconState = rememberSharedContentState(key = "bottom_dock_search_icon")

        AnimatedContent(
            targetState = activeMainDestination,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 40)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 160))
            },
            contentKey = { it == MainDestination.Search },
        ) { destination ->
            if (destination == MainDestination.Search) {
                this@SharedTransitionLayout.SearchDock(
                    query = librarySearch.query,
                    onQueryChange = onSearchQueryChange,
                    onCloseClick = onCloseSearch,
                    containerModifier = Modifier.sharedBounds(
                        sharedContentState = searchBoundsState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                        clipInOverlayDuringTransition = OverlayClip(CircleShape)
                    ),
                    searchIconModifier = Modifier.sharedElement(
                        sharedContentState = searchIconState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                    ),
                )
            } else {
                NavigationDock(
                    activeMainDestination = destination,
                    onHomeClick = onHomeClick,
                    onLibraryClick = onLibraryClick,
                    onSearchClick = onOpenSearch,
                    searchButtonModifier = Modifier.sharedBounds(
                        sharedContentState = searchBoundsState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                        clipInOverlayDuringTransition = OverlayClip(CircleShape)
                    ),
                    searchIconModifier = Modifier.sharedElement(
                        sharedContentState = searchIconState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                    ),
                )
            }
        }
    }
}

private val DockBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = 420)
}

@Composable
fun PlayerBar(
    currentTrack: LibraryTrack?,
    isPlaying: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    barRadius: Dp = 32.dp
) {
    val spacing = AppTheme.spacing
    val barShape = RoundedCornerShape(barRadius)

    val albumArtPadding = spacing.large
    val albumArtShape = barRadius.nestedShape(padding = albumArtPadding)

    val buttonPadding = spacing.small
    // val playPauseShape = barRadius.nestedShape(padding = buttonPadding)

    Surface(
        onClick = onClick,
        shape = barShape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = albumArtPadding,
                    top = buttonPadding,
                    end = buttonPadding,
                    bottom = buttonPadding
                ),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(albumArtShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                TrackImage(
                    uri = currentTrack?.albumArtUri,
                    modifier = Modifier.fillMaxSize(),
                    maxDecodeSizePx = 160,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = currentTrack?.title ?: "Название трека",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTheme.typography.snPro.titleMedium
                )
                Text(
                    text = currentTrack?.artistName ?: "Исполнитель",
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTheme.typography.snPro.bodyMedium
                )
            }

            PlayerIconButton(
                icon = Res.drawable.skip_previous,
                contentDescription = "Предыдущий трек",
                onClick = onPreviousClick
            )

            PlayerIconButton(
                icon = Res.drawable.skip_next,
                contentDescription = "Следующий трек",
                onClick = onNextClick
            )

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(M3ECookie9SidedShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .shapeClickableWithFeedback(
                        shape = M3ECookie9SidedShape,
                        onClickDelay = ClickDelays.Play,
                        borderDuration = BorderDelays.ExtendedBorder,
                        fadeOutDuration = BorderDelays.ExpressiveFastFade,
                        onClick = onPlayPauseClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) Res.drawable.pause else Res.drawable.play_arrow),
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun PlayerIconButton(
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp)
        )
    }
}
