package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.NavigationEntry

internal data class ContentSceneSnapshot(
    val currentEntry: NavigationEntry,
    val contentStack: List<NavigationEntry>,
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

internal fun contentSceneSnapshot(
    backStack: List<NavigationEntry>,
): ContentSceneSnapshot {
    val contentStack = contentNavigationEntries(backStack)
    return ContentSceneSnapshot(
        currentEntry = contentStack.last(),
        contentStack = contentStack,
    )
}

internal fun contentSceneSnapshots(
    backStack: List<NavigationEntry>,
): List<ContentSceneSnapshot> {
    val contentStack = contentNavigationEntries(backStack)
    return contentStack.indices.map { index ->
        val stackAtEntry = contentStack.take(index + 1)
        ContentSceneSnapshot(
            currentEntry = stackAtEntry.last(),
            contentStack = stackAtEntry,
        )
    }
}

private fun contentNavigationEntries(
    backStack: List<NavigationEntry>,
): List<NavigationEntry> {
    return backStack.takeWhile { entry ->
        entry.route != AppRoute.Player && entry.route != AppRoute.Queue
    }
}
