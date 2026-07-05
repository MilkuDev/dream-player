@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.milkdev.dreamplayer.database.SystemPlaylists
import org.milkdev.dreamplayer.database.appDatabase
import org.milkdev.dreamplayer.model.AlbumSortOrder
import org.milkdev.dreamplayer.model.TrackSortOrder
import org.milkdev.dreamplayer.playback.ResolvedPlaybackItem
import org.milkdev.dreamplayer.database.entities.TrackEntity

actual object MusicLibrarySource {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val repository = MusicRepository(
        musicDao = appDatabase.musicDao(),
        scanner = MediaStoreScanner(),
        scope = scope
    )

    actual val dailyPlaylistTracks: Flow<List<LibraryTrack>> = appDatabase.playlistDao()
        .getTracksForPlaylist(SystemPlaylists.DAILY_PLAYLIST_ID)
        .map { tracks -> tracks.map { it.toLibraryTrack() } }

    actual val metadataSyncState: StateFlow<MetadataSyncUiState> = repository.metadataSyncState

    actual fun getRecentlyPlayedTracks(): Flow<List<LibraryTrack>> {
        return repository.getRecentlyPlayedTracks()
    }

    actual suspend fun addTrackToHistory(trackId: Long) {
        repository.addTrackToHistory(trackId)
    }

    actual suspend fun getRandomGenreWithTracks(): GenreListItem? {
        val dbResult = repository.getRandomGenreWithTracks() ?: return null
        return GenreListItem(
            id = dbResult.id,
            name = dbResult.name,
            albumCount = 0,
            trackCount = 0,
        )
    }

    actual suspend fun getLibrarySummary(): LibrarySummary {
        return repository.getLibrarySummary()
    }

    actual suspend fun getTrackPage(
        order: TrackSortOrder,
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<TrackListItem> {
        return repository.getTrackPage(order, cursor, limit)
    }

    actual suspend fun getAlbumPage(
        order: AlbumSortOrder,
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<AlbumListItem> {
        return repository.getAlbumPage(order, cursor, limit)
    }

    actual suspend fun getArtistPage(
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<ArtistListItem> {
        return repository.getArtistPage(cursor, limit)
    }

    actual suspend fun getGenrePage(
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<GenreListItem> {
        return repository.getGenrePage(cursor, limit)
    }

    actual suspend fun searchTrackPage(
        query: String,
        mode: TrackSearchMode,
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<TrackListItem> {
        return repository.searchTrackPage(query, mode, cursor, limit)
    }

    actual suspend fun getAllTrackIds(order: TrackSortOrder): LongArray {
        return repository.getAllTrackIds(order)
    }

    actual fun getTracksByAlbum(albumId: Long): Flow<List<LibraryTrack>> {
        return repository.getTracksByAlbum(albumId)
    }

    actual fun getTracksByArtist(artistId: Long): Flow<List<LibraryTrack>> {
        return repository.getTracksByArtist(artistId)
    }

    actual fun getAlbumsByGenre(genreId: Long): Flow<List<AlbumListItem>> {
        return repository.getAlbumsByGenre(genreId)
    }

    actual fun getTracksByGenre(genreId: Long): Flow<List<LibraryTrack>> {
        return repository.getTracksByGenre(genreId)
    }

    actual suspend fun loadTracks() {
        repository.sync()
    }

    actual fun startMetadataSync() {
        repository.startMetadataSync()
    }

    actual fun startMusicBrainzCoverSync() {
        repository.startMusicBrainzCoverSync()
    }

    actual suspend fun resolvePlayableItems(ids: LongArray): List<ResolvedPlaybackItem> {
        return repository.resolvePlayableItems(ids)
    }

    actual suspend fun resolveDisplayQueue(ids: LongArray): List<LibraryTrack> {
        return repository.resolveDisplayQueue(ids)
    }
}

private fun TrackEntity.toLibraryTrack(): LibraryTrack {
    return LibraryTrack(
        id = id,
        title = title,
        artistName = artistName,
        albumName = albumName,
        durationMs = durationMs,
        albumArtUri = albumArtUri,
        isPresent = isPresent,
    )
}
