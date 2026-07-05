package org.milkdev.dreamplayer.model


import org.milkdev.dreamplayer.library.AlbumListItem
import org.milkdev.dreamplayer.library.ArtistListItem
import org.milkdev.dreamplayer.library.GenreListItem
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.library.UserPlaylist

sealed interface LibraryIntent {

    data class SelectCategory(val category: LibraryCategory) : LibraryIntent
    data class ChangeTrackSort(val order: TrackSortOrder) : LibraryIntent
    data class ChangeAlbumSort(val order: AlbumSortOrder) : LibraryIntent

    data object LoadNextTracks : LibraryIntent
    data object LoadNextAlbums : LibraryIntent
    data object LoadNextArtists : LibraryIntent
    data object LoadNextGenres : LibraryIntent

    data class PlayTrack(val tracks: List<LibraryTrack>, val track: LibraryTrack) : LibraryIntent
    data class OpenPlaylist(val playlist: UserPlaylist) : LibraryIntent
    data class CreatePlaylist(val name: String) : LibraryIntent
    data class OpenArtist(val artist: ArtistListItem) : LibraryIntent
    data class OpenAlbum(val album: AlbumListItem) : LibraryIntent
    data class OpenGenre(val genre: GenreListItem) : LibraryIntent
}