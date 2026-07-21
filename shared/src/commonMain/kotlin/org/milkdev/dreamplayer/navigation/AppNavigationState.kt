package org.milkdev.dreamplayer.navigation

class AppNavigationState private constructor(
    val backStack: List<NavigationEntry>,
    private val nextEntryId: Long,
) {
    constructor() : this(
        backStack = listOf(HomeEntry),
        nextEntryId = FirstDynamicEntryId,
    )

    init {
        require(backStack.isNotEmpty()) { "Navigation stack must not be empty" }
        require(backStack.first() == HomeEntry) { "Home must be the first navigation entry" }
        require(backStack.map { it.entryId }.distinct().size == backStack.size) {
            "Navigation entry IDs must be unique within the active stack"
        }
        require(hasValidPlaybackSuffix(backStack)) {
            "Playback overlays must form a Player or Player -> Queue suffix"
        }
        require(hasValidSettingsOrder(backStack)) {
            "AiDebugSettings must immediately follow Settings"
        }
    }

    val currentEntry: NavigationEntry
        get() = backStack.last()

    val currentDestination: AppRoute
        get() = currentEntry.route

    val currentContentEntry: NavigationEntry
        get() = backStack.asReversed()
            .first { !it.route.isPlaybackOverlay }

    val activeMainDestination: MainDestination
        get() = backStack.asReversed()
            .firstNotNullOfOrNull { it.route.toMainDestinationOrNull() }
            ?: MainDestination.Home

    val canNavigateBack: Boolean
        get() = backStack.size > 1

    fun contains(route: AppRoute): Boolean {
        return backStack.any { it.route == route }
    }

    fun selectMainPage(page: MainPage): AppNavigationState {
        val nextStack = when (page) {
            MainPage.Home -> listOf(HomeEntry)
            MainPage.Library -> listOf(HomeEntry, LibraryEntry)
        }
        return withStack(nextStack)
    }

    fun openSearch(): AppNavigationState {
        val existingSearchIndex = backStack.indexOfLast { it.route == AppRoute.Search }
        if (existingSearchIndex >= 0 && activeMainDestination == MainDestination.Search) {
            return withStack(backStack.take(existingSearchIndex + 1))
        }

        val anchor = when (activeMainDestination) {
            MainDestination.Library -> listOf(HomeEntry, LibraryEntry)
            MainDestination.Home,
            MainDestination.Search -> listOf(HomeEntry)
        }
        return withPushedRoute(
            baseStack = anchor,
            route = AppRoute.Search,
        )
    }

    fun closeSearch(): AppNavigationState {
        val searchIndex = backStack.indexOfLast { it.route == AppRoute.Search }
        if (searchIndex < 0) return this
        return withStack(backStack.take(searchIndex))
    }

    fun push(route: AppRoute): AppNavigationState {
        if (currentDestination == route) return this

        return when (route) {
            AppRoute.Home,
            AppRoute.Library,
            AppRoute.Search -> this

            AppRoute.Player -> {
                if (backStack.any { it.route == AppRoute.Player }) {
                    this
                } else {
                    withPushedRoute(backStack, route)
                }
            }

            AppRoute.Queue -> {
                if (currentDestination == AppRoute.Player) {
                    withPushedRoute(backStack, route)
                } else {
                    this
                }
            }

            AppRoute.AiDebugSettings -> {
                if (currentDestination == AppRoute.Settings) {
                    withPushedRoute(backStack, route)
                } else {
                    this
                }
            }

            else -> {
                if (backStack.any { it.route.isPlaybackOverlay }) {
                    this
                } else {
                    withPushedRoute(backStack, route)
                }
            }
        }
    }

    fun pop(expectedTopEntryId: Long? = null): AppNavigationState? {
        if (expectedTopEntryId != null && currentEntry.entryId != expectedTopEntryId) {
            return null
        }
        if (!canNavigateBack) return null
        return withStack(backStack.dropLast(1))
    }

    fun previewBack(): AppNavigationState? {
        return pop()
    }

    fun removePlaybackOverlays(): AppNavigationState {
        val firstOverlayIndex = backStack.indexOfFirst { it.route.isPlaybackOverlay }
        if (firstOverlayIndex < 0) return this
        return withStack(backStack.take(firstOverlayIndex))
    }

    private fun withPushedRoute(
        baseStack: List<NavigationEntry>,
        route: AppRoute,
    ): AppNavigationState {
        val entry = NavigationEntry(
            entryId = nextEntryId,
            route = route,
        )
        return AppNavigationState(
            backStack = baseStack + entry,
            nextEntryId = nextDynamicEntryId(nextEntryId),
        )
    }

    private fun withStack(nextStack: List<NavigationEntry>): AppNavigationState {
        if (nextStack == backStack) return this
        return AppNavigationState(
            backStack = nextStack,
            nextEntryId = nextEntryId,
        )
    }

    private companion object {
        const val HomeEntryId = 0L
        const val LibraryEntryId = 1L
        const val FirstDynamicEntryId = 2L

        val HomeEntry = NavigationEntry(
            entryId = HomeEntryId,
            route = AppRoute.Home,
        )
        val LibraryEntry = NavigationEntry(
            entryId = LibraryEntryId,
            route = AppRoute.Library,
        )

        fun nextDynamicEntryId(current: Long): Long {
            check(current < Long.MAX_VALUE) { "Navigation entry ID space is exhausted" }
            return current + 1L
        }

        fun hasValidPlaybackSuffix(stack: List<NavigationEntry>): Boolean {
            val firstOverlayIndex = stack.indexOfFirst { it.route.isPlaybackOverlay }
            if (firstOverlayIndex < 0) return true

            val overlayRoutes = stack.drop(firstOverlayIndex).map { it.route }
            return overlayRoutes == listOf(AppRoute.Player) ||
                overlayRoutes == listOf(AppRoute.Player, AppRoute.Queue)
        }

        fun hasValidSettingsOrder(stack: List<NavigationEntry>): Boolean {
            return stack.withIndex().all { (index, entry) ->
                entry.route != AppRoute.AiDebugSettings ||
                    stack.getOrNull(index - 1)?.route == AppRoute.Settings
            }
        }
    }
}
