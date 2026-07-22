package org.milkdev.dreamplayer.playback


import org.milkdev.dreamplayer.database.DailyPlaylistGenerationMode
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistPromptPresets
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders
import org.milkdev.dreamplayer.extensions.network.LastFmSettingsUiState
import org.milkdev.dreamplayer.features.PlatformFeatureStatus
import org.milkdev.dreamplayer.library.AlbumListItem
import org.milkdev.dreamplayer.library.ArtistListItem
import org.milkdev.dreamplayer.library.GenreListItem
import org.milkdev.dreamplayer.library.LibrarySummary
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.library.LibrarySearchState
import org.milkdev.dreamplayer.library.TrackListItem
import org.milkdev.dreamplayer.library.UserPlaylist
import org.milkdev.dreamplayer.model.AlbumSortOrder
import org.milkdev.dreamplayer.model.LibraryCategory
import org.milkdev.dreamplayer.model.TrackSortOrder


sealed interface DailyPlaylistUiState {
    object Loading : DailyPlaylistUiState
    object Empty : DailyPlaylistUiState
    data class Available(val tracks: List<LibraryTrack>) : DailyPlaylistUiState
}

enum class PlaybackRepeatMode {
    Off, Queue, One
}

data class PlaybackUiState(
    val currentTrack: LibraryTrack? = null,
    val isPlaying: Boolean = false,
    val totalDurationMs: Long = 0L,
    val playbackQueue: List<LibraryTrack> = emptyList(),
    val currentQueueIndex: Int = -1,
    val queueVersion: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    val isCurrentTrackFavorite: Boolean = false,
)

data class LibraryUiState(
    val tracks: List<LibraryTrack> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    val currentCategory: LibraryCategory = LibraryCategory.TRACKS,
    val recentlyPlayedTracks: List<LibraryTrack> = emptyList(),
    val homeGenreTitle: String = "",
    val homeGenreTracks: List<LibraryTrack> = emptyList(),
    val dailyPlaylistState: DailyPlaylistUiState = DailyPlaylistUiState.Loading,
    val isLoading: Boolean = false,
    val error: String? = null,
    val librarySearch: LibrarySearchState = LibrarySearchState(),
    val librarySummary: LibrarySummary = LibrarySummary(),
    val trackListItems: List<TrackListItem> = emptyList(),
    val albumListItems: List<AlbumListItem> = emptyList(),
    val artistListItems: List<ArtistListItem> = emptyList(),
    val genreListItems: List<GenreListItem> = emptyList(),
    val searchTrackListItems: List<TrackListItem> = emptyList(),
    val playlistPickerTrackItems: List<TrackListItem> = emptyList(),
    val trackSortOrder: TrackSortOrder = TrackSortOrder.TRACK_NAME,
    val albumSortOrder: AlbumSortOrder = AlbumSortOrder.TITLE,
    val hasMoreTracks: Boolean = true,
    val hasMoreAlbums: Boolean = true,
    val hasMoreArtists: Boolean = true,
    val hasMoreGenres: Boolean = true,
    val hasMoreSearchTracks: Boolean = false,
    val hasMorePlaylistPickerTracks: Boolean = true,
    val isTrackPageLoading: Boolean = false,
    val isAlbumPageLoading: Boolean = false,
    val isArtistPageLoading: Boolean = false,
    val isGenrePageLoading: Boolean = false,
    val isSearchPageLoading: Boolean = false,
    val isPlaylistPickerPageLoading: Boolean = false,
)

data class SettingsUiState(
    val isBlurEnabled: Boolean = true,
    val isForceNightMode: Boolean = false,
    val aiDailyPlaylistFeature: PlatformFeatureStatus = PlatformFeatureStatus(enabled = false),
    val dailyPlaylistGenerationMode: DailyPlaylistGenerationMode = DailyPlaylistGenerationMode.LOCAL_DAILY,
    val aiPlaylistProviderId: String = AiPlaylistProviders.OpenAi.id,
    val aiPlaylistModel: String = AiPlaylistProviders.OpenAi.defaultModelId,
    val aiPlaylistPromptPresetId: String = AiPlaylistPromptPresets.DEFAULT_ID,
    val aiPlaylistCustomSystemPrompt: String = "",
    val isAiPlaylistApiKeyConfigured: Boolean = false,
    val isAnyAiPlaylistApiKeyConfigured: Boolean = false,
    val aiPlaylistApiTestStatus: String? = null,
    val lastFmSettings: LastFmSettingsUiState = LastFmSettingsUiState(),
)
