package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.settings
import org.milkdev.dreamplayer.generated.resources.shuffle_2
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.playback.LibraryUiState
import org.milkdev.dreamplayer.playback.DailyPlaylistUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    libraryState: LibraryUiState,
    onShuffleDailyPlaylistClick: () -> Unit,
    onOpenDailyPlaylistClick: () -> Unit,
    onTrackClick: (List<LibraryTrack>, LibraryTrack) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
) {
    val dailyTracks = (libraryState.dailyPlaylistState as? DailyPlaylistUiState.Available)?.tracks ?: emptyList()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                top = 0.dp,
                bottom = contentPadding.calculateBottomPadding() + 10.dp
            ),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            item {
                DailyPlaylistBanner(
                    tracks = dailyTracks,
                    onShuffleClick = onShuffleDailyPlaylistClick,
                    onOpenClick = onOpenDailyPlaylistClick,
                )
            }
            item {
                ContentSection(
                    title = "Слушали ранее",
                    tracks = libraryState.recentlyPlayedTracks,
                    onTrackClick = { track -> onTrackClick(libraryState.recentlyPlayedTracks, track) }
                )
            }

            item {
                val genreTitle = if (libraryState.homeGenreTitle.isNotEmpty()) "Жанр: ${libraryState.homeGenreTitle}" else
                    "На основе ваших вкусов"
                ContentSection(
                    title = genreTitle,
                    tracks = libraryState.homeGenreTracks,
                    onTrackClick = { track -> onTrackClick(libraryState.homeGenreTracks, track) }
                )
            }
        }

        TopAppBar(
            title = {
                Text(
                    text = "Главная",
                    style = AppTheme.typography.snPro.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            actions = {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(46.dp)
                        .clickable(onClick = onSettingsClick),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(M3ECookie6SidedShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 1f))
                    )
                    Icon(
                        painter = painterResource(Res.drawable.settings),
                        contentDescription = "Настройки",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            scrollBehavior = scrollBehavior
        )
    }
}
@Composable
fun DailyPlaylistBanner(
    tracks: List<LibraryTrack>,
    onShuffleClick: () -> Unit,
    onOpenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Spacer(modifier = Modifier
        .height(120.dp)
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(690.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RectangleShape)
                .offset(y = 30.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .graphicsLayer(scaleX = 1.2f, scaleY = 1.2f, rotationZ = 40f)
                .clip(M3EPuffyShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 30.dp)
                .padding(top = 30.dp)
                .align(Alignment.Center)
                .height(400.dp)
                .width(600.dp)
        ) {
            Box(modifier = Modifier
                .padding(top = 80.dp, start = 20.dp)
                .size(120.dp)
                .align(Alignment.TopStart)
                .graphicsLayer(rotationZ = 340f)
                .clip(M3EHeartShape)
                .background(MaterialTheme.colorScheme.primary)
                .shapeClickableWithFeedback(
                    shape = M3EHeartShape,
                    onClick = onOpenClick,
                )
            ) {
                TrackImage(
                    uri = tracks.getOrNull(0)?.albumArtUri,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(modifier = Modifier
                .padding(bottom = 0.dp, start = 50.dp)
                .size(150.dp)
                .align(Alignment.BottomStart)
                .graphicsLayer(scaleX = 1f, scaleY = 1f, rotationZ = 355f)
                .clip(M3ESunnyShape)
                .background(MaterialTheme.colorScheme.primary)
                .shapeClickableWithFeedback(
                    shape = M3ESunnyShape,
                    onClick = onOpenClick,
                    )
            ) {
                TrackImage(
                    uri = tracks.getOrNull(1)?.albumArtUri,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(modifier = Modifier
                .size(180.dp)
                .align(Alignment.TopEnd)
                .offset(x = (0).dp, y = (0).dp)
                .graphicsLayer(scaleX = 1f, scaleY = 1f, rotationZ = 20f)
                .clip(M3EFlowerShape)
                .background(MaterialTheme.colorScheme.primary)
                .shapeClickableWithFeedback(
                    shape = M3EFlowerShape,
                    onClick = onOpenClick,
                    )
            ) {
                TrackImage(
                    uri = tracks.getOrNull(2)?.albumArtUri,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .padding(end = 20.dp, bottom = 70.dp)
                    .size(100.dp)
                    .align(Alignment.BottomEnd)
                    .clip(M3ECookie12SidedShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .shapeClickableWithFeedback(
                        shape = M3ECookie12SidedShape,
                        onClick = onShuffleClick,
                        onClickDelay = ClickDelays.Shuffle
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(Res.drawable.shuffle_2),
                    contentDescription = "Shuffle",
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(180.dp)
                .align(Alignment.TopStart)
                .offset(x = 40.dp, y = (-0).dp)
                .graphicsLayer(scaleX = 2.5f, scaleY = 1f)
                .clip(M3EPixelTriangleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
        Column(
            modifier = Modifier

                .padding(top = 55.dp, start = 24.dp, end = 24.dp)
                .align(Alignment.TopStart)
        ) {
            Text(
                text = "Ваш плейлист",
                style = AppTheme.typography.snPro.titleLarge.copy(fontStyle = FontStyle.Italic, fontSize = 28.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "дня",
                style = AppTheme.typography.snPro.titleLarge.copy(fontStyle = FontStyle.Italic, fontSize = 28.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
