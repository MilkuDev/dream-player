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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import org.milkdev.dreamplayer.generated.resources.album
import org.milkdev.dreamplayer.generated.resources.arrow_back
import org.milkdev.dreamplayer.generated.resources.artist
import org.milkdev.dreamplayer.library.AlbumListItem
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.model.LibraryCollectionType
import org.milkdev.dreamplayer.playback.LibraryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCollectionDetailsScreen(
    modifier: Modifier = Modifier,
    libraryState: LibraryUiState,
    onBackClick: () -> Unit,
    onTrackClick: (LibraryTrack) -> Unit,
    onAlbumClick: (AlbumListItem) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues.Zero,
    currentTrackId: Long? = null,
) {
    val collection = libraryState.selectedLibraryCollection
    var selectedGenreTab by remember(collection?.title) { mutableStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = collection?.title ?: "Коллекция",
                            style = AppTheme.typography.snPro.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        collection?.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = AppTheme.typography.snPro.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
    ) { innerPadding ->
        val isGenre = collection?.type == LibraryCollectionType.GENRE
        val tracks = collection?.tracks.orEmpty()
        val albums = collection?.albums.orEmpty()
        val isEmpty = if (isGenre && selectedGenreTab == 0) albums.isEmpty() else tracks.isEmpty()

        if (isEmpty && !isGenre) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Здесь пока нет треков",
                    style = AppTheme.typography.snPro.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (isGenre) {
                    PrimaryTabRow(selectedTabIndex = selectedGenreTab) {
                        Tab(
                            selected = selectedGenreTab == 0,
                            onClick = { selectedGenreTab = 0 },
                            text = { Text("Альбомы") },
                        )
                        Tab(
                            selected = selectedGenreTab == 1,
                            onClick = { selectedGenreTab = 1 },
                            text = { Text("Треки") },
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = contentPadding.calculateBottomPadding() + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isGenre && selectedGenreTab == 0) {
                        if (albums.isEmpty()) {
                            item(contentType = "empty_genre_albums") {
                                EmptyCollectionText("Здесь пока нет альбомов")
                            }
                        } else {
                            items(
                                items = albums,
                                key = { it.id },
                                contentType = { "library_collection_album" },
                            ) { album ->
                                LibraryCollectionAlbumItem(
                                    album = album,
                                    onClick = { onAlbumClick(album) },
                                )
                            }
                        }
                    } else {
                        if (tracks.isEmpty()) {
                            item(contentType = "empty_genre_tracks") {
                                EmptyCollectionText("Здесь пока нет треков")
                            }
                        } else {
                            items(
                                items = tracks,
                                key = { it.id },
                                contentType = { "library_collection_track" },
                            ) { track ->
                                LibraryCollectionTrackItem(
                                    track = track,
                                    selected = track.id == currentTrackId,
                                    fallbackIcon = when (collection?.type) {
                                        LibraryCollectionType.ARTIST -> Res.drawable.artist
                                        LibraryCollectionType.ALBUM,
                                        LibraryCollectionType.GENRE,
                                        null -> Res.drawable.album
                                    },
                                    onClick = { onTrackClick(track) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCollectionText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AppTheme.typography.snPro.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryCollectionAlbumItem(
    album: AlbumListItem,
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
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            TrackImage(
                uri = album.artworkUri,
                modifier = Modifier.fillMaxSize(),
                fallbackIcon = Res.drawable.album,
                maxDecodeSizePx = 160,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = AppTheme.typography.snPro.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artistName,
                style = AppTheme.typography.snPro.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryCollectionTrackItem(
    track: LibraryTrack,
    selected: Boolean,
    fallbackIcon: org.jetbrains.compose.resources.DrawableResource,
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
                fallbackIcon = fallbackIcon,
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
