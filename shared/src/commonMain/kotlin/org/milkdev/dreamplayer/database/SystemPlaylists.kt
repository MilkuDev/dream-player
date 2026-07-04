package org.milkdev.dreamplayer.database

data class SystemPlaylistPermissions(
    val visibleInLibrary: Boolean,
    val canEditTracks: Boolean,
    val canRename: Boolean,
)

data class SystemPlaylistDefinition(
    val id: Long,
    val name: String,
    val createdAt: Long = 0L,
    val permissions: SystemPlaylistPermissions,
)

object SystemPlaylists {
    const val DAILY_PLAYLIST_ID = -1L
    const val DAILY_PLAYLIST_NAME = "Плейлист дня"
    const val FAVORITES_PLAYLIST_ID = -2L
    const val FAVORITES_PLAYLIST_NAME = "Избранное"

    val DailyPlaylist = SystemPlaylistDefinition(
        id = DAILY_PLAYLIST_ID,
        name = DAILY_PLAYLIST_NAME,
        permissions = SystemPlaylistPermissions(
            visibleInLibrary = false,
            canEditTracks = false,
            canRename = false,
        ),
    )

    val Favorites = SystemPlaylistDefinition(
        id = FAVORITES_PLAYLIST_ID,
        name = FAVORITES_PLAYLIST_NAME,
        permissions = SystemPlaylistPermissions(
            visibleInLibrary = true,
            canEditTracks = true,
            canRename = false,
        ),
    )

    val All = listOf(DailyPlaylist, Favorites)
    val VisibleInLibrary = All.filter { it.permissions.visibleInLibrary }

    fun byId(id: Long): SystemPlaylistDefinition? {
        return All.firstOrNull { it.id == id }
    }
}
