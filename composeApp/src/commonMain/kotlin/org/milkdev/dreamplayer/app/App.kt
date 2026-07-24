package org.milkdev.dreamplayer.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeState
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
import org.milkdev.dreamplayer.model.LibraryCollectionDetailUiState
import org.milkdev.dreamplayer.model.PlayerViewModel
import org.milkdev.dreamplayer.model.PlaylistDetailUiState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainTab
import org.milkdev.dreamplayer.navigation.toMainTabOrNull
import org.milkdev.dreamplayer.ui.*
import kotlin.math.roundToInt

private val playerViewModelInstance = PlayerViewModel()

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun App(
    playerViewModel: PlayerViewModel = playerViewModelInstance,
    isPermissionGranted: Boolean = true
) {
    val playbackState by playerViewModel.playbackState.collectAsState()
    val libraryState by playerViewModel.libraryState.collectAsState()
    val detailPresentationState by playerViewModel.detailPresentationState.collectAsState()
    val settingsState by playerViewModel.settingsState.collectAsState()
    val navigationSnapshot by playerViewModel.navigationSnapshot.collectAsState()
    val navigationState = navigationSnapshot.state
    val committedContentScenes = contentSceneSnapshots(navigationState.backStack)
    val retainedContentState = rememberRetainedContentStackState(
        initialNavigationRevision = navigationSnapshot.revision,
    )
    val appBackGestureCoordinator = remember { AppBackGestureCoordinator() }
    val playbackOverlayBackDispatcher = remember { PlaybackOverlayBackDispatcher() }
    val presentationOwnerEpoch = remember(playerViewModel) {
        playerViewModel.acquirePresentationOwner()
    }
    DisposableEffect(playerViewModel, presentationOwnerEpoch) {
        onDispose {
            playerViewModel.releasePresentationOwner(presentationOwnerEpoch)
        }
    }
    val navigationForegroundOwner = when (navigationState.currentDestination) {
        AppRoute.Player -> ForegroundOwner.Player
        AppRoute.Queue -> ForegroundOwner.Queue
        else -> ForegroundOwner.Content
    }
    var foregroundPresentation: ForegroundPresentation by remember {
        mutableStateOf(ForegroundPresentation.Settled(navigationForegroundOwner))
    }
    val effectiveForegroundPresentation = if (
        foregroundPresentation ==
        ForegroundPresentation.Settled(ForegroundOwner.Content) &&
        navigationForegroundOwner != ForegroundOwner.Content
    ) {
        ForegroundPresentation.Transitioning(
            from = ForegroundOwner.Content,
            to = navigationForegroundOwner,
            token = navigationSnapshot.revision,
        )
    } else {
        foregroundPresentation
    }
    val contentBackSession = retainedContentState.backSession
    val mainTabCarouselState = remember {
        MainTabCarouselState(navigationState.activeMainTab)
    }
    val animateMainTabSelection = isDirectRootTabSwitch(
        navigationSnapshot.lastTransaction,
    )
    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            playerViewModel.loadLibrary()
        }
    }

    AppTheme(darkTheme = settingsState.isForceNightMode || androidx.compose.foundation.isSystemInDarkTheme()) {
        val globalChromePresentation = resolveGlobalChromePresentation(navigationState)
        val globalHazeState = rememberHazeState()
        val predictiveDockProgress = contentBackSession
            ?.takeIf { session -> session.mode == ContentBackMode.Predictive }
            ?.let { session ->
                predictiveDockPresenceProgress(
                    originPresented = contentStackPresentsMainDock(session.origin),
                    previewPresented = contentStackPresentsMainDock(session.preview),
                    progress = retainedContentState.visualBackProgress,
                )
            }
        val contentPresentationSettled =
            retainedContentState.isSettledAt(navigationSnapshot.revision)
        val globalChromePolicy = resolveGlobalChromeExecutionPolicy(
            foregroundPresentation = effectiveForegroundPresentation,
            contentPresentationSettled = contentPresentationSettled,
            isDockPresented = globalChromePresentation.isDockPresented,
            authorityEpoch = retainedContentState.authorityEpoch,
        )
        val contentBackReady =
            contentPresentationSettled &&
                retainedContentState.canRequestBack() &&
                effectiveForegroundPresentation ==
                ForegroundPresentation.Settled(ForegroundOwner.Content)
        val startTimeDrivenContentBack: () -> Unit = {
            if (contentBackReady) {
                val originSnapshot = playerViewModel.navigationSnapshot.value
                val backPlan = playerViewModel.planBack()
                val session = backPlan?.let { plan ->
                    retainedContentState.startTimeDrivenBack(
                        backPlan = plan,
                        origin = contentSceneSnapshot(originSnapshot.state.backStack),
                        preview = contentSceneSnapshot(plan.targetState.backStack),
                    )
                }
                if (session != null) {
                    AppDebugLog.log(
                        "content_back_start surface=content mode=time_driven " +
                            "entryId=${session.originTopEntryId}",
                    )
                }
            }
        }
        LaunchedEffect(
            retainedContentState.hasQueuedTimeDrivenBack,
            contentBackReady,
            navigationSnapshot.revision,
        ) {
            if (retainedContentState.takeQueuedTimeDrivenBackIf(contentBackReady)) {
                startTimeDrivenContentBack()
            }
        }

        PlatformBackHandler(
            // Keep the handler registered while a presentation transition is running.
            // Its callbacks validate/queue against fresh session state instead of
            // allowing an early platform Back to fall through to the Activity.
            enabled = navigationState.canNavigateBack,
            onBackStarted = {
                retainedContentState.cancelDeferredBackGesture()
                val originSnapshot = playerViewModel.navigationSnapshot.value
                val origin = originSnapshot.state
                val surface = when (origin.currentDestination) {
                    AppRoute.Player -> AppBackSurface.Player
                    AppRoute.Queue -> AppBackSurface.Queue
                    else -> AppBackSurface.Content
                }
                val backPlan = playerViewModel.planBack()
                val gesture = backPlan?.let { plan ->
                    AppBackGesture(
                        surface = surface,
                        backPlan = plan,
                    )
                }
                val accepted = when {
                    gesture == null -> false
                    surface == AppBackSurface.Content -> {
                        if (!contentBackReady) {
                            retainedContentState.beginDeferredBackGesture()
                            false
                        } else {
                            val session = retainedContentState.startPredictiveBack(
                                backPlan = gesture.backPlan,
                                origin = contentSceneSnapshot(origin.backStack),
                                preview = contentSceneSnapshot(
                                    gesture.backPlan.targetState.backStack,
                                ),
                            )
                            if (session != null) {
                                AppDebugLog.log(
                                    "predictive_back_start surface=content " +
                                        "entryId=${session.originTopEntryId} " +
                                        "revision=${session.originRevision} " +
                                        "origin=${session.origin.currentEntry.route} " +
                                        "preview=${session.preview.currentEntry.route} " +
                                        "originLayer=${session.origin.contentLayer} " +
                                        "previewLayer=${session.preview.contentLayer} " +
                                        "motionId=" +
                                        session.previewFrame.motionContext?.transitionId,
                                )
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> playbackOverlayBackDispatcher.start(gesture)
                }
                appBackGestureCoordinator.begin(
                    gesture = gesture,
                    acceptedBySurface = accepted,
                )
            },
            onBackProgressed = { event ->
                when (appBackGestureCoordinator.routedGesture?.surface) {
                    AppBackSurface.Content -> {
                        val currentNavigation = playerViewModel.navigationSnapshot.value
                        retainedContentState.progressPredictiveBack(
                            event = event,
                            currentTopEntryId = currentNavigation.state.currentEntry.entryId,
                            currentRevision = currentNavigation.revision,
                        )
                    }

                    AppBackSurface.Player,
                    AppBackSurface.Queue -> playbackOverlayBackDispatcher.progress(event)
                    null -> Unit
                }
            },
            onBackCancelled = {
                retainedContentState.cancelDeferredBackGesture()
                when (appBackGestureCoordinator.finish()?.surface) {
                    AppBackSurface.Content -> {
                        retainedContentState.cancelPredictiveBack()?.let { session ->
                            AppDebugLog.log(
                                "predictive_back_cancel surface=content " +
                                    "entryId=${session.originTopEntryId}",
                            )
                        }
                    }

                    AppBackSurface.Player,
                    AppBackSurface.Queue -> playbackOverlayBackDispatcher.cancel()
                    null -> Unit
                }
            },
            onBackCommitted = { hadProgress ->
                val routedGesture = appBackGestureCoordinator.finish()
                if (routedGesture == null) {
                    retainedContentState.commitDeferredBackGesture()
                }
                when (routedGesture?.surface) {
                    AppBackSurface.Content -> {
                        retainedContentState.commitPredictiveBack(hadProgress)
                            ?.let { session ->
                                AppDebugLog.log(
                                    "predictive_back_commit surface=content " +
                                        "entryId=${session.originTopEntryId} " +
                                        "hadProgress=$hadProgress " +
                                        "mode=${session.mode.name.lowercase()} " +
                                        "events=${session.progressEventCount} " +
                                        "maxProgress=${session.maxProgress}",
                                )
                            }
                    }

                    AppBackSurface.Player,
                    AppBackSurface.Queue -> playbackOverlayBackDispatcher.commit(hadProgress)
                    null -> Unit
                }
            },
        )

        val onContentBack: () -> Unit = startTimeDrivenContentBack

        val spacing = AppTheme.spacing
        val playerSidePadding = 16.dp
        val playerTopPadding = 16.dp

        val playerBarRadius = 32.dp
        val searchBarRadius = 24.dp
        val defaultDockRadius = 20.dp

        @Composable
        fun GlobalNavigationChrome(
            presentation: GlobalChromePresentation,
            executionPolicy: GlobalChromeExecutionPolicy,
            chromeHazeState: HazeState,
            predictivePresenceProgress: Float?,
        ) {
            val dockTargetProgress = if (presentation.isDockPresented) 1f else 0f
            val dockProgress = remember {
                Animatable(dockTargetProgress)
            }
            var dockAnimationReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                dockAnimationReady = true
            }
            LaunchedEffect(
                dockTargetProgress,
                dockAnimationReady,
                predictivePresenceProgress != null,
            ) {
                if (predictivePresenceProgress != null) return@LaunchedEffect
                if (dockAnimationReady) {
                    dockProgress.updateBounds(
                        lowerBound = 0f,
                        upperBound = 1f,
                    )
                    dockProgress.animateTo(
                        targetValue = dockTargetProgress,
                        animationSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = 0.001f,
                        ),
                    )
                } else {
                    dockProgress.snapTo(dockTargetProgress)
                }
            }
            LaunchedEffect(predictivePresenceProgress) {
                if (predictivePresenceProgress != null) {
                    dockProgress.snapTo(predictivePresenceProgress)
                }
            }
            val effectiveDockProgress =
                predictivePresenceProgress ?: dockProgress.value
            val activeMainTab = when (val dock = presentation.dockContent) {
                is GlobalDockContent.Navigation -> dock.activeMainTab
                is GlobalDockContent.Search -> dock.activeMainTab
            }
            val searchEntryId =
                (presentation.dockContent as? GlobalDockContent.Search)?.entryId
            val activeTopRadius = when {
                playbackState.currentTrack != null -> playerBarRadius
                searchEntryId != null -> searchBarRadius
                else -> defaultDockRadius
            }
            val animatedBlurContainerRadius by animateDpAsState(
                targetValue = activeTopRadius + playerSidePadding,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            val miniPlayerAction: (() -> Unit) -> () -> Unit = { action ->
                if (executionPolicy.allowsMiniPlayerInput) action else ({})
            }
            val dockAction: (() -> Unit) -> () -> Unit = { action ->
                if (executionPolicy.allowsDockInput) action else ({})
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .disableClicks()
                    .clip(
                        RoundedCornerShape(
                            topStart = animatedBlurContainerRadius,
                            topEnd = animatedBlurContainerRadius,
                        ),
                    )
                    .then(
                        if (settingsState.isBlurEnabled) {
                            Modifier.hazeEffect(chromeHazeState) {
                                blurRadius = 16.dp
                            }
                        } else {
                            Modifier.background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                            )
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (playbackState.currentTrack != null) {
                    Spacer(modifier = Modifier.height(playerTopPadding))
                    Box(
                        modifier = if (executionPolicy.allowsMiniPlayerInput) {
                            Modifier
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    ) {
                        PlayerBar(
                            currentTrack = playbackState.currentTrack,
                            isPlaying = playbackState.isPlaying,
                            onPreviousClick = miniPlayerAction {
                                playerViewModel.playPrevious()
                            },
                            onNextClick = miniPlayerAction { playerViewModel.playNext() },
                            onPlayPauseClick = miniPlayerAction {
                                if (playbackState.isPlaying) {
                                    playerViewModel.pause()
                                } else {
                                    playerViewModel.resume()
                                }
                            },
                            onClick = miniPlayerAction { playerViewModel.openPlayer() },
                            modifier = Modifier.padding(horizontal = playerSidePadding),
                            barRadius = playerBarRadius,
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.medium))
                }

                RetainedScaledHeight(
                    progress = effectiveDockProgress,
                    clearSemantics = !executionPolicy.allowsDockInput,
                ) {
                    Column {
                        if (playbackState.currentTrack == null) {
                            Spacer(modifier = Modifier.height(playerTopPadding))
                        }
                        BottomDock(
                            activeMainTab = activeMainTab,
                            searchEntryId = searchEntryId,
                            librarySearch = libraryState.librarySearch,
                            allowsSearchFocus = executionPolicy.allowsSearchFocus,
                            authorityEpoch = executionPolicy.authorityEpoch,
                            onHomeClick = dockAction {
                                playerViewModel.selectMainTab(MainTab.Home)
                            },
                            onLibraryClick = dockAction {
                                playerViewModel.selectMainTab(MainTab.Library)
                            },
                            onOpenSearch = dockAction {
                                playerViewModel.openLibrarySearch()
                            },
                            onCloseSearch = dockAction(onContentBack),
                            onSearchQueryChange = { query ->
                                if (executionPolicy.allowsDockInput) {
                                    playerViewModel.updateLibrarySearchQuery(query)
                                }
                            },
                        )
                        Spacer(
                            Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars),
                        )
                    }
                }
                if (playbackState.currentTrack != null) {
                    RetainedScaledHeight(progress = 1f - effectiveDockProgress) {
                        Spacer(
                            Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars),
                        )
                    }
                }
            }
        }

        @Composable
        fun ContentScene(
            targetSnapshot: ContentSceneSnapshot,
            executionPolicy: SceneExecutionPolicy,
            backSession: ContentBackSession?,
            contentPadding: PaddingValues,
        ) {
            val targetEntry = targetSnapshot.currentEntry
            val targetTab = targetEntry.route.toMainTabOrNull()
            ContentSceneHost(
                executionPolicy = executionPolicy,
                contentPadding = contentPadding,
            ) { entryContentPadding ->
                if (targetTab != null) {
                    MainTabCarouselHost(
                        state = mainTabCarouselState,
                        activeTab = targetTab,
                        animateActiveTab = animateMainTabSelection,
                        backSession = backSession?.takeIf {
                            it.motionStyle ==
                                PredictiveBackMotionStyle.MainTabCarousel
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        when (tab) {
                            MainTab.Home -> HomeScreen(
                                libraryState = libraryState,
                                onShuffleDailyPlaylistClick =
                                    playerViewModel::shuffleDailyPlaylist,
                                onOpenDailyPlaylistClick =
                                    playerViewModel::openDailyPlaylist,
                                contentPadding = entryContentPadding,
                                onSettingsClick = playerViewModel::openSettings,
                                onTrackClick = playerViewModel::playFromVisibleTracks,
                            )

                            MainTab.Library -> LibraryScreen(
                                libraryState = libraryState,
                                currentTrack = playbackState.currentTrack,
                                onIntent = playerViewModel::onLibraryIntent,
                                onSettingsClick = playerViewModel::openSettings,
                                contentPadding = entryContentPadding,
                            )
                        }
                    }
                } else {
                    val entryDetailState =
                        detailPresentationState.entry(targetEntry.entryId)

                    when (targetEntry.route) {
                            AppRoute.Home,
                            AppRoute.Library -> Unit

                            is AppRoute.Playlist -> {
                                if (entryDetailState is PlaylistDetailUiState) {
                                    PlaylistDetailsScreen(
                                        libraryState = libraryState,
                                        detailState = entryDetailState,
                                        currentTrackId = playbackState.currentTrack?.id,
                                        onBackClick = onContentBack,
                                        onTrackClick = { track ->
                                            playerViewModel.playFromPlaylistEntry(
                                                entryId = targetEntry.entryId,
                                                track = track,
                                            )
                                        },
                                        onSaveTracks = playerViewModel::savePlaylistTracks,
                                        onLoadNextPickerTracks = {
                                            playerViewModel.loadPlaylistPickerPage()
                                        },
                                        contentPadding = entryContentPadding,
                                    )
                                }
                            }

                            is AppRoute.LibraryCollection -> {
                                if (entryDetailState is LibraryCollectionDetailUiState) {
                                    LibraryCollectionDetailsScreen(
                                        detailState = entryDetailState,
                                        currentTrackId = playbackState.currentTrack?.id,
                                        onBackClick = onContentBack,
                                        onTrackClick = { track ->
                                            playerViewModel.playFromLibraryCollectionEntry(
                                                entryId = targetEntry.entryId,
                                                track = track,
                                            )
                                        },
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
                                onDailyPlaylistModeChange = {
                                    playerViewModel.setDailyPlaylistGenerationMode(it)
                                },
                                onAiPlaylistProviderChange = {
                                    playerViewModel.setAiPlaylistProvider(it)
                                },
                                onAiPlaylistModelChange = {
                                    playerViewModel.setAiPlaylistModel(it)
                                },
                                onAiPlaylistPromptPresetChange = {
                                    playerViewModel.setAiPlaylistPromptPreset(it)
                                },
                                onAiPlaylistCustomSystemPromptChange = {
                                    playerViewModel.setAiPlaylistCustomSystemPrompt(it)
                                },
                                onAiPlaylistApiKeySave = {
                                    playerViewModel.saveAiPlaylistApiKey(it)
                                },
                                onAiPlaylistApiKeyClear = {
                                    playerViewModel.clearAiPlaylistApiKey()
                                },
                                onOpenAiDebugSettings = playerViewModel::openAiDebugSettings,
                                onLastFmApiKeySave = {
                                    playerViewModel.saveLastFmApiKey(it)
                                },
                                onLastFmApiKeyClear = {
                                    playerViewModel.clearLastFmApiKey()
                                },
                                onLastFmApiTest = {
                                    playerViewModel.testLastFmApi()
                                },
                                onLastFmMetadataSync = {
                                    playerViewModel.startLastFmMetadataSync()
                                },
                                onMusicBrainzCoverSync = {
                                    playerViewModel.startMusicBrainzCoverSync()
                                },
                                contentPadding = entryContentPadding,
                            )

                            AppRoute.AiDebugSettings -> AiDebugSettingsScreen(
                                settingsState = settingsState,
                                librarySummary = libraryState.librarySummary,
                                onBackClick = onContentBack,
                                onModeChange = {
                                    playerViewModel.setDailyPlaylistGenerationMode(it)
                                },
                                onProviderChange = {
                                    playerViewModel.setAiPlaylistProvider(it)
                                },
                                onModelChange = {
                                    playerViewModel.setAiPlaylistModel(it)
                                },
                                onPromptPresetChange = {
                                    playerViewModel.setAiPlaylistPromptPreset(it)
                                },
                                onCustomPromptChange = {
                                    playerViewModel.setAiPlaylistCustomSystemPrompt(it)
                                },
                                onApiKeySave = { playerViewModel.saveAiPlaylistApiKey(it) },
                                onApiKeyClear = { playerViewModel.clearAiPlaylistApiKey() },
                                onAllApiKeysClear = {
                                    playerViewModel.clearAllAiPlaylistApiKeys()
                                },
                                onApiTest = { playerViewModel.testAiPlaylistApi() },
                                onResponseTest = {
                                    playerViewModel.testAiPlaylistModelResponse()
                                },
                                onForceGenerateDailyPlaylist = {
                                    playerViewModel.forceGenerateDailyPlaylist()
                                },
                                onPromptSend = { playerViewModel.sendAiPrompt(it) },
                                contentPadding = entryContentPadding,
                            )

                            AppRoute.Search -> LibrarySearchScreen(
                                libraryState = libraryState,
                                currentTrackId = playbackState.currentTrack?.id,
                                onTrackClick = playerViewModel::playFromVisibleTracks,
                                onBackClick = onContentBack,
                                onLoadNextSearch = {
                                    playerViewModel.loadNextSearchPage()
                                },
                                contentPadding = entryContentPadding,
                            )

                            AppRoute.Player,
                            AppRoute.Queue -> Unit
                    }
                }
            }
        }

        val focusManager = LocalFocusManager.current
        LaunchedEffect(
            retainedContentState.ordinaryTransition,
            effectiveForegroundPresentation,
        ) {
            val foregroundLeavingContent =
                (
                    effectiveForegroundPresentation as?
                        ForegroundPresentation.Transitioning
                    )
                    ?.from == ForegroundOwner.Content
            if (
                retainedContentState.ordinaryTransition != null ||
                foregroundLeavingContent
            ) {
                focusManager.clearFocus(force = true)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    GlobalNavigationChrome(
                        presentation = globalChromePresentation,
                        executionPolicy = globalChromePolicy,
                        chromeHazeState = globalHazeState,
                        predictivePresenceProgress = predictiveDockProgress,
                    )
                },
            ) { globalContentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(globalHazeState)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    RetainedContentStackHost(
                        authoritativeScenes = committedContentScenes,
                        navigationRevision = navigationSnapshot.revision,
                        navigationTransaction = navigationSnapshot.lastTransaction,
                        state = retainedContentState,
                        foregroundPresentation = effectiveForegroundPresentation,
                        presentationOwnerEpoch = presentationOwnerEpoch,
                        modifier = Modifier.fillMaxSize(),
                        onCommitBack = { session ->
                            focusManager.clearFocus(force = true)
                            val readyLogEvent = when (session.source) {
                                ContentBackSource.Platform ->
                                    "predictive_back_transition_ready"

                                ContentBackSource.Ui -> "content_back_transition_ready"
                            }
                            AppDebugLog.log(
                                "$readyLogEvent surface=content " +
                                    "entryId=${session.originTopEntryId} " +
                                    "origin=${session.origin.currentEntry.route} " +
                                    "preview=${session.preview.currentEntry.route}",
                            )
                            val didPop = playerViewModel.commitBack(session.backPlan)
                            val settledLogEvent = when (session.source) {
                                ContentBackSource.Platform -> "predictive_back_settled"
                                ContentBackSource.Ui -> "content_back_settled"
                            }
                            AppDebugLog.log(
                                "$settledLogEvent surface=content " +
                                    "result=${if (didPop) "committed" else "stale"} " +
                                    "entryId=${session.originTopEntryId}",
                            )
                            didPop
                        },
                        onSceneEvicted = { token, ownerEpoch ->
                            playerViewModel.acknowledgePresentationEviction(
                                token = token,
                                presentationOwnerEpoch = ownerEpoch,
                            )
                        },
                    ) { scene, policy, backSession ->
                        ContentScene(
                            targetSnapshot = scene,
                            executionPolicy = policy,
                            backSession = backSession,
                            contentPadding = globalContentPadding,
                        )
                    }
                }
            }
            if (retainedContentState.isTransitionRunning) {
                val transitionToken =
                    contentBackSession?.sessionId ?: navigationSnapshot.revision
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { }
                        .pointerInput(transitionToken) {
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
                navigationRevision = navigationSnapshot.revision,
                isPlayerVisible = navigationState.contains(AppRoute.Player) ||
                    navigationState.contains(AppRoute.Queue),
                isQueueVisible = navigationState.currentDestination == AppRoute.Queue,
                onForegroundPresentationChanged = { presentation ->
                    foregroundPresentation = presentation
                },
                onPlanBack = playerViewModel::planBack,
                onCommitBack = playerViewModel::commitBack,
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
                backDispatcher = playbackOverlayBackDispatcher,
            )
        }
    }
}

@Composable
private fun RetainedScaledHeight(
    progress: Float,
    modifier: Modifier = Modifier,
    clearSemantics: Boolean = false,
    content: @Composable () -> Unit,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    Layout(
        modifier = modifier
            .clipToBounds()
            .graphicsLayer {
                alpha = clampedProgress
            }
            .then(
                if (clearSemantics || clampedProgress == 0f) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier
                },
            ),
        content = content,
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(minHeight = 0))
        }
        val fullHeight = placeables.maxOfOrNull { placeable -> placeable.height } ?: 0
        val visibleHeight = (fullHeight * clampedProgress)
            .roundToInt()
            .coerceIn(0, constraints.maxHeight)
        layout(constraints.maxWidth, visibleHeight) {
            placeables.forEach { placeable ->
                placeable.place(0, 0)
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BottomDock(
    activeMainTab: MainTab,
    searchEntryId: Long?,
    librarySearch: org.milkdev.dreamplayer.library.LibrarySearchState,
    allowsSearchFocus: Boolean,
    authorityEpoch: Long,
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
            .then(
                if (searchEntryId != null) {
                    Modifier.imePadding()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp)
            .padding(bottom = AppTheme.spacing.medium)
    ) {
        val searchBoundsState = rememberSharedContentState(key = "bottom_dock_search_bounds")
        val searchIconState = rememberSharedContentState(key = "bottom_dock_search_icon")

        AnimatedContent(
            targetState = searchEntryId,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 40)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 160))
            },
            contentKey = { entryId -> entryId ?: NavigationDockContentKey },
        ) { activeSearchEntryId ->
            if (activeSearchEntryId != null) {
                this@SharedTransitionLayout.SearchDock(
                    query = librarySearch.query,
                    onQueryChange = onSearchQueryChange,
                    onCloseClick = onCloseSearch,
                    allowsFocusAndPopups = allowsSearchFocus,
                    authorityEpoch = authorityEpoch,
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
                    activeMainTab = activeMainTab,
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

private const val NavigationDockContentKey = "navigation"

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

    Surface(
        onClick = onClick,
        shape = barShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
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
