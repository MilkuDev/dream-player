@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.milkdev.dreamplayer.model.AlbumSortOrder
import org.milkdev.dreamplayer.model.TrackSortOrder
import org.milkdev.dreamplayer.playback.ResolvedPlaybackItem

actual object MusicLibrarySource {
    actual val dailyPlaylistTracks: Flow<List<LibraryTrack>>
        get() = TODO("Not yet implemented")
    actual val metadataSyncState: StateFlow<MetadataSyncUiState>
        get() = TODO("Not yet implemented")

    actual suspend fun getLibrarySummary(): LibrarySummary {
        TODO("Not yet implemented")
    }

    actual suspend fun getTrackPage(
        order: TrackSortOrder,
        cursor: LibraryPageCursor?,
        limit: Int
    ): LibraryPage<TrackListItem> {
        TODO("Not yet implemented")
    }

    actual suspend fun getAlbumPage(
        order: AlbumSortOrder,
        cursor: LibraryPageCursor?,
        limit: Int
    ): LibraryPage<AlbumListItem> {
        TODO("Not yet implemented")
    }

    actual suspend fun getArtistPage(
        cursor: LibraryPageCursor?,
        limit: Int
    ): LibraryPage<ArtistListItem> {
        TODO("Not yet implemented")
    }

    actual suspend fun getGenrePage(
        cursor: LibraryPageCursor?,
        limit: Int
    ): LibraryPage<GenreListItem> {
        TODO("Not yet implemented")
    }

    actual suspend fun searchTrackPage(
        query: String,
        mode: TrackSearchMode,
        cursor: LibraryPageCursor?,
        limit: Int
    ): LibraryPage<TrackListItem> {
        TODO("Not yet implemented")
    }

    actual suspend fun getAllTrackIds(order: TrackSortOrder): LongArray {
        TODO("Not yet implemented 'return repository.getAllTrackIds(order)'")
    }

    actual fun getTracksByAlbum(albumId: Long): Flow<List<LibraryTrack>> {
        TODO("Not yet implemented")
    }

    actual fun getTracksByArtist(artistId: Long): Flow<List<LibraryTrack>> {
        TODO("Not yet implemented")
    }

    actual fun getAlbumsByGenre(genreId: Long): Flow<List<AlbumListItem>> {
        TODO("Not yet implemented")
    }

    actual fun getTracksByGenre(genreId: Long): Flow<List<LibraryTrack>> {
        TODO("Not yet implemented")
    }

    actual fun getRecentlyPlayedTracks(): Flow<List<LibraryTrack>> {
        TODO("Not yet implemented")
    }

    actual suspend fun addTrackToHistory(trackId: Long) {
    }

    actual suspend fun getRandomGenreWithTracks(): GenreListItem? {
        TODO("Not yet implemented")
    }

    actual suspend fun loadTracks() {
    }

    actual fun startMetadataSync() {
    }

    actual fun startMusicBrainzCoverSync() {
    }

    actual suspend fun resolvePlayableItems(ids: LongArray): List<ResolvedPlaybackItem> {
        TODO("Not yet implemented")
    }

    actual suspend fun resolveDisplayQueue(ids: LongArray): List<LibraryTrack> {
        TODO("Not yet implemented")
    }
}