package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.arrow_back
import org.milkdev.dreamplayer.generated.resources.music_note
import org.milkdev.dreamplayer.generated.resources.playlist_add
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.model.PlaylistDetailUiState
import org.milkdev.dreamplayer.playback.LibraryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailsScreen(
    modifier: Modifier = Modifier,
    libraryState: LibraryUiState,
    detailState: PlaylistDetailUiState,
    onBackClick: () -> Unit,
    onTrackClick: (LibraryTrack) -> Unit,
    onSaveTracks: (Long, List<Long>) -> Unit,
    onLoadNextPickerTracks: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues.Zero,
    currentTrackId: Long? = null,
) {
    val playlist = detailState.playlist
    val editablePlaylist = playlist.takeIf { it.canEditTracks }
    var isTrackPickerVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = playlist.name,
                        style = AppTheme.typography.snPro.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(Res.drawable.arrow_back),
                            contentDescription = "Назад",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        floatingActionButton = {
            if (editablePlaylist != null) {
                FloatingActionButton(
                    onClick = { isTrackPickerVisible = true },
                    modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.playlist_add),
                        contentDescription = "Добавить треки",
                    )
                }
            }
        },
    ) { innerPadding ->
        if (detailState.tracks.isEmpty()) {
            EmptyPlaylistMessage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(contentPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = detailState.tracks,
                    key = { it.id },
                    contentType = { "playlist_track" },
                ) { track ->
                    PlaylistTrackItem(
                        track = track,
                        selected = track.id == currentTrackId,
                        onClick = { onTrackClick(track) },
                    )
                }
            }
        }
    }

    if (isTrackPickerVisible && editablePlaylist != null) {
        PlaylistTrackPickerDialog(
            allTracks = libraryState.playlistPickerTrackItems.map { it.toLibraryTrack() },
            currentPlaylistTracks = detailState.tracks,
            hasMoreTracks = libraryState.hasMorePlaylistPickerTracks,
            isLoadingMore = libraryState.isPlaylistPickerPageLoading,
            onLoadMore = onLoadNextPickerTracks,
            onDismiss = { isTrackPickerVisible = false },
            onSave = { trackIds ->
                onSaveTracks(editablePlaylist.id, trackIds)
                isTrackPickerVisible = false
            },
        )
    }
}

@Composable
private fun PlaylistTrackItem(
    track: LibraryTrack,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            TrackImage(
                uri = track.albumArtUri,
                modifier = Modifier.fillMaxSize(),
                fallbackIcon = Res.drawable.music_note,
                maxDecodeSizePx = 160,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = AppTheme.typography.snPro.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistName,
                style = AppTheme.typography.snPro.bodyMedium,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaylistTrackPickerDialog(
    allTracks: List<LibraryTrack>,
    currentPlaylistTracks: List<LibraryTrack>,
    hasMoreTracks: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<Long>) -> Unit,
) {
    val currentTrackIds = currentPlaylistTracks.map { it.id }
    var selectedTrackIds by remember(currentTrackIds) {
        mutableStateOf(currentTrackIds.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Треки плейлиста") },
        text = {
            if (allTracks.isEmpty()) {
                Text("В библиотеке пока нет треков")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = allTracks,
                        key = { it.id },
                        contentType = { "playlist_track_picker" },
                    ) { track ->
                        PlaylistSelectableTrackItem(
                            track = track,
                            selected = track.id in selectedTrackIds,
                            onClick = {
                                selectedTrackIds = if (track.id in selectedTrackIds) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                }
                            },
                        )
                    }
                    if (hasMoreTracks) {
                        item {
                            TextButton(
                                onClick = onLoadMore,
                                enabled = !isLoadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (isLoadingMore) "Загружаю..." else "Загрузить ещё")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newTrackIds = allTracks
                        .map { it.id }
                        .filter { it in selectedTrackIds && it !in currentTrackIds }
                    onSave(
                        currentTrackIds.filter { it in selectedTrackIds } + newTrackIds
                    )
                },
                enabled = allTracks.isNotEmpty(),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun PlaylistSelectableTrackItem(
    track: LibraryTrack,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            TrackImage(
                uri = track.albumArtUri,
                modifier = Modifier.fillMaxSize(),
                fallbackIcon = Res.drawable.music_note,
                maxDecodeSizePx = 144,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = AppTheme.typography.snPro.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistName,
                style = AppTheme.typography.snPro.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
        )
    }
}

@Composable
private fun EmptyPlaylistMessage(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "В плейлисте пока нет треков",
            style = AppTheme.typography.snPro.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
