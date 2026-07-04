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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.playback.PlayerPresentation
import org.milkdev.dreamplayer.playback.PlayerUiState
import kotlin.math.roundToInt

@Composable
fun PlayerOverlayHost(
    state: PlayerUiState,
    onNavigateBack: () -> Unit,
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
    modifier: Modifier = Modifier,
) {

    val currentTrack = state.currentTrack

    if (state.playerPresentation != PlayerPresentation.Fullscreen || currentTrack == null) {
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val scope = rememberCoroutineScope()
        val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val dismissThresholdPx = maxHeightPx / 3f
        val playerOffsetY = remember { Animatable(0f) }
        var hasAnimatedIn by remember { mutableStateOf(false) }

        fun restorePlayer() {
            scope.launch {
                playerOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                )
            }
        }

        fun dismissPlayer() {
            scope.launch {
                playerOffsetY.animateTo(
                    targetValue = maxHeightPx,
                    animationSpec = tween(durationMillis = 280),
                )
                onNavigateBack()
            }
        }

        LaunchedEffect(maxHeightPx) {
            if (!hasAnimatedIn) {
                playerOffsetY.snapTo(maxHeightPx)
                hasAnimatedIn = true
                playerOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                )
            }
        }

        val visiblePlayerOffsetY = if (hasAnimatedIn) playerOffsetY.value else maxHeightPx

        val playerDragModifier = if (state.isQueueSheetVisible) {
            Modifier
        } else {
            Modifier.pointerInput(maxHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = {
                        scope.launch { playerOffsetY.stop() }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val nextOffset = (playerOffsetY.value + dragAmount).coerceIn(0f, maxHeightPx)
                        scope.launch { playerOffsetY.snapTo(nextOffset) }
                    },
                    onDragEnd = {
                        if (playerOffsetY.value >= dismissThresholdPx) {
                            dismissPlayer()
                        } else {
                            restorePlayer()
                        }
                    },
                    onDragCancel = {
                        restorePlayer()
                    }
                )
            }
        }

        PlayerScreen(
            state = state,
            onBackClick = { dismissPlayer() },
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
                .offset { IntOffset(0, visiblePlayerOffsetY.roundToInt()) },
        )

        if (state.isQueueSheetVisible) {
            QueueSheetOverlay(
                tracks = state.playbackQueue,
                currentTrackId = currentTrack.id,
                currentQueueIndex = state.currentQueueIndex,
                onNavigateBack = onNavigateBack,
                onTrackClick = onQueueTrackClick,
                onMoveTrack = onMoveTrack,
                onClearQueueClick = onClearQueueClick,
            )
        }
    }
}

@Composable
private fun QueueSheetOverlay(
    tracks: List<LibraryTrack>,
    currentTrackId: Long?,
    currentQueueIndex: Int,
    onNavigateBack: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onClearQueueClick: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val scope = rememberCoroutineScope()
        val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val sheetOffsetY = remember { Animatable(0f) }
        var hasAnimatedIn by remember { mutableStateOf(false) }
        val dismissThresholdPx = maxHeightPx / 7f
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        fun restoreSheet() {
            scope.launch {
                sheetOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                )
            }
        }

        fun dismissSheet() {
            scope.launch {
                sheetOffsetY.animateTo(
                    targetValue = maxHeightPx,
                    animationSpec = tween(durationMillis = 240),
                )
                onNavigateBack()
            }
        }

        LaunchedEffect(maxHeightPx) {
            if (!hasAnimatedIn) {
                sheetOffsetY.snapTo(maxHeightPx)
                hasAnimatedIn = true
                sheetOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                )
            }
        }

        val visibleSheetOffsetY = if (hasAnimatedIn) sheetOffsetY.value else maxHeightPx

        val headerDragModifier = Modifier.pointerInput(maxHeightPx) {
            detectVerticalDragGestures(
                onDragStart = {
                    scope.launch { sheetOffsetY.stop() }
                },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    val nextOffset = (sheetOffsetY.value + dragAmount).coerceIn(0f, maxHeightPx)
                    scope.launch { sheetOffsetY.snapTo(nextOffset) }
                },
                onDragEnd = {
                    if (sheetOffsetY.value >= dismissThresholdPx) {
                        dismissSheet()
                    } else {
                        restoreSheet()
                    }
                },
                onDragCancel = {
                    restoreSheet()
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f))
                .clickable(onClick = { dismissSheet() })
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset + 8.dp)
                .offset { IntOffset(0, visibleSheetOffsetY.roundToInt()) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
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
                onBackClick = { dismissSheet() },
                onTrackClick = onTrackClick,
                onMoveTrack = onMoveTrack,
                onClearQueueClick = onClearQueueClick,
                headerModifier = headerDragModifier,
            )
        }
    }
}
