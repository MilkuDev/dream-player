package org.milkdev.dreamplayer.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.DeferredAnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
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
    val detailPresentationState by playerViewModel.detailPresentationState.collectAsState()
    val settingsState by playerViewModel.settingsState.collectAsState()
    val navigationSnapshot by playerViewModel.navigationSnapshot.collectAsState()
    val navigationState = navigationSnapshot.state
    val committedContentSnapshot = contentSceneSnapshot(navigationState.backStack)
    val contentPresentationController = remember {
        ContentNavigationPresentationController(committedContentSnapshot)
    }
    val appBackGestureCoordinator = remember { AppBackGestureCoordinator() }
    val playbackOverlayBackDispatcher = remember { PlaybackOverlayBackDispatcher() }
    val contentTransitionState = contentPresentationController.transitionState
    val contentTransition = rememberTransition(
        transitionState = contentTransitionState,
        label = "ContentNavigation",
    )
    val contentPresentationState = contentPresentationController.state
    val contentBackSession = contentPresentationState.backSession
    val mainTabCarouselState = remember {
        MainTabCarouselState(navigationState.activeMainTab)
    }
    val animateMainTabSelection = isDirectRootTabSwitch(
        navigationSnapshot.lastTransaction,
    )
    LaunchedEffect(committedContentSnapshot) {
        contentPresentationController.onCommittedSnapshotChanged(
            snapshot = committedContentSnapshot,
            transaction = navigationSnapshot.lastTransaction,
        )
    }

    LaunchedEffect(
        navigationState.currentEntry.entryId,
        navigationSnapshot.revision,
    ) {
        val staleSession = contentPresentationController.invalidateIfOriginChanged(
            currentTopEntryId = navigationState.currentEntry.entryId,
            currentRevision = navigationSnapshot.revision,
            committedSnapshot = committedContentSnapshot,
        )
        if (staleSession != null) {
            AppDebugLog.log(
                "predictive_back_settled surface=content result=stale_route " +
                    "entryId=${staleSession.originTopEntryId}",
            )
        }
    }

    fun logContentBackCancellation(
        completion: ContentTransitionCompletion.Cancelled,
    ) {
        AppDebugLog.log(
            "predictive_back_settled surface=content result=cancelled " +
                "entryId=${completion.session.originTopEntryId}",
        )
    }

    fun commitContentBack(
        completion: ContentTransitionCompletion.CommitReady,
    ) {
        val readyLogEvent = when (completion.session.source) {
            ContentBackSource.Platform -> "predictive_back_transition_ready"
            ContentBackSource.Ui -> "content_back_transition_ready"
        }
        AppDebugLog.log(
            "$readyLogEvent surface=content " +
                "entryId=${completion.session.originTopEntryId} " +
                "origin=${completion.session.origin.currentEntry.route} " +
                "preview=${completion.session.preview.currentEntry.route} " +
                "mode=${completion.session.mode.name.lowercase()} " +
                "events=${completion.session.progressEventCount} " +
                "maxProgress=${completion.session.maxProgress}",
        )
        val didPop = playerViewModel.commitBack(completion.session.backPlan)
        contentPresentationController.onCommitPopCompleted(
            sessionId = completion.session.sessionId,
            didPop = didPop,
            recoveryTarget = contentSceneSnapshot(
                playerViewModel.navigationSnapshot.value.state.backStack,
            ),
        )
        val logEvent = when (completion.session.source) {
            ContentBackSource.Platform -> "predictive_back_settled"
            ContentBackSource.Ui -> "content_back_settled"
        }
        AppDebugLog.log(
            "$logEvent surface=content " +
                "result=${if (didPop) "committed" else "stale"} " +
                "entryId=${completion.session.originTopEntryId}",
        )
    }

    val cancellingDeferredSessionId = (
        contentPresentationState as? ContentNavigationPresentationState.Cancelling
        )?.session
        ?.takeUnless {
            it.mode == ContentBackMode.Predictive &&
                it.motionStyle == PredictiveBackMotionStyle.Stack
        }
        ?.sessionId
    LaunchedEffect(cancellingDeferredSessionId) {
        cancellingDeferredSessionId?.let { sessionId ->
            contentPresentationController.settleCancellation(sessionId)
                ?.let { completion -> logContentBackCancellation(completion) }
        }
    }

    val committingMainTabCarouselSessionId = (
        contentPresentationState as? ContentNavigationPresentationState.Committing
        )?.session
        ?.takeIf { it.motionStyle == PredictiveBackMotionStyle.MainTabCarousel }
        ?.sessionId
    LaunchedEffect(committingMainTabCarouselSessionId) {
        committingMainTabCarouselSessionId?.let { sessionId ->
            contentPresentationController.settleMainTabCarouselCommit(sessionId)
                ?.let { completion -> commitContentBack(completion) }
        }
    }

    LaunchedEffect(
        contentBackSession?.sessionId,
        contentPresentationState is ContentNavigationPresentationState.Animating,
        contentPresentationState is ContentNavigationPresentationState.Cancelling,
        contentPresentationState is ContentNavigationPresentationState.Committing,
        contentTransition.currentState,
        contentTransition.targetState,
        contentTransition.pendingTargetState,
        contentTransition.isRunning,
    ) {
        when (
            val completion = contentPresentationController.onTransitionObserved(
                currentState = contentTransition.currentState,
                targetState = contentTransition.targetState,
                pendingTargetState = contentTransition.pendingTargetState,
                isRunning = contentTransition.isRunning,
            )
        ) {
            is ContentTransitionCompletion.Cancelled ->
                logContentBackCancellation(completion)

            is ContentTransitionCompletion.CommitReady ->
                commitContentBack(completion)

            null -> Unit
        }
    }

    LaunchedEffect(
        navigationState.backStack.map { it.entryId },
        contentBackSession?.sessionId,
        contentTransition.currentState,
        contentTransition.targetState,
        contentTransition.pendingTargetState,
        contentTransition.isRunning,
    ) {
        val isCommittedSceneSettled =
            contentBackSession == null &&
                !contentTransition.isRunning &&
                contentTransition.pendingTargetState == null &&
                contentTransition.currentState.scene == committedContentSnapshot &&
                contentTransition.targetState.scene == committedContentSnapshot
        if (isCommittedSceneSettled) {
            playerViewModel.retainDetailPresentations(
                navigationState.backStack.mapTo(mutableSetOf()) { it.entryId },
            )
        }
    }

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            playerViewModel.loadLibrary()
        }
    }

    val hazeState = rememberHazeState()

    AppTheme(darkTheme = settingsState.isForceNightMode || androidx.compose.foundation.isSystemInDarkTheme()) {
        PlatformBackHandler(
            enabled = navigationState.canNavigateBack,
            onBackStarted = {
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
                        val session = contentPresentationController.startPredictiveBack(
                            backPlan = gesture.backPlan,
                            origin = contentSceneSnapshot(origin.backStack),
                            preview = contentSceneSnapshot(gesture.backPlan.targetState.backStack),
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
                                    "motionId=${session.previewFrame.motionContext?.transitionId}",
                            )
                            true
                        } else {
                            false
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
                        contentPresentationController.progressPredictiveBack(
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
                when (appBackGestureCoordinator.finish()?.surface) {
                    AppBackSurface.Content -> {
                        contentPresentationController.cancelPredictiveBack()?.let { session ->
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
                when (appBackGestureCoordinator.finish()?.surface) {
                    AppBackSurface.Content -> {
                        when (
                            val result = contentPresentationController.commitPredictiveBack(hadProgress)
                        ) {
                            ContentBackCommitResult.NoSession,
                            ContentBackCommitResult.Ignored -> Unit

                            is ContentBackCommitResult.Animated -> AppDebugLog.log(
                                "predictive_back_commit surface=content " +
                                    "entryId=${result.session.originTopEntryId} " +
                                    "hadProgress=$hadProgress " +
                                    "mode=${result.session.mode.name.lowercase()} " +
                                    "events=${result.session.progressEventCount} " +
                                    "maxProgress=${result.session.maxProgress}",
                            )
                        }
                    }

                    AppBackSurface.Player,
                    AppBackSurface.Queue -> playbackOverlayBackDispatcher.commit(hadProgress)
                    null -> Unit
                }
            },
        )

        val onContentBack: () -> Unit = {
            if (
                contentPresentationController.canRequestContentBack(
                    isTransitionRunning = contentTransition.isRunning,
                )
            ) {
                val originSnapshot = playerViewModel.navigationSnapshot.value
                val backPlan = playerViewModel.planBack()
                val session = backPlan?.let { plan ->
                    contentPresentationController.startTimeDrivenBack(
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

        val spacing = AppTheme.spacing
        val playerSidePadding = 16.dp
        val playerTopPadding = 16.dp

        val playerBarRadius = 32.dp
        val searchBarRadius = 24.dp
        val defaultDockRadius = 20.dp
        val contentChromeLayers = resolveContentChromeLayers(
            committedScene = committedContentSnapshot,
            backSession = contentBackSession,
        )

        @Composable
        fun NavigationChrome(
            presentation: NavigationChromePresentation,
            interactive: Boolean,
            chromeHazeState: HazeState,
        ) {
            val activeTopRadius = when {
                playbackState.currentTrack != null -> playerBarRadius
                presentation.isSearchActive -> searchBarRadius
                else -> defaultDockRadius
            }
            val animatedBlurContainerRadius by animateDpAsState(
                targetValue = activeTopRadius + playerSidePadding,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            val enabledAction: (() -> Unit) -> () -> Unit = { action ->
                if (interactive) action else ({})
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .disableClicks()
                    .then(
                        if (interactive) {
                            Modifier
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    )
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
                Spacer(modifier = Modifier.height(playerTopPadding))

                if (playbackState.currentTrack != null) {
                    PlayerBar(
                        currentTrack = playbackState.currentTrack,
                        isPlaying = playbackState.isPlaying,
                        onPreviousClick = enabledAction { playerViewModel.playPrevious() },
                        onNextClick = enabledAction { playerViewModel.playNext() },
                        onPlayPauseClick = enabledAction {
                            if (playbackState.isPlaying) {
                                playerViewModel.pause()
                            } else {
                                playerViewModel.resume()
                            }
                        },
                        onClick = enabledAction { playerViewModel.openPlayer() },
                        modifier = Modifier.padding(horizontal = playerSidePadding),
                        barRadius = playerBarRadius,
                    )
                }

                Spacer(modifier = Modifier.height(spacing.medium))

                BottomDock(
                    activeMainTab = presentation.activeMainTab,
                    isSearchActive = presentation.isSearchActive,
                    librarySearch = libraryState.librarySearch,
                    onHomeClick = enabledAction {
                        playerViewModel.selectMainTab(MainTab.Home)
                    },
                    onLibraryClick = enabledAction {
                        playerViewModel.selectMainTab(MainTab.Library)
                    },
                    onOpenSearch = enabledAction { playerViewModel.openLibrarySearch() },
                    onCloseSearch = enabledAction(onContentBack),
                    onSearchQueryChange = { query ->
                        if (interactive) playerViewModel.updateLibrarySearchQuery(query)
                    },
                )
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }

        val predictiveStackSession = contentBackSession
            ?.takeIf { it.mode == ContentBackMode.Predictive }
            ?.takeIf { it.motionStyle == PredictiveBackMotionStyle.Stack }
        val saveableStateHolder = rememberSaveableStateHolder()
        val latestBackStack by rememberUpdatedState(navigationState.backStack)

        @Composable
        fun ContentScene(
            targetFrame: ContentTransitionFrame,
            persistentPadding: PaddingValues,
            ownsChrome: Boolean,
            retainsSaveableState: Boolean = true,
        ) {
            val targetSnapshot = targetFrame.scene
            val targetEntry = targetSnapshot.currentEntry
            val targetTab = targetEntry.route.toMainTabOrNull()
            DisposableEffect(targetEntry.entryId) {
                onDispose {
                    val hasStablePresentationIdentity =
                        targetEntry.route.toMainTabOrNull() != null
                    if (
                        !hasStablePresentationIdentity &&
                        latestBackStack.none { it.entryId == targetEntry.entryId }
                    ) {
                        saveableStateHolder.removeState(targetEntry.entryId)
                    }
                }
            }
            ContentSceneHost(
                scene = targetSnapshot,
                committedScene = committedContentSnapshot,
                backSession = contentBackSession,
                chromeLayers = contentChromeLayers,
                persistentPadding = persistentPadding,
                ownsChrome = ownsChrome,
                navigationChrome = { chrome, chromeHazeState ->
                    NavigationChrome(
                        presentation = chrome,
                        interactive = false,
                        chromeHazeState = chromeHazeState,
                    )
                },
            ) { entryContentPadding ->
                if (targetTab != null) {
                    MainTabCarouselHost(
                        state = mainTabCarouselState,
                        activeTab = navigationState.activeMainTab,
                        animateActiveTab = animateMainTabSelection,
                        backSession = contentBackSession,
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
                    val detailContent: @Composable () -> Unit = {
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
                    if (retainsSaveableState) {
                        saveableStateHolder.SaveableStateProvider(targetEntry.entryId) {
                            detailContent()
                        }
                    } else {
                        detailContent()
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    if (predictiveStackSession == null) {
                        contentChromeLayers.persistent?.let { chrome ->
                            NavigationChrome(
                                presentation = chrome,
                                interactive = contentBackSession == null,
                                chromeHazeState = hazeState,
                            )
                        }
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    PredictiveStackHost(
                        session = predictiveStackSession,
                        presentationState = contentPresentationState,
                        onCancellationSettled = { sessionId ->
                            contentPresentationController
                                .onPredictiveStackCancellationSettled(sessionId)
                                ?.let { completion ->
                                    logContentBackCancellation(completion)
                                }
                        },
                        onCommitVisualSettled = { sessionId ->
                            contentPresentationController
                                .onPredictiveStackVisualSettled(sessionId)
                        },
                        onCommitHandoffSettled = { sessionId ->
                            contentPresentationController
                                .onPredictiveStackHandoffSettled(sessionId)
                        },
                        previewContent = { session ->
                            // The backing host claims the saveable key during the invisible handoff.
                            ContentScene(
                                targetFrame = session.previewFrame,
                                persistentPadding = paddingValues,
                                ownsChrome = true,
                                retainsSaveableState = false,
                            )
                        },
                        originContent = { ownsChrome ->
                            contentTransition.DeferredAnimatedContent(
                                transitionSpec = {
                                    val session = contentBackSession
                                    when {
                                        session == null -> navigationContentTransform(
                                            initial = initialState.scene,
                                            target = targetState.scene,
                                            context = targetState.motionContext,
                                        )

                                        session.motionStyle ==
                                            PredictiveBackMotionStyle.MainTabCarousel ->
                                            predictiveBackCancelContentTransform(
                                                target = targetState.scene,
                                                motionStyle =
                                                    PredictiveBackMotionStyle.MainTabCarousel,
                                            )

                                        session.mode == ContentBackMode.TimeDriven ->
                                            navigationContentTransform(
                                                initial = initialState.scene,
                                                target = targetState.scene,
                                                context = targetState.motionContext,
                                            )

                                        else -> predictiveStackContentTransform(
                                            target = targetState.scene,
                                        )
                                    }
                                },
                                contentKey = ::mainTabCarouselContentKey,
                            ) { targetFrame ->
                                ContentScene(
                                    targetFrame = targetFrame,
                                    persistentPadding = paddingValues,
                                    ownsChrome = ownsChrome,
                                )
                            }
                        },
                    )
                }
            }
            contentBackSession?.let { session ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { }
                        .pointerInput(session.sessionId) {
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BottomDock(
    activeMainTab: MainTab,
    isSearchActive: Boolean,
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
            targetState = isSearchActive,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 40)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 160))
            },
            contentKey = { it },
        ) { searchActive ->
            if (searchActive) {
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
