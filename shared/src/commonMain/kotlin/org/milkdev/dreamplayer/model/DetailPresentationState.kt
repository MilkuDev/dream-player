package org.milkdev.dreamplayer.model

import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.library.UserPlaylist

sealed interface DetailEntryUiState {
    val isLoading: Boolean
}

data class PlaylistDetailUiState(
    val playlist: UserPlaylist,
    val tracks: List<LibraryTrack> = emptyList(),
    override val isLoading: Boolean = true,
) : DetailEntryUiState

data class LibraryCollectionDetailUiState(
    val collection: LibraryCollectionDetailsUiModel,
    override val isLoading: Boolean = true,
) : DetailEntryUiState

data class DetailPresentationState(
    val activeEntryId: Long? = null,
    val entries: Map<Long, DetailEntryUiState> = emptyMap(),
) {
    fun entry(entryId: Long): DetailEntryUiState? = entries[entryId]

    val activeEntry: DetailEntryUiState?
        get() = activeEntryId?.let(entries::get)
}
