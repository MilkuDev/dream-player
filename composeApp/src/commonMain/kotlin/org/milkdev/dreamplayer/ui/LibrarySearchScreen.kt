package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.arrow_back
import org.milkdev.org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.org.milkdev.dreamplayer.playback.PlayerUiState
import kotlin.time.Duration.Companion.seconds

private var isFirstLaunchDebug = true
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibrarySearchScreen(
    modifier: Modifier = Modifier,
    state: PlayerUiState,
    onTrackClick: (List<LibraryTrack>, LibraryTrack) -> Unit,
    onBackClick: () -> Unit,
    onLoadNextSearch: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues.Zero,
) {
    val filteredTracks = remember(state.searchTrackListItems) {
        state.searchTrackListItems.map { it.toLibraryTrack() }
    }

    val lazyListState = rememberLazyListState()
    val loadUncachedArtwork = rememberLazyArtworkLoadingEnabled(
        listState = lazyListState,
    )

    LaunchedEffect(state.librarySearch.query, state.librarySearch.mode) {
        lazyListState.scrollToItem(0)
    }

    val shouldLoadMoreSearch by remember {
        derivedStateOf {
            val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.searchTrackListItems.lastIndex - 8
        }
    }

    LaunchedEffect(shouldLoadMoreSearch, state.hasMoreSearchTracks, state.isSearchPageLoading) {
        if (shouldLoadMoreSearch && state.hasMoreSearchTracks && !state.isSearchPageLoading) {
            onLoadNextSearch()
        }
    }

    var isDebugLoading by remember { mutableStateOf(isFirstLaunchDebug) }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            if (isFirstLaunchDebug) {
                delay(1.seconds)
                isFirstLaunchDebug = false
            }
            isDebugLoading = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isDebugLoading -> {
                LoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.error != null -> {
                Text(
                    text = "Error: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            state.librarySummary.trackCount == 0 && state.librarySearch.query.isBlank() -> {
                Text(
                    text = "No audio items found.",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    painter = painterResource(Res.drawable.arrow_back),
                                    contentDescription = "Назад",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Поиск",
                                style = AppTheme.typography.snPro.headlineLarge,
                            )
                        }
                    }

                    if (filteredTracks.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.librarySearch.query.isBlank()) {
                                    "No audio items found."
                                } else {
                                    "Попробуй другой запрос"
                                },
                                style = AppTheme.typography.snPro.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = lazyListState,
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 16.dp,
                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredTracks, key = { it.id }) { track ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .clickable { onTrackClick(filteredTracks, track) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (track.id == state.currentTrack?.id)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                        ) {
                                            TrackImage(
                                                uri = track.albumArtUri,
                                                modifier = Modifier.fillMaxSize(),
                                                maxDecodeSizePx = 192,
                                                loadUncached = loadUncachedArtwork,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(
                                                text = track.title,
                                                style = AppTheme.typography.snPro.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = track.artistName,
                                                style = AppTheme.typography.snPro.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
