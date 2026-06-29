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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import org.milkdev.dreamplayer.generated.resources.artist
import org.milkdev.dreamplayer.library.GenreListItem
import org.milkdev.dreamplayer.generated.resources.playlist_add
import org.milkdev.dreamplayer.generated.resources.playlist_play_24dp
import org.milkdev.dreamplayer.library.ArtistListItem
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.library.UserPlaylist
import org.milkdev.dreamplayer.model.AlbumSortOrder
import org.milkdev.dreamplayer.model.LibraryCategory
import org.milkdev.dreamplayer.model.TrackSortOrder
import org.milkdev.dreamplayer.playback.PlayerUiState
import kotlin.time.Duration.Companion.seconds

private val TrackSortOrders = listOf(
    TrackSortOrder.TRACK_NAME,
    TrackSortOrder.ALBUM,
    TrackSortOrder.ARTIST,
    TrackSortOrder.YEAR,
    TrackSortOrder.GENRE,
)

private val AlbumSortOrders = listOf(
    AlbumSortOrder.TITLE,
    AlbumSortOrder.ARTIST,
    AlbumSortOrder.YEAR,
    AlbumSortOrder.GENRE,
)

private var isFirstLaunchDebug = true

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    state: PlayerUiState,
    onTrackClick: (List<LibraryTrack>, LibraryTrack) -> Unit,
    onPlaylistClick: (UserPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onArtistClick: (ArtistListItem) -> Unit = {},
    onAlbumItemClick: (org.milkdev.dreamplayer.library.AlbumListItem) -> Unit = {},
    onGenreClick: (GenreListItem) -> Unit = {},
    onSortTrack: (TrackSortOrder) -> Unit = {},
    onSortAlbum: (AlbumSortOrder) -> Unit = {},
    onLoadNextTracks: () -> Unit = {},
    onLoadNextAlbums: () -> Unit = {},
    onLoadNextArtists: () -> Unit = {},
    onLoadNextGenres: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues.Zero,
) {
    var currentCategory by rememberSaveable { mutableStateOf(LibraryCategory.TRACKS) }

    val sortedPlaylists = remember(state.playlists) {
        state.playlists.sortedBy { it.name.lowercase() }
    }

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    val loadUncachedListArtwork = rememberLazyArtworkLoadingEnabled(listState = lazyListState)
    val loadUncachedGridArtwork = rememberLazyArtworkLoadingEnabled(gridState = lazyGridState)

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

    val shouldLoadMoreTracks by remember {
        derivedStateOf {
            val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.trackListItems.lastIndex - 8
        }
    }
    val shouldLoadMoreAlbums by remember {
        derivedStateOf {
            val lastVisible = lazyGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.albumListItems.lastIndex - 8
        }
    }
    val shouldLoadMoreArtists by remember {
        derivedStateOf {
            val lastVisible = lazyGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.artistListItems.lastIndex - 8
        }
    }
    val shouldLoadMoreGenres by remember {
        derivedStateOf {
            val lastVisible = lazyGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.genreListItems.lastIndex - 8
        }
    }

    LaunchedEffect(currentCategory) {
        if (!isDebugLoading) {
            lazyListState.scrollToItem(0)
            lazyGridState.scrollToItem(0)
        }
    }

    LaunchedEffect(currentCategory, shouldLoadMoreTracks, state.hasMoreTracks, state.isTrackPageLoading) {
        if (currentCategory == LibraryCategory.TRACKS && shouldLoadMoreTracks && state.hasMoreTracks && !state.isTrackPageLoading) {
            onLoadNextTracks()
        }
    }

    LaunchedEffect(currentCategory, shouldLoadMoreAlbums, state.hasMoreAlbums, state.isAlbumPageLoading) {
        if (currentCategory == LibraryCategory.ALBUMS && shouldLoadMoreAlbums && state.hasMoreAlbums && !state.isAlbumPageLoading) {
            onLoadNextAlbums()
        }
    }

    LaunchedEffect(currentCategory, shouldLoadMoreArtists, state.hasMoreArtists, state.isArtistPageLoading) {
        if (currentCategory == LibraryCategory.ARTISTS && shouldLoadMoreArtists && state.hasMoreArtists && !state.isArtistPageLoading) {
            onLoadNextArtists()
        }
    }

    LaunchedEffect(
        currentCategory,
        state.trackSortOrder,
        state.albumSortOrder,
        shouldLoadMoreGenres,
        state.hasMoreGenres,
        state.isGenrePageLoading,
    ) {
        val isGenreMode =
            (currentCategory == LibraryCategory.TRACKS && state.trackSortOrder == TrackSortOrder.GENRE) ||
                (currentCategory == LibraryCategory.ALBUMS && state.albumSortOrder == AlbumSortOrder.GENRE)
        if (isGenreMode && shouldLoadMoreGenres && state.hasMoreGenres && !state.isGenrePageLoading) {
            onLoadNextGenres()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isDebugLoading) {
            LoadingIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                    Text(
                        text = "Библиотека",
                        style = AppTheme.typography.snPro.headlineLarge,
                    )
                }

                LibraryCategoryRow(
                    selectedCategory = currentCategory,
                    onCategorySelected = { currentCategory = it },
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (currentCategory) {
                    LibraryCategory.TRACKS -> SortButtonGroupRow(
                        selectedSort = state.trackSortOrder,
                        onSortSelected = onSortTrack,
                        entries = TrackSortOrders,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    LibraryCategory.ALBUMS -> SortButtonGroupRow(
                        selectedSort = state.albumSortOrder,
                        onSortSelected = onSortAlbum,
                        entries = AlbumSortOrders,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    LibraryCategory.ARTISTS,
                    LibraryCategory.PLAYLISTS -> Spacer(modifier = Modifier.padding(bottom = 0.dp))
                }

                when (currentCategory) {
                    LibraryCategory.TRACKS -> if (state.trackSortOrder == TrackSortOrder.GENRE) {
                        LibraryGenreGrid(
                            genres = state.genreListItems,
                            onGenreClick = onGenreClick,
                            gridState = lazyGridState,
                            contentPadding = contentPadding,
                        )
                    } else {
                        LibraryTrackList(
                            tracks = state.trackListItems.map { it.toLibraryTrack() },
                            currentTrack = state.currentTrack,
                            onTrackClick = onTrackClick,
                            loadUncachedArtwork = loadUncachedListArtwork,
                            listState = lazyListState,
                            contentPadding = contentPadding,
                        )
                    }
                    LibraryCategory.ALBUMS -> if (state.albumSortOrder == AlbumSortOrder.GENRE) {
                        LibraryGenreGrid(
                            genres = state.genreListItems,
                            onGenreClick = onGenreClick,
                            gridState = lazyGridState,
                            contentPadding = contentPadding,
                        )
                    } else {
                        LibraryAlbumGrid(
                            albums = state.albumListItems,
                            onAlbumClick = onAlbumItemClick,
                            loadUncachedArtwork = loadUncachedGridArtwork,
                            gridState = lazyGridState,
                            contentPadding = contentPadding,
                        )
                    }
                    LibraryCategory.ARTISTS -> LibraryArtistGrid(
                        artists = state.artistListItems,
                        onArtistClick = onArtistClick,
                        loadUncachedArtwork = loadUncachedGridArtwork,
                        gridState = lazyGridState,
                        contentPadding = contentPadding,
                    )
                    LibraryCategory.PLAYLISTS -> LibraryPlaylistList(
                        playlists = sortedPlaylists,
                        onPlaylistClick = onPlaylistClick,
                        onCreatePlaylist = onCreatePlaylist,
                        listState = lazyListState,
                        contentPadding = contentPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTrackList(
    tracks: List<LibraryTrack>,
    currentTrack: LibraryTrack?,
    onTrackClick: (List<LibraryTrack>, LibraryTrack) -> Unit,
    loadUncachedArtwork: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
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
        items(
            items = tracks,
            key = { it.id },
            contentType = { "library_track" },
        ) { track ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onTrackClick(tracks, track) },
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
    albums: List<org.milkdev.dreamplayer.library.AlbumListItem>,
    onAlbumClick: (org.milkdev.dreamplayer.library.AlbumListItem) -> Unit,
    loadUncachedArtwork: Boolean,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
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
            items = albums,
            key = { it.id },
            contentType = { "library_album" },
        ) { album ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(),
                contentAlignment = Alignment.TopCenter,
            ) {
                RecommendationCard(
                    title = album.title,
                    subtitle = "${album.artistName}${album.year?.let { " • $it" } ?: ""}",
                    artworkUri = album.artworkUri,
                    onClick = { onAlbumClick(album) },
                    loadUncached = loadUncachedArtwork,
                )
            }
        }
    }
}

@Composable
private fun LibraryArtistGrid(
    artists: List<ArtistListItem>,
    onArtistClick: (ArtistListItem) -> Unit,
    loadUncachedArtwork: Boolean,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
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
                    onClick = { onArtistClick(artist) },
                    loadUncached = loadUncachedArtwork,
                )
            }
        }
    }
}

@Composable
private fun LibraryGenreGrid(
    genres: List<GenreListItem>,
    onGenreClick: (GenreListItem) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
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
                    .clickable { onGenreClick(genre) },
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
    onPlaylistClick: (UserPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
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
                    .clickable { onCreatePlaylist("Новый плейлист") },
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
                    .clickable { onPlaylistClick(playlist) },
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
