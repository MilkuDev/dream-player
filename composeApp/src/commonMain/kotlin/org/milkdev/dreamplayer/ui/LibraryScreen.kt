package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.artist
import org.milkdev.dreamplayer.generated.resources.playlist_add
import org.milkdev.dreamplayer.generated.resources.playlist_play_24dp
import org.milkdev.dreamplayer.generated.resources.settings
import org.milkdev.dreamplayer.library.*
import org.milkdev.dreamplayer.model.*
import org.milkdev.dreamplayer.playback.LibraryUiState

private val TrackSortOrders = listOf(
    TrackSortOrder.TRACK_NAME, TrackSortOrder.ALBUM, TrackSortOrder.ARTIST,
    TrackSortOrder.YEAR, TrackSortOrder.GENRE,
)

private val AlbumSortOrders = listOf(
    AlbumSortOrder.TITLE, AlbumSortOrder.ARTIST,
    AlbumSortOrder.YEAR, AlbumSortOrder.GENRE,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(
    libraryState: LibraryUiState,
    currentTrack: LibraryTrack?,
    onIntent: (LibraryIntent) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
) {
    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()

    val loadUncachedListArtwork = rememberLazyArtworkLoadingEnabled(listState = lazyListState)
    val loadUncachedGridArtwork = rememberLazyArtworkLoadingEnabled(gridState = lazyGridState)

    LaunchedEffect(libraryState.currentCategory) {
        if (!libraryState.isLoading) {
            lazyListState.scrollToItem(0)
            lazyGridState.scrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (libraryState.isLoading) {
            LoadingIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Библиотека",
                        style = AppTheme.typography.snPro.headlineLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(M3ECookie6SidedShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onSettingsClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.settings),
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                LibraryCategoryRow(
                    selectedCategory = libraryState.currentCategory,
                    onCategorySelected = { onIntent(LibraryIntent.SelectCategory(it)) },
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (libraryState.currentCategory) {
                    LibraryCategory.TRACKS -> SortButtonGroupRow(
                        selectedSort = libraryState.trackSortOrder,
                        onSortSelected = { onIntent(LibraryIntent.ChangeTrackSort(it)) },
                        entries = TrackSortOrders,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    LibraryCategory.ALBUMS -> SortButtonGroupRow(
                        selectedSort = libraryState.albumSortOrder,
                        onSortSelected = { onIntent(LibraryIntent.ChangeAlbumSort(it)) },
                        entries = AlbumSortOrders,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    else -> Spacer(modifier = Modifier.padding(bottom = 0.dp))
                }

                when (libraryState.currentCategory) {
                    LibraryCategory.TRACKS -> {

                        val mappedTracks = remember(libraryState.trackListItems) {
                            libraryState.trackListItems.map { it.toLibraryTrack() }
                        }

                        if (libraryState.trackSortOrder == TrackSortOrder.GENRE) {
                            InfiniteGridHandler(lazyGridState) { onIntent(LibraryIntent.LoadNextGenres) }
                            LibraryGenreGrid(
                                genres = libraryState.genreListItems,
                                onIntent = onIntent,
                                gridState = lazyGridState,
                                contentPadding = contentPadding
                            )
                        } else {
                            InfiniteListHandler(lazyListState) { onIntent(LibraryIntent.LoadNextTracks) }
                            LibraryTrackList(
                                tracks = mappedTracks,
                                currentTrack = currentTrack,
                                onIntent = onIntent,
                                loadUncachedArtwork = loadUncachedListArtwork,
                                listState = lazyListState,
                                contentPadding = contentPadding
                            )
                        }
                    }
                    LibraryCategory.ALBUMS -> {
                        if (libraryState.albumSortOrder == AlbumSortOrder.GENRE) {
                            InfiniteGridHandler(lazyGridState) { onIntent(LibraryIntent.LoadNextGenres) }
                            LibraryGenreGrid(
                                genres = libraryState.genreListItems,
                                onIntent = onIntent,
                                gridState = lazyGridState,
                                contentPadding = contentPadding
                            )
                        } else {
                            InfiniteGridHandler(lazyGridState) { onIntent(LibraryIntent.LoadNextAlbums) }
                            LibraryAlbumGrid(
                                albums = libraryState.albumListItems,
                                onIntent = onIntent,
                                loadUncachedArtwork = loadUncachedGridArtwork,
                                gridState = lazyGridState,
                                contentPadding = contentPadding
                            )
                        }
                    }
                    LibraryCategory.ARTISTS -> {
                        InfiniteGridHandler(lazyGridState) { onIntent(LibraryIntent.LoadNextArtists) }
                        LibraryArtistGrid(
                            artists = libraryState.artistListItems,
                            onIntent = onIntent,
                            loadUncachedArtwork = loadUncachedGridArtwork,
                            gridState = lazyGridState,
                            contentPadding = contentPadding
                        )
                    }
                    LibraryCategory.PLAYLISTS -> {

                        val sortedPlaylists = remember(libraryState.playlists) {
                            libraryState.playlists.sortedBy { it.name.lowercase() }
                        }

                        LibraryPlaylistList(
                            playlists = sortedPlaylists,
                            onIntent = onIntent,
                            listState = lazyListState,
                            contentPadding = contentPadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTrackList(
    tracks: List<LibraryTrack>,
    currentTrack: LibraryTrack?,
    onIntent: (LibraryIntent) -> Unit,
    loadUncachedArtwork: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = tracks, key = { it.id }, contentType = { "library_track" }) { track ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onIntent(LibraryIntent.PlayTrack(tracks, track)) },
                colors = CardDefaults.cardColors(
                    containerColor = if (track.id == currentTrack?.id) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
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
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artistName,
                            style = AppTheme.typography.snPro.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryAlbumGrid(
    albums: List<AlbumListItem>,
    onIntent: (LibraryIntent) -> Unit,
    loadUncachedArtwork: Boolean,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        gridItems(items = albums, key = { it.id }, contentType = { "library_album" }) { album ->
            RecommendationCard(
                title = album.title,
                subtitle = "${album.artistName}${album.year?.let { " • $it" } ?: ""}",
                artworkUri = album.artworkUri,
                onClick = { onIntent(LibraryIntent.OpenAlbum(album)) },
                loadUncached = loadUncachedArtwork,
            )
        }
    }
}

@Composable
private fun LibraryArtistGrid(
    artists: List<ArtistListItem>,
    onIntent: (LibraryIntent) -> Unit,
    loadUncachedArtwork: Boolean,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        gridItems(
            items = artists,
            key = { it.id },
            contentType = { "library_artist" },
        ) { artist ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(),
                contentAlignment = Alignment.TopCenter,
            ) {
                RecommendationCard(
                    title = artist.name,
                    subtitle = "${artist.albumCount} альбомов • ${artist.trackCount} треков",
                    artworkUri = artist.artworkUri,
                    fallbackIcon = Res.drawable.artist,
                    imageShape = CircleShape,
                    onClick = { onIntent(LibraryIntent.OpenArtist(artist)) },
                    loadUncached = loadUncachedArtwork,
                )
            }
        }
    }
}

@Composable
private fun LibraryGenreGrid(
    genres: List<GenreListItem>,
    onIntent: (LibraryIntent) -> Unit,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        gridItems(
            items = genres,
            key = { it.id },
            contentType = { "library_genre" },
        ) { genre ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onIntent(LibraryIntent.OpenGenre(genre)) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = genre.name,
                        style = AppTheme.typography.snPro.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${genre.albumCount} альбомов • ${genre.trackCount} треков",
                        style = AppTheme.typography.snPro.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryPlaylistList(
    playlists: List<UserPlaylist>,
    onIntent: (LibraryIntent) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onIntent(LibraryIntent.CreatePlaylist("Новый плейлист")) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(Res.drawable.playlist_add),
                        contentDescription = "Создать",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Создать плейлист",
                        style = AppTheme.typography.snPro.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        items(
            items = playlists,
            key = { it.id },
            contentType = { "library_playlist" },
        ) { playlist ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onIntent(LibraryIntent.OpenPlaylist(playlist)) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(resource = Res.drawable.playlist_play_24dp),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = playlist.name,
                            style = AppTheme.typography.snPro.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (playlist.isSystem) "Системный" else "Пользовательский",
                            style = AppTheme.typography.snPro.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
