@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.milkdev.dreamplayer.model.AlbumSortOrder
import org.milkdev.dreamplayer.model.TrackSortOrder
import org.milkdev.dreamplayer.playback.ResolvedPlaybackItem

expect object MusicLibrarySource {
    val dailyPlaylistTracks: Flow<List<LibraryTrack>>
    val metadataSyncState: StateFlow<MetadataSyncUiState>

    suspend fun getLibrarySummary(): LibrarySummary
    suspend fun getTrackPage(order: TrackSortOrder, cursor: LibraryPageCursor?, limit: Int): LibraryPage<TrackListItem>
    suspend fun getAlbumPage(order: AlbumSortOrder, cursor: LibraryPageCursor?, limit: Int): LibraryPage<AlbumListItem>
    suspend fun getArtistPage(cursor: LibraryPageCursor?, limit: Int): LibraryPage<ArtistListItem>
    suspend fun getGenrePage(cursor: LibraryPageCursor?, limit: Int): LibraryPage<GenreListItem>
    suspend fun searchTrackPage(query: String, mode: TrackSearchMode, cursor: LibraryPageCursor?, limit: Int): LibraryPage<TrackListItem>
    suspend fun getAllTrackIds(order: TrackSortOrder): LongArray
    fun getTracksByAlbum(albumId: Long): Flow<List<LibraryTrack>>
    fun getTracksByArtist(artistId: Long): Flow<List<LibraryTrack>>
    fun getAlbumsByGenre(genreId: Long): Flow<List<AlbumListItem>>
    fun getTracksByGenre(genreId: Long): Flow<List<LibraryTrack>>
    fun getRecentlyPlayedTracks(): Flow<List<LibraryTrack>>
    suspend fun addTrackToHistory(trackId: Long)
    suspend fun getRandomGenreWithTracks(): GenreListItem?
    suspend fun loadTracks()
    fun startMetadataSync()
    fun startMusicBrainzCoverSync()
    suspend fun resolvePlayableItems(ids: LongArray): List<ResolvedPlaybackItem>
    suspend fun resolveDisplayQueue(ids: LongArray): List<LibraryTrack>
}
