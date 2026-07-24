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
        val rootTab = requireNotNull(backStack.first().route.toMainTabOrNull()) {
            "Navigation stack must start with a main tab"
        }
        require(backStack.first() == rootEntry(rootTab)) {
            "Navigation stack must use the stable entry for its main tab"
        }
        require(backStack.drop(1).none { it.route.toMainTabOrNull() != null }) {
            "Only the first navigation entry may be a main tab"
        }
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

    val activeMainTab: MainTab
        get() = checkNotNull(backStack.first().route.toMainTabOrNull())

    val isSettingsFlowActive: Boolean
        get() = backStack.any { it.route == AppRoute.Settings }

    val canUseMainDestinationDock: Boolean
        get() = !isSettingsFlowActive

    val canNavigateBack: Boolean
        get() = backStack.size > 1 || activeMainTab != MainTab.Home

    fun contains(route: AppRoute): Boolean {
        return backStack.any { it.route == route }
    }

    fun selectMainTab(tab: MainTab): AppNavigationState {
        if (!canUseMainDestinationDock) return this
        return withStack(listOf(rootEntry(tab)))
    }

    fun openSearch(): AppNavigationState {
        if (!canUseMainDestinationDock) return this
        val existingSearchIndex = backStack.indexOfLast { it.route == AppRoute.Search }
        if (existingSearchIndex >= 0) {
            return withStack(backStack.take(existingSearchIndex + 1))
        }

        return withPushedRoute(
            baseStack = listOf(backStack.first()),
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

    fun navigateBack(expectedTopEntryId: Long? = null): AppNavigationState? {
        if (expectedTopEntryId != null && currentEntry.entryId != expectedTopEntryId) {
            return null
        }
        return when {
            backStack.size > 1 -> withStack(backStack.dropLast(1))
            activeMainTab != MainTab.Home -> selectMainTab(MainTab.Home)
            else -> null
        }
    }

    fun previewBack(): AppNavigationState? {
        return navigateBack()
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
        const val FirstDynamicEntryId = 1_024L

        val HomeEntry = rootEntry(MainTab.Home)

        fun nextDynamicEntryId(current: Long): Long {
            check(current < Long.MAX_VALUE) { "Navigation entry ID space is exhausted" }
            return current + 1L
        }

        fun rootEntry(tab: MainTab): NavigationEntry {
            return NavigationEntry(
                entryId = tab.stableEntryId,
                route = when (tab) {
                    MainTab.Home -> AppRoute.Home
                    MainTab.Library -> AppRoute.Library
                },
            )
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
