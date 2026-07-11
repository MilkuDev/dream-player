package org.milkdev.dreamplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.delete
import org.milkdev.dreamplayer.generated.resources.drag_indicator
import org.milkdev.dreamplayer.generated.resources.equalizer
import org.milkdev.dreamplayer.generated.resources.stat_minus
import org.milkdev.dreamplayer.library.LibraryTrack

@Stable
private data class UiTrack(val key: String, val track: LibraryTrack)

@Composable
fun PlayingQueueScreen(
    tracks: List<LibraryTrack>,
    currentTrackId: Long?,
    currentQueueIndex: Int,
    onBackClick: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onClearQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val loadUncachedArtwork = rememberLazyArtworkLoadingEnabled(listState = listState)

    var localTracks by remember { mutableStateOf(emptyList<UiTrack>()) }
    var isDragging by remember { mutableStateOf(false) }
    var isDropping by remember { mutableStateOf(false) }
    var isCommittingDrop by remember { mutableStateOf(false) }
    var localPlayingIndex by remember { mutableIntStateOf(currentQueueIndex) }

    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val currentOnMoveTrack by rememberUpdatedState(onMoveTrack)
    val currentOnTrackClick by rememberUpdatedState(onTrackClick)

    LaunchedEffect(tracks) {
        if (!isDragging && !isDropping) {
            val counts = mutableMapOf<Long, Int>()
            localTracks = tracks.map { track ->
                val count = counts[track.id] ?: 0
                counts[track.id] = count + 1
                UiTrack("${track.id}_$count", track)
            }
        }
    }

    LaunchedEffect(currentQueueIndex) {
        if (!isDragging && !isDropping) {
            localPlayingIndex = currentQueueIndex
        }
    }

    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var currentTargetIndex by remember { mutableStateOf<Int?>(null) }

    var rawDraggedItemTop by remember { mutableFloatStateOf(0f) }
    var draggedItemHeight by remember { mutableFloatStateOf(0f) }
    var edgeScrollStep by remember { mutableFloatStateOf(0f) }

    val draggedTrack = dragStartIndex?.let { localTracks.getOrNull(it)?.track }

    val density = LocalDensity.current
    val itemSpacing = 8.dp
    val itemSpacingPx = remember(density) { with(density) { itemSpacing.toPx() } }

    val autoScrollMaxSpeedPx = remember(density) { with(density) { 24.dp.toPx() } }
    val edgeThresholdPx = remember(density) { with(density) { 64.dp.toPx() } }

    fun resetDragState() {
        isDragging = false
        isDropping = false
        dragStartIndex = null
        currentTargetIndex = null
        rawDraggedItemTop = 0f
        draggedItemHeight = 0f
        edgeScrollStep = 0f
    }

    fun updateTargetIndex() {
        if (dragStartIndex == null || draggedItemHeight <= 0f) return
        val draggedCenter = rawDraggedItemTop + (draggedItemHeight / 2f)

        val hoveredItem = listState.layoutInfo.visibleItemsInfo.find { item ->
            draggedCenter >= item.offset && draggedCenter <= (item.offset + item.size)
        }

        if (hoveredItem != null && hoveredItem.index in localTracks.indices) {
            if (currentTargetIndex != hoveredItem.index) {
                currentTargetIndex = hoveredItem.index
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun updateEdgeScrollStep() {
        if (dragStartIndex == null || draggedItemHeight <= 0f) {
            edgeScrollStep = 0f
            return
        }

        val layoutInfo = listState.layoutInfo
        val minTop = layoutInfo.viewportStartOffset.toFloat()
        val maxTop = layoutInfo.viewportEndOffset.toFloat() - draggedItemHeight

        val topDistance = rawDraggedItemTop - minTop
        val bottomDistance = maxTop - rawDraggedItemTop

        edgeScrollStep = when {
            topDistance < edgeThresholdPx -> {
                val factor = 1f - (topDistance / edgeThresholdPx).coerceIn(0f, 1f)
                -autoScrollMaxSpeedPx * factor
            }
            bottomDistance < edgeThresholdPx -> {
                val factor = 1f - (bottomDistance / edgeThresholdPx).coerceIn(0f, 1f)
                autoScrollMaxSpeedPx * factor
            }
            else -> 0f
        }
    }

    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        while (isActive) {
            withFrameMillis { }
            updateEdgeScrollStep()
            if (edgeScrollStep != 0f) {
                listState.scrollBy(edgeScrollStep)
                updateTargetIndex()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Box(
            modifier = headerModifier.fillMaxWidth().padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            ) { Icon(
                painterResource(Res.drawable.stat_minus),
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Очередь воспроизведения",
                style = AppTheme.typography.snPro.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(
                onClick = onClearQueueClick,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            ) { Icon(
                painterResource(Res.drawable.delete),
                contentDescription = "Очистить",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            if (isDropping) return@detectDragGesturesAfterLongPress

                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { item ->
                                    offset.y.toInt() in item.offset..(item.offset + item.size)
                                }?.also { item ->
                                    if (item.index in localTracks.indices) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isDragging = true
                                        dragStartIndex = item.index
                                        currentTargetIndex = item.index
                                        rawDraggedItemTop = item.offset.toFloat()
                                        draggedItemHeight = item.size.toFloat()
                                        edgeScrollStep = 0f
                                    }
                                }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            rawDraggedItemTop += dragAmount.y
                            updateTargetIndex()
                        },
                        onDragEnd = {
                            val from = dragStartIndex
                            val to = currentTargetIndex

                            if (from != null && to != null) {
                                isDragging = false
                                isDropping = true
                                edgeScrollStep = 0f

                                coroutineScope.launch {
                                    val targetItem = listState.layoutInfo.visibleItemsInfo.find {
                                        it.index == to
                                    }
                                    val targetTop = targetItem?.offset?.toFloat() ?: rawDraggedItemTop

                                    Animatable(rawDraggedItemTop).animateTo(
                                        targetValue = targetTop,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    ) {
                                        rawDraggedItemTop = value
                                    }

                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                                    if (from != to) {
                                        isCommittingDrop = true

                                        val oldFirstIdx = listState.firstVisibleItemIndex
                                        val oldOffset = listState.firstVisibleItemScrollOffset

                                        val newList = localTracks.toMutableList()
                                        val movedItem = newList.removeAt(from)
                                        newList.add(to, movedItem)

                                        when (localPlayingIndex) {
                                            from -> localPlayingIndex = to
                                            in (from + 1)..to -> localPlayingIndex--
                                            in to until from -> localPlayingIndex++
                                        }

                                        localTracks = newList
                                        dragStartIndex = null
                                        currentTargetIndex = null
                                        isDropping = false

                                        currentOnMoveTrack(from, to)

                                        try {
                                            listState.requestScrollToItem(oldFirstIdx,
                                                oldOffset)
                                        } catch (_: Exception) {
                                            listState.scrollToItem(oldFirstIdx,
                                                oldOffset)
                                        }

                                        val movedKey = movedItem.key
                                        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
                                            .first { visibleItems ->
                                                visibleItems.any {
                                                    it.index == to && it.key == movedKey
                                                }
                                            }

                                        rawDraggedItemTop = 0f
                                        draggedItemHeight = 0f
                                        edgeScrollStep = 0f
                                        isCommittingDrop = false
                                    } else {
                                        resetDragState()
                                    }
                                }
                            } else {
                                resetDragState()
                            }
                        },
                        onDragCancel = { resetDragState() }
                    )
                }
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = !isDragging && !isDropping,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                itemsIndexed(
                    items = localTracks,
                    key = { _, uiTrack -> uiTrack.key },
                    contentType = { _, _ -> "queue_track" },
                ) { index, uiTrack ->

                    val track = uiTrack.track
                    val isPlaying = index == localPlayingIndex ||
                            (localPlayingIndex !in localTracks.indices && track.id == currentTrackId)

                    val shiftAmount = draggedItemHeight + itemSpacingPx
                    val targetTranslation = when {
                        dragStartIndex == null || currentTargetIndex == null -> 0f
                        index == dragStartIndex -> 0f
                        dragStartIndex!! < currentTargetIndex!! && index in
                                (dragStartIndex!! + 1)..currentTargetIndex!! -> -shiftAmount
                        dragStartIndex!! > currentTargetIndex!! && index in
                                currentTargetIndex!! until dragStartIndex!! -> shiftAmount
                        else -> 0f
                    }

                    val animatedTranslationY = animateFloatAsState(
                        targetValue = targetTranslation,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "translationY"
                    )

                    val isAnimationDisabled = isDragging || isDropping || isCommittingDrop

                    QueueItem(
                        title = track.title,
                        artist = track.artistName,
                        albumArtUri = track.albumArtUri,
                        isPlaying = isPlaying,
                        isDragging = false,
                        loadUncachedArtwork = loadUncachedArtwork && !isDragging,
                        modifier = Modifier
                            .then(
                                if (isAnimationDisabled) Modifier
                                else Modifier.animateItem(
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            )
                            .graphicsLayer {
                                val isGhostActive = dragStartIndex != null &&
                                        (isDragging || isDropping)
                                val isMe = index == dragStartIndex

                                alpha = if (isMe && isGhostActive) 0f else 1f
                                translationY = if (dragStartIndex == null ||
                                    isCommittingDrop) 0f else animatedTranslationY.value
                            },
                        onClick = { currentOnTrackClick(index) }
                    )
                }
            }

            if (draggedTrack != null && dragStartIndex != null && (isDragging || isDropping)) {
                val isPlaying = dragStartIndex == localPlayingIndex ||
                        (localPlayingIndex !in localTracks.indices &&
                                draggedTrack.id == currentTrackId)

                val scale by animateFloatAsState(
                    targetValue = if (isDragging && !isDropping) 1.03f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow),
                    label = "dragScale"
                )

                val elevation by animateDpAsState(
                    targetValue = if (isDragging && !isDropping) 8.dp else 0.dp,
                    animationSpec = spring(),
                    label = "dragElevation"
                )

                QueueItem(
                    title = draggedTrack.title,
                    artist = draggedTrack.artistName,
                    albumArtUri = draggedTrack.albumArtUri,
                    isPlaying = isPlaying,
                    isDragging = true,
                    loadUncachedArtwork = false,
                    modifier = Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            translationY = rawDraggedItemTop
                            scaleX = scale
                            scaleY = scale
                            shadowElevation = elevation.toPx()
                            shape = RoundedCornerShape(24.dp)
                            clip = true
                        },
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun QueueItem(
    modifier: Modifier = Modifier,
    title: String,
    artist: String,
    albumArtUri: String?,
    isPlaying: Boolean,
    isDragging: Boolean = false,
    loadUncachedArtwork: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.surfaceVariant
        isPlaying -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val contentColor = if (isPlaying && !isDragging) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            TrackImage(
                uri = albumArtUri,
                modifier = Modifier.fillMaxSize(),
                maxDecodeSizePx = 160,
                loadUncached = loadUncachedArtwork,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = AppTheme.typography.snPro.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = AppTheme.typography.snPro.bodyMedium,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isPlaying) {
            Icon(
                painter = painterResource(Res.drawable.equalizer),
                contentDescription = "Играет",
                tint = contentColor,
                modifier = Modifier.padding(end = 12.dp).size(24.dp)
            )
        } else {
            Icon(
                painter = painterResource(Res.drawable.drag_indicator),
                contentDescription = "Переместить",
                tint = contentColor,
                modifier = Modifier.padding(end = 12.dp).size(24.dp)
            )
        }
    }
}