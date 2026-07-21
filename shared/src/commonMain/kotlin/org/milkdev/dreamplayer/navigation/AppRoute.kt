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

enum class MainDestination {
    Home,
    Library,
    Search,
}

enum class MainPage {
    Home,
    Library,
}

internal val AppRoute.isPlaybackOverlay: Boolean
    get() = this == AppRoute.Player || this == AppRoute.Queue

internal fun AppRoute.toMainDestinationOrNull(): MainDestination? {
    return when (this) {
        AppRoute.Home -> MainDestination.Home
        AppRoute.Library -> MainDestination.Library
        AppRoute.Search -> MainDestination.Search
        else -> null
    }
}
