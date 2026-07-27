package org.milkdev.dreamplayer.navigation

import org.milkdev.dreamplayer.library.LibraryCollectionType

sealed interface AppRoute {
    data object Home : AppRoute
    data object Library : AppRoute
    data object Search : AppRoute

    data class Playlist(
        val playlistId: Long,
    ) : AppRoute

    data class LibraryCollection(
        val type: LibraryCollectionType,
        val collectionId: Long,
    ) : AppRoute

    data object Settings : AppRoute
    data object AiDebugSettings : AppRoute

    data object Player : AppRoute
    data object Queue : AppRoute
}

enum class MainTab(
    val position: Int,
    internal val stableEntryId: Long,
) {
    Home(position = 0, stableEntryId = 0L),
    Library(position = 1, stableEntryId = 1L),
}

internal val AppRoute.isPlaybackOverlay: Boolean
    get() = this == AppRoute.Player || this == AppRoute.Queue

fun AppRoute.toMainTabOrNull(): MainTab? {
    return when (this) {
        AppRoute.Home -> MainTab.Home
        AppRoute.Library -> MainTab.Library
        else -> null
    }
}
