package org.milkdev.dreamplayer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.mahozad.multiplatform.wavyslider.WaveDirection
import ir.mahozad.multiplatform.wavyslider.material3.WaveHeight
import ir.mahozad.multiplatform.wavyslider.material3.WaveLength
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.favorite
import org.milkdev.dreamplayer.generated.resources.pause
import org.milkdev.dreamplayer.generated.resources.queue_music
import org.milkdev.dreamplayer.generated.resources.repeat
import org.milkdev.dreamplayer.generated.resources.repeat_on
import org.milkdev.dreamplayer.generated.resources.repeat_one_on
import org.milkdev.dreamplayer.generated.resources.shuffle_2
import org.milkdev.dreamplayer.generated.resources.shuffle_on
import org.milkdev.dreamplayer.generated.resources.skip_next
import org.milkdev.dreamplayer.generated.resources.skip_previous
import org.milkdev.dreamplayer.generated.resources.play_arrow
import org.milkdev.dreamplayer.generated.resources.stat_minus
import org.milkdev.dreamplayer.playback.PlaybackRepeatMode
import org.milkdev.dreamplayer.playback.PlayerUiState
import ir.mahozad.multiplatform.wavyslider.material3.WavySlider
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.generated.resources.favorite_filled
import org.milkdev.dreamplayer.generated.resources.lyrics
import org.milkdev.dreamplayer.library.LibraryTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onBackClick: () -> Unit,
    onQueueClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    val sliderProgress = if (isScrubbing) scrubProgress else state.playbackProgressMs.toFloat()
    val totalDuration = state.totalDurationMs.toFloat().coerceAtLeast(1f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlayerTopBar(
            onBackClick = onBackClick,
            onQueueClick = onQueueClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val useWideLayout = maxWidth >= maxHeight ||
                (maxWidth >= WideLayoutMinWidth && maxHeight < maxWidth + PortraitControlsHeight)

            if (useWideLayout) {
                WidePlayerContent(
                    state = state,
                    sliderProgress = sliderProgress,
                    totalDuration = totalDuration,
                    onPreviousClick = onPreviousClick,
                    onNextClick = onNextClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onShuffleClick = onShuffleClick,
                    onRepeatClick = onRepeatClick,
                    onFavoriteClick = onFavoriteClick,
                    onSliderValueChange = { newValue ->
                        isScrubbing = true
                        scrubProgress = newValue
                    },
                    onSliderValueChangeFinished = {
                        onSeek(scrubProgress.toLong())
                        isScrubbing = false
                    },
                )
            } else {
                PortraitPlayerContent(
                    state = state,
                    sliderProgress = sliderProgress,
                    totalDuration = totalDuration,
                    onPreviousClick = onPreviousClick,
                    onNextClick = onNextClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onShuffleClick = onShuffleClick,
                    onRepeatClick = onRepeatClick,
                    onFavoriteClick = onFavoriteClick,
                    onSliderValueChange = { newValue ->
                        isScrubbing = true
                        scrubProgress = newValue
                    },
                    onSliderValueChangeFinished = {
                        onSeek(scrubProgress.toLong())
                        isScrubbing = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    onBackClick: () -> Unit,
    onQueueClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Icon(
                painter = painterResource(Res.drawable.stat_minus),
                contentDescription = "Свернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = "Сейчас играет",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
            style = AppTheme.typography.snPro.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        IconButton(
            onClick = onQueueClick,
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Icon(
                painter = painterResource(Res.drawable.queue_music),
                contentDescription = "Очередь",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PortraitPlayerContent(
    state: PlayerUiState,
    sliderProgress: Float,
    totalDuration: Float,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val artworkSize = minOf(maxWidth, maxHeight)

            AlbumArtwork(
                albumArtUri = state.currentTrack?.albumArtUri,
                modifier = Modifier.size(artworkSize)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        PlayerTrackInfo(
            currentTrack = state.currentTrack,
            onFavoriteClick = onFavoriteClick,
            isFavorite = state.isCurrentTrackFavorite
        )

        Spacer(modifier = Modifier.height(24.dp))

        PlayerProgress(
            sliderProgress = sliderProgress,
            totalDuration = totalDuration,
            totalDurationMs = state.totalDurationMs,
            isPlaying = state.isPlaying,
            onSliderValueChange = onSliderValueChange,
            onSliderValueChangeFinished = onSliderValueChangeFinished,
        )

        Spacer(modifier = Modifier.height(20.dp))

        MainPlaybackControls(
            isPlaying = state.isPlaying,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
            onPlayPauseClick = onPlayPauseClick
        )

        Spacer(modifier = Modifier.height(20.dp))

        ExtraPlaybackControls(
            isShuffleEnabled = state.isShuffleEnabled,
            repeatMode = state.repeatMode,
            onShuffleClick = onShuffleClick,
            onRepeatClick = onRepeatClick
        )
    }
}

@Composable
private fun WidePlayerContent(
    state: PlayerUiState,
    sliderProgress: Float,
    totalDuration: Float,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            val artworkSize = minOf(maxWidth, maxHeight)

            AlbumArtwork(
                albumArtUri = state.currentTrack?.albumArtUri,
                modifier = Modifier.size(artworkSize)
            )
        }

        Spacer(modifier = Modifier.width(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            PlayerTrackInfo(
                currentTrack = state.currentTrack,
                onFavoriteClick = onFavoriteClick,
                compact = true,
                isFavorite = state.isCurrentTrackFavorite,
            )

            Spacer(modifier = Modifier.height(16.dp))

            PlayerProgress(
                sliderProgress = sliderProgress,
                totalDuration = totalDuration,
                totalDurationMs = state.totalDurationMs,
                isPlaying = state.isPlaying,
                onSliderValueChange = onSliderValueChange,
                onSliderValueChangeFinished = onSliderValueChangeFinished,
            )

            Spacer(modifier = Modifier.height(16.dp))

            MainPlaybackControls(
                isPlaying = state.isPlaying,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onPlayPauseClick = onPlayPauseClick,
                compact = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            ExtraPlaybackControls(
                isShuffleEnabled = state.isShuffleEnabled,
                repeatMode = state.repeatMode,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                compact = true
            )
        }
    }
}

@Composable
private fun AlbumArtwork(
    albumArtUri: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
    ) {
        TrackImage(
            uri = albumArtUri,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlayerTrackInfo(
    currentTrack: LibraryTrack?,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    compact: Boolean = false
) {
    val actionButtonSize = if (compact) 40.dp else 48.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentTrack?.title ?: "Название трека",
                style = if (compact) AppTheme.typography.snPro.headlineSmall else
                    AppTheme.typography.snPro.headlineSmall.copy(fontWeight = FontWeight.Bold),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = currentTrack?.artistName ?: "Исполнитель",
                style = if (compact) AppTheme.typography.snPro.bodyLarge else
                    AppTheme.typography.snPro.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()

        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.75f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "favorite_bounce"
        )

        val iconTint by animateColorAsState(
            targetValue = if (isFavorite) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(durationMillis = 300),
            label = "favorite_icon_color"
        )

        val containerBg by animateColorAsState(
            targetValue = if (isFavorite) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            animationSpec = tween(durationMillis = 300),
            label = "favorite_bg_color"
        )

        IconButton(
            onClick = onFavoriteClick,
            enabled = currentTrack != null,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(actionButtonSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(containerBg, CircleShape)
        ) {
            val iconRes = if (isFavorite) {
                Res.drawable.favorite_filled
            } else {
                Res.drawable.favorite
            }

            Icon(
                painter = painterResource(iconRes),
                contentDescription = if (isFavorite) "Убрать из избранного" else
                    "Добавить в избранное",
                tint = iconTint
            )
        }

        Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))

        IconButton(
            onClick = { /* TODO */ },
            modifier = Modifier
                .size(actionButtonSize)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(
                painter = painterResource(Res.drawable.lyrics),
                contentDescription = "lyrics",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun PlayerProgress(
    sliderProgress: Float,
    totalDuration: Float,
    totalDurationMs: Long,
    isPlaying: Boolean,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    val animatedWaveHeight by animateDpAsState(
        targetValue = if (isPlaying) SliderDefaults.WaveHeight else 0.dp,
        animationSpec = tween(durationMillis = 100),
        label = "WaveHeightAnimation"
    )
    WavySlider(
        value = sliderProgress,
        onValueChange = onSliderValueChange,
        onValueChangeFinished = onSliderValueChangeFinished,
        valueRange = 0f..totalDuration,
        modifier = Modifier.fillMaxWidth(),
        waveHeight = animatedWaveHeight,
        waveVelocity = if (isPlaying) {
            SliderDefaults.WaveLength to WaveDirection.HEAD
        } else {
            0.dp to WaveDirection.HEAD
        }
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatTime(sliderProgress.toLong()),
            style = AppTheme.typography.snPro.labelMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = formatTime(totalDurationMs),
            style = AppTheme.typography.snPro.labelMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun MainPlaybackControls(
    isPlaying: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    compact: Boolean = false
) {
    val secondaryButtonSize = if (compact) 56.dp else 72.dp
    val secondaryIconSize = if (compact) 28.dp else 32.dp
    val primaryButtonSize = if (compact) 72.dp else 96.dp
    val primaryIconSize = if (compact) 40.dp else 48.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPreviousClick,
            modifier = Modifier
                .size(secondaryButtonSize)
                .clip(M3ESquareShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                painter = painterResource(Res.drawable.skip_previous),
                contentDescription = "Предыдущий",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(secondaryIconSize)
            )
        }

        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier
                .size(primaryButtonSize)
                .clip(M3EClover4Leaf)
                .background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                painter = painterResource(if (isPlaying) Res.drawable.pause else
                    Res.drawable.play_arrow),
                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(primaryIconSize)
            )
        }

        IconButton(
            onClick = onNextClick,
            modifier = Modifier
                .size(secondaryButtonSize)
                .clip(M3ESquareShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                painter = painterResource(Res.drawable.skip_next),
                contentDescription = "Следующий",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(secondaryIconSize)
            )
        }
    }
}

@Composable
private fun ExtraPlaybackControls(
    isShuffleEnabled: Boolean,
    repeatMode: PlaybackRepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    compact: Boolean = false
) {
    val buttonSize = if (compact) 40.dp else 48.dp
    val inactiveIconSize = if (compact) 22.dp else 24.dp
    val activeIconSize = if (compact) 26.dp else 28.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaybackModeButton(
            icon = if (isShuffleEnabled) Res.drawable.shuffle_on else Res.drawable.shuffle_2,
            active = isShuffleEnabled,
            contentDescription = "Shuffle",
            onClick = onShuffleClick,
            buttonSize = buttonSize,
            inactiveIconSize = inactiveIconSize,
            activeIconSize = activeIconSize,
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = if (compact) 18.dp else 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "инфо о файле",
                style = AppTheme.typography.snPro.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        PlaybackModeButton(
            icon = when (repeatMode) {
                PlaybackRepeatMode.Off -> Res.drawable.repeat
                PlaybackRepeatMode.Queue -> Res.drawable.repeat_on
                PlaybackRepeatMode.One -> Res.drawable.repeat_one_on
            },
            active = repeatMode != PlaybackRepeatMode.Off,
            contentDescription = when (repeatMode) {
                PlaybackRepeatMode.Off -> "Repeat"
                PlaybackRepeatMode.Queue -> "Repeat queue"
                PlaybackRepeatMode.One -> "Repeat one"
            },
            onClick = onRepeatClick,
            buttonSize = buttonSize,
            inactiveIconSize = inactiveIconSize,
            activeIconSize = activeIconSize,
        )
    }
}

@Composable
private fun PlaybackModeButton(
    icon: DrawableResource,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    buttonSize: Dp,
    inactiveIconSize: Dp,
    activeIconSize: Dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.08f else 1f,
        animationSpec = tween(durationMillis = if (isPressed) 90 else 180),
        label = "PlaybackModeButtonScale",
    )
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(if (active) activeIconSize else inactiveIconSize),
        )
    }
}

private val WideLayoutMinWidth = 520.dp
private val PortraitControlsHeight = 344.dp

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString()
            .padStart(2, '0')}"
}
