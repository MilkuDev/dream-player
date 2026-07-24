package org.milkdev.dreamplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.app.AppBackSurface
import org.milkdev.dreamplayer.app.ForegroundOwner
import org.milkdev.dreamplayer.app.ForegroundPresentation
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.navigation.NavigationPlan
import org.milkdev.dreamplayer.playback.PlaybackUiState
import kotlin.math.roundToInt

private enum class PlaybackOverlay {
    Player,
    Queue,
}

private enum class OverlayBackPhase {
    Tracking,
    Cancelling,
    Committing,
}

private data class OverlayBackSession(
    val backPlan: NavigationPlan,
    val overlay: PlaybackOverlay,
    val phase: OverlayBackPhase = OverlayBackPhase.Tracking,
    val progress: Float = 0f,
) {
    val expectedTopEntryId: Long
        get() = backPlan.expectedTopEntryId

    val expectedRevision: Long
        get() = backPlan.expectedRevision
}

@Composable
internal fun PlayerOverlayHost(
    playbackState: PlaybackUiState,
    topEntryId: Long,
    navigationRevision: Long,
    isPlayerVisible: Boolean,
    isQueueVisible: Boolean,
    onForegroundPresentationChanged: (ForegroundPresentation) -> Unit,
    onPlanBack: () -> NavigationPlan?,
    onCommitBack: (NavigationPlan) -> Boolean,
    onOpenQueueSheet: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onQueueTrackClick: (Int) -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onClearQueueClick: () -> Unit,
    backDispatcher: PlaybackOverlayBackDispatcher,
    modifier: Modifier = Modifier,
) {
    var retainedPlaybackState by remember { mutableStateOf<PlaybackUiState?>(null) }
    SideEffect {
        if (playbackState.currentTrack != null) {
            retainedPlaybackState = playbackState
        }
    }
    val renderedPlaybackState = playbackState.takeIf { it.currentTrack != null }
        ?: retainedPlaybackState

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val scope = rememberCoroutineScope()
        val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val playerProgress = remember { Animatable(1f) }
        val queueProgress = remember { Animatable(1f) }
        var renderPlayer by remember { mutableStateOf(false) }
        var renderQueue by remember { mutableStateOf(false) }
        var overlayBackSession by remember { mutableStateOf<OverlayBackSession?>(null) }
        var backTransitionInProgress by remember { mutableStateOf(false) }
        var lastSettledForegroundOwner by remember {
            mutableStateOf(ForegroundOwner.Content)
        }
        var foregroundTransitionToken by remember { mutableStateOf(0L) }
        val latestTopEntryId by rememberUpdatedState(topEntryId)
        val latestNavigationRevision by rememberUpdatedState(navigationRevision)
        val latestPlayerVisible by rememberUpdatedState(isPlayerVisible)
        val latestQueueVisible by rememberUpdatedState(isQueueVisible)

        val targetForegroundOwner = when {
            isQueueVisible -> ForegroundOwner.Queue
            isPlayerVisible -> ForegroundOwner.Player
            else -> ForegroundOwner.Content
        }
        val settledForegroundOwner = when (targetForegroundOwner) {
            ForegroundOwner.Content -> ForegroundOwner.Content.takeIf {
                !renderPlayer &&
                    !renderQueue &&
                    !backTransitionInProgress &&
                    overlayBackSession == null
            }

            ForegroundOwner.Player -> ForegroundOwner.Player.takeIf {
                renderPlayer &&
                    !renderQueue &&
                    !playerProgress.isRunning &&
                    !queueProgress.isRunning &&
                    !backTransitionInProgress &&
                    overlayBackSession == null
            }

            ForegroundOwner.Queue -> ForegroundOwner.Queue.takeIf {
                renderPlayer &&
                    renderQueue &&
                    !playerProgress.isRunning &&
                    !queueProgress.isRunning &&
                    !backTransitionInProgress &&
                    overlayBackSession == null
            }
        }
        LaunchedEffect(settledForegroundOwner, targetForegroundOwner) {
            if (settledForegroundOwner != null) {
                lastSettledForegroundOwner = settledForegroundOwner
                onForegroundPresentationChanged(
                    ForegroundPresentation.Settled(settledForegroundOwner),
                )
            } else {
                foregroundTransitionToken += 1L
                onForegroundPresentationChanged(
                    ForegroundPresentation.Transitioning(
                        from = lastSettledForegroundOwner,
                        to = targetForegroundOwner,
                        token = foregroundTransitionToken,
                    ),
                )
            }
        }

        suspend fun restoreOverlay(overlay: PlaybackOverlay) {
            val progress = when (overlay) {
                PlaybackOverlay.Player -> playerProgress
                PlaybackOverlay.Queue -> queueProgress
            }
            progress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }

        suspend fun finishOverlayBack(session: OverlayBackSession) {
            if (backTransitionInProgress) return
            backTransitionInProgress = true
            try {
                val progress = when (session.overlay) {
                    PlaybackOverlay.Player -> playerProgress
                    PlaybackOverlay.Queue -> queueProgress
                }
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = if (session.overlay == PlaybackOverlay.Queue) 240 else 280,
                    ),
                )
                if (!onCommitBack(session.backPlan)) {
                    restoreOverlay(session.overlay)
                }
            } finally {
                backTransitionInProgress = false
                overlayBackSession = null
            }
        }

        fun requestOverlayBack(overlay: PlaybackOverlay) {
            if (backTransitionInProgress || overlayBackSession != null) return
            val backPlan = onPlanBack() ?: return
            if (
                backPlan.expectedTopEntryId != latestTopEntryId ||
                backPlan.expectedRevision != latestNavigationRevision
            ) {
                return
            }
            scope.launch {
                finishOverlayBack(
                    OverlayBackSession(
                        backPlan = backPlan,
                        overlay = overlay,
                    )
                )
            }
        }

        LaunchedEffect(overlayBackSession?.phase) {
            val session = overlayBackSession ?: return@LaunchedEffect
            when (session.phase) {
                OverlayBackPhase.Tracking -> Unit

                OverlayBackPhase.Cancelling -> {
                    val cancelProgress = Animatable(session.progress.coerceIn(0f, 1f))
                    cancelProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) {
                        val currentSession = overlayBackSession
                        if (
                            currentSession?.expectedTopEntryId == session.expectedTopEntryId &&
                            currentSession.phase == OverlayBackPhase.Cancelling
                        ) {
                            overlayBackSession = currentSession.copy(progress = value)
                        }
                    }
                    val currentSession = overlayBackSession
                    if (
                        currentSession?.expectedTopEntryId == session.expectedTopEntryId &&
                        currentSession.phase == OverlayBackPhase.Cancelling
                    ) {
                        overlayBackSession = null
                        AppDebugLog.log(
                            "predictive_back_settled surface=${session.overlay.name.lowercase()} " +
                                "result=cancelled entryId=${session.expectedTopEntryId}",
                        )
                    }
                }

                OverlayBackPhase.Committing -> {
                    val commitProgress = Animatable(session.progress.coerceIn(0f, 1f))
                    commitProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = if (session.overlay == PlaybackOverlay.Queue) 180 else 220,
                        ),
                    ) {
                        val currentSession = overlayBackSession
                        if (
                            currentSession?.expectedTopEntryId == session.expectedTopEntryId &&
                            currentSession.phase == OverlayBackPhase.Committing
                        ) {
                            overlayBackSession = currentSession.copy(progress = value)
                        }
                    }
                    val currentSession = overlayBackSession
                    if (
                        currentSession?.expectedTopEntryId != session.expectedTopEntryId ||
                        currentSession.phase != OverlayBackPhase.Committing
                    ) {
                        return@LaunchedEffect
                    }
                    when (session.overlay) {
                        PlaybackOverlay.Player -> playerProgress.snapTo(1f)
                        PlaybackOverlay.Queue -> queueProgress.snapTo(1f)
                    }
                    val didPop = onCommitBack(session.backPlan)
                    if (!didPop) {
                        commitProgress.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ) {
                            val staleSession = overlayBackSession
                            if (
                                staleSession?.expectedTopEntryId == session.expectedTopEntryId &&
                                staleSession.phase == OverlayBackPhase.Committing
                            ) {
                                overlayBackSession = staleSession.copy(progress = value)
                            }
                        }
                        restoreOverlay(session.overlay)
                    }
                    overlayBackSession = null
                    AppDebugLog.log(
                        "predictive_back_settled surface=${session.overlay.name.lowercase()} " +
                            "result=${if (didPop) "committed" else "stale"} " +
                            "entryId=${session.expectedTopEntryId}",
                    )
                }
            }
        }

        LaunchedEffect(topEntryId, navigationRevision, isPlayerVisible, isQueueVisible) {
            val session = overlayBackSession ?: return@LaunchedEffect
            val routeStillMatches = when (session.overlay) {
                PlaybackOverlay.Player -> isPlayerVisible && !isQueueVisible
                PlaybackOverlay.Queue -> isQueueVisible
            }
            if (
                !routeStillMatches ||
                topEntryId != session.expectedTopEntryId ||
                navigationRevision != session.expectedRevision
            ) {
                when (session.overlay) {
                    PlaybackOverlay.Player -> playerProgress.snapTo(session.progress)
                    PlaybackOverlay.Queue -> queueProgress.snapTo(session.progress)
                }
                overlayBackSession = null
                AppDebugLog.log(
                    "predictive_back_settled surface=${session.overlay.name.lowercase()} " +
                        "result=stale_route entryId=${session.expectedTopEntryId}",
                )
            }
        }

        SideEffect {
            backDispatcher.bind(
                onStarted = onStarted@{ gesture ->
                    val overlay = when (gesture.surface) {
                        AppBackSurface.Player -> PlaybackOverlay.Player
                        AppBackSurface.Queue -> PlaybackOverlay.Queue
                        AppBackSurface.Content -> return@onStarted false
                    }
                    if (
                        backTransitionInProgress ||
                        overlayBackSession != null
                    ) {
                        return@onStarted false
                    }

                    val session = OverlayBackSession(
                        backPlan = gesture.backPlan,
                        overlay = overlay,
                    )
                    overlayBackSession = session
                    AppDebugLog.log(
                        "predictive_back_start surface=${session.overlay.name.lowercase()} " +
                            "entryId=${session.expectedTopEntryId}",
                    )
                    true
                },
                onProgressed = { event ->
                    val session = overlayBackSession
                    if (session?.phase == OverlayBackPhase.Tracking) {
                        overlayBackSession = session.copy(
                            progress = event.progress.coerceIn(0f, 1f),
                        )
                    }
                },
                onCancelled = {
                    val session = overlayBackSession
                    if (session?.phase == OverlayBackPhase.Tracking) {
                        overlayBackSession = session.copy(phase = OverlayBackPhase.Cancelling)
                        AppDebugLog.log(
                            "predictive_back_cancel surface=${session.overlay.name.lowercase()} " +
                                "entryId=${session.expectedTopEntryId}",
                        )
                    }
                },
                onCommitted = { hadProgress ->
                    val session = overlayBackSession
                    if (session?.phase == OverlayBackPhase.Tracking) {
                        AppDebugLog.log(
                            "predictive_back_commit surface=${session.overlay.name.lowercase()} " +
                                "entryId=${session.expectedTopEntryId}",
                        )
                        if (hadProgress) {
                            overlayBackSession = session.copy(phase = OverlayBackPhase.Committing)
                        } else {
                            overlayBackSession = null
                            scope.launch { finishOverlayBack(session) }
                        }
                    }
                },
            )
        }

        LaunchedEffect(isPlayerVisible, renderedPlaybackState?.currentTrack?.id) {
            if (isPlayerVisible && renderedPlaybackState?.currentTrack != null) {
                if (!renderPlayer) {
                    renderPlayer = true
                    playerProgress.snapTo(1f)
                }
                if (overlayBackSession?.overlay != PlaybackOverlay.Player) {
                    restoreOverlay(PlaybackOverlay.Player)
                }
            } else if (renderPlayer) {
                playerProgress.animateTo(1f, tween(durationMillis = 280))
                renderPlayer = false
            }
        }

        LaunchedEffect(isQueueVisible, renderedPlaybackState?.currentTrack?.id) {
            if (isQueueVisible && renderedPlaybackState?.currentTrack != null) {
                if (!renderQueue) {
                    renderQueue = true
                    queueProgress.snapTo(1f)
                }
                if (overlayBackSession?.overlay != PlaybackOverlay.Queue) {
                    restoreOverlay(PlaybackOverlay.Queue)
                }
            } else if (renderQueue) {
                queueProgress.animateTo(1f, tween(durationMillis = 240))
                renderQueue = false
            }
        }

        val playerVisualProgress = overlayBackSession
            ?.takeIf { it.overlay == PlaybackOverlay.Player }
            ?.progress
            ?: playerProgress.value
        val queueVisualProgress = overlayBackSession
            ?.takeIf { it.overlay == PlaybackOverlay.Queue }
            ?.progress
            ?: queueProgress.value

        val state = renderedPlaybackState
        val currentTrack = state?.currentTrack
        if ((renderPlayer || isPlayerVisible) && state != null && currentTrack != null) {
            val playerDragModifier = if (
                isQueueVisible || backTransitionInProgress || overlayBackSession != null
            ) {
                Modifier
            } else {
                Modifier.pointerInput(maxHeightPx, topEntryId) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            scope.launch { playerProgress.stop() }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                playerProgress.snapTo(
                                    (playerProgress.value + dragAmount / maxHeightPx).coerceIn(0f, 1f)
                                )
                            }
                        },
                        onDragEnd = {
                            if (playerProgress.value >= 1f / 3f) {
                                requestOverlayBack(PlaybackOverlay.Player)
                            } else {
                                scope.launch { restoreOverlay(PlaybackOverlay.Player) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { restoreOverlay(PlaybackOverlay.Player) }
                        },
                    )
                }
            }

            PlayerScreen(
                playbackState = state,
                onBackClick = { requestOverlayBack(PlaybackOverlay.Player) },
                onQueueClick = onOpenQueueSheet,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onPlayPauseClick = onPlayPauseClick,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                onFavoriteClick = onFavoriteClick,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxSize()
                    .then(playerDragModifier)
                    .offset {
                        IntOffset(0, (playerVisualProgress * maxHeightPx).roundToInt())
                    },
            )

            if (renderQueue || isQueueVisible) {
                QueueSheetOverlay(
                    tracks = state.playbackQueue,
                    currentTrackId = currentTrack.id,
                    currentQueueIndex = state.currentQueueIndex,
                    progress = queueVisualProgress,
                    maxHeightPx = maxHeightPx,
                    gesturesEnabled = !backTransitionInProgress && overlayBackSession == null,
                    onDismissRequest = { requestOverlayBack(PlaybackOverlay.Queue) },
                    onDragStart = {
                        scope.launch { queueProgress.stop() }
                    },
                    onDrag = { dragAmount ->
                        scope.launch {
                            queueProgress.snapTo(
                                (queueProgress.value + dragAmount / maxHeightPx).coerceIn(0f, 1f)
                            )
                        }
                    },
                    onDragEnd = {
                        if (queueProgress.value >= 1f / 7f) {
                            requestOverlayBack(PlaybackOverlay.Queue)
                        } else {
                            scope.launch { restoreOverlay(PlaybackOverlay.Queue) }
                        }
                    },
                    onDragCancel = {
                        scope.launch { restoreOverlay(PlaybackOverlay.Queue) }
                    },
                    onTrackClick = onQueueTrackClick,
                    onMoveTrack = onMoveTrack,
                    onClearQueueClick = onClearQueueClick,
                )
            }
        }
    }
}

@Composable
private fun QueueSheetOverlay(
    tracks: List<LibraryTrack>,
    currentTrackId: Long?,
    currentQueueIndex: Int,
    progress: Float,
    maxHeightPx: Float,
    gesturesEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onClearQueueClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val headerDragModifier = if (gesturesEnabled) {
            Modifier.pointerInput(maxHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { onDragStart() },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
        } else {
            Modifier
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.scrim.copy(
                        alpha = 0.24f * (1f - progress.coerceIn(0f, 1f))
                    )
                )
                .clickable(enabled = gesturesEnabled, onClick = onDismissRequest)
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset + 8.dp)
                .offset {
                    IntOffset(0, (progress.coerceIn(0f, 1f) * maxHeightPx).roundToInt())
                },
            shape = RoundedCornerShape(
                topStart = 32.dp,
                topEnd = 32.dp,
            ),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shadowElevation = 0.dp,
        ) {
            PlayingQueueScreen(
                tracks = tracks,
                currentTrackId = currentTrackId,
                currentQueueIndex = currentQueueIndex,
                onBackClick = onDismissRequest,
                onTrackClick = onTrackClick,
                onMoveTrack = onMoveTrack,
                onClearQueueClick = onClearQueueClick,
                headerModifier = headerDragModifier,
            )
        }
    }
}
