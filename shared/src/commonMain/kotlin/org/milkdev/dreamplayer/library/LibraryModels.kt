package org.milkdev.dreamplayer.library

data class LibraryTrack(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumName: String,
    val durationMs: Long,
    val albumArtUri: String?,
    val isPresent: Boolean = true,
    val contentVersion: Long = 0L,
)

enum class CoverSource {
    EMBEDDED,
    LOCAL_CACHE,
    REMOTE,
    NONE
}

enum class MetadataState {
    NOT_SYNCED,
    PENDING,
    DONE,
    FAILED,
    NO_MATCH
}

enum class CoverLookupState {
    NOT_SYNCED, // TODO:
    PENDING,
    DONE,
    FAILED,
    NO_MATCH
}

data class AlbumListItem(
    val id: Long,
    val title: String,
    val artistName: String,
    val year: Int?,
    val genre: String?,
    val artworkUri: String?,
)

data class TrackListItem(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val artworkUri: String?,
) {
    fun toLibraryTrack(): LibraryTrack {
        return LibraryTrack(
            id = id,
            title = title,
            artistName = artistName,
            albumName = albumTitle,
            durationMs = durationMs,
            albumArtUri = artworkUri,
            isPresent = true
        )
    }
}

data class ArtistListItem(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val artworkUri: String?,
)

data class GenreListItem(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
)

data class LibrarySummary(
    val trackCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val pendingMetadataCount: Int = 0,
)

data class MetadataSyncUiState(
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val coverPendingCount: Int = 0,
    val lastFmPendingCount: Int = 0,
    val processedCount: Int = 0,
    val message: String? = null,
)

data class LibraryPageCursor(
    val sortKey: String = "",
    val tieKey: String = "",
    val numericKey: Int? = null,
    val missingBucket: Int = 0,
    val id: Long = 0,
)

data class LibraryPage<T>(
    val items: List<T>,
    val nextCursor: LibraryPageCursor?,
    val hasMore: Boolean,
)
