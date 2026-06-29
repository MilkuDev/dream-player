package org.milkdev.dreamplayer.model

import org.milkdev.dreamplayer.library.AlbumListItem
import org.milkdev.dreamplayer.library.LibraryTrack

enum class LibraryCategory(val label: String) {
    TRACKS("Треки"),
    ALBUMS("Альбомы"),
    ARTISTS("Исполнители"),
    PLAYLISTS("Плейлисты")
}

enum class LibraryCollectionType {
    ALBUM,
    ARTIST,
    GENRE,
}

data class LibraryCollectionDetailsUiModel(
    val type: LibraryCollectionType,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val tracks: List<LibraryTrack>,
    val albums: List<AlbumListItem> = emptyList(),
)

