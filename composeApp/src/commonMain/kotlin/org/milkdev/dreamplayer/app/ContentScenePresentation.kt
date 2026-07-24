package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainTab
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.navigation.toMainTabOrNull

internal data class NavigationChromePresentation(
    val isVisible: Boolean,
    val activeMainTab: MainTab,
    val isSearchActive: Boolean,
)

internal data class ContentSceneSnapshot(
    val currentEntry: NavigationEntry,
    val contentStack: List<NavigationEntry>,
    val chrome: NavigationChromePresentation = navigationChromePresentation(
        currentEntry = currentEntry,
        contentStack = contentStack,
    ),
) {
    /**
     * Stable draw order owned by the scene's position in navigation history.
     *
     * AnimatedContent retains the z-index assigned when content enters. Using stack depth keeps a
     * restored scene below the scene being popped on every consecutive Back transition.
     */
    val contentLayer: Float
        get() = contentStack.lastIndex.toFloat()
}

internal data class DestinationChromePresentation(
    val entryId: Long,
    val chrome: NavigationChromePresentation,
)

internal data class ContentChromeLayers(
    val persistent: NavigationChromePresentation?,
    val destination: DestinationChromePresentation?,
)

internal fun contentSceneSnapshot(
    backStack: List<NavigationEntry>,
): ContentSceneSnapshot {
    val contentStack = backStack.takeWhile { entry ->
        entry.route != AppRoute.Player && entry.route != AppRoute.Queue
    }
    return ContentSceneSnapshot(
        currentEntry = contentStack.last(),
        contentStack = contentStack,
    )
}

internal fun resolveContentChromeLayers(
    committedScene: ContentSceneSnapshot,
    backSession: ContentBackSession?,
): ContentChromeLayers {
    if (backSession == null) {
        return ContentChromeLayers(
            persistent = committedScene.chrome.takeIf { it.isVisible },
            destination = null,
        )
    }

    val originChrome = backSession.origin.chrome
    val destinationChrome = backSession.preview.chrome
    return ContentChromeLayers(
        persistent = originChrome.takeIf { it.isVisible },
        destination = destinationChrome
            .takeIf { !originChrome.isVisible && it.isVisible }
            ?.let { chrome ->
                DestinationChromePresentation(
                    entryId = backSession.preview.currentEntry.entryId,
                    chrome = chrome,
                )
            },
    )
}

private fun navigationChromePresentation(
    currentEntry: NavigationEntry,
    contentStack: List<NavigationEntry>,
): NavigationChromePresentation {
    val activeMainTab = contentStack.firstNotNullOfOrNull { entry ->
        entry.route.toMainTabOrNull()
    } ?: MainTab.Home
    return NavigationChromePresentation(
        isVisible = when (currentEntry.route) {
            AppRoute.Settings,
            AppRoute.AiDebugSettings -> false

            else -> true
        },
        activeMainTab = activeMainTab,
        isSearchActive = contentStack.any { it.route == AppRoute.Search },
    )
}
