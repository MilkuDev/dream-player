package org.milkdev.dreamplayer.app

import androidx.compose.runtime.Immutable
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainTab

internal sealed interface GlobalDockContent {
    data class Navigation(
        val activeMainTab: MainTab,
    ) : GlobalDockContent

    data class Search(
        val entryId: Long,
        val activeMainTab: MainTab,
    ) : GlobalDockContent
}

@Immutable
internal data class GlobalChromePresentation(
    val dockContent: GlobalDockContent,
    val isDockPresented: Boolean,
)

@Immutable
internal data class GlobalChromeExecutionPolicy(
    val allowsMiniPlayerInput: Boolean,
    val allowsDockInput: Boolean,
    val allowsSearchFocus: Boolean,
    val authorityEpoch: Long,
)

internal fun resolveGlobalChromePresentation(
    navigationState: AppNavigationState,
): GlobalChromePresentation {
    val searchEntry = navigationState.backStack
        .takeWhile { entry ->
            entry.route != AppRoute.Player && entry.route != AppRoute.Queue
        }
        .lastOrNull { entry -> entry.route == AppRoute.Search }
    val dockContent = if (searchEntry != null) {
        GlobalDockContent.Search(
            entryId = searchEntry.entryId,
            activeMainTab = navigationState.activeMainTab,
        )
    } else {
        GlobalDockContent.Navigation(navigationState.activeMainTab)
    }
    return GlobalChromePresentation(
        dockContent = dockContent,
        isDockPresented = navigationState.canUseMainDestinationDock,
    )
}

internal fun resolveGlobalChromeExecutionPolicy(
    foregroundPresentation: ForegroundPresentation,
    contentPresentationSettled: Boolean,
    isDockPresented: Boolean,
    authorityEpoch: Long,
): GlobalChromeExecutionPolicy {
    val contentOwnsForeground =
        foregroundPresentation == ForegroundPresentation.Settled(ForegroundOwner.Content) &&
            contentPresentationSettled
    val dockHasAuthority = contentOwnsForeground && isDockPresented
    return GlobalChromeExecutionPolicy(
        allowsMiniPlayerInput = contentOwnsForeground,
        allowsDockInput = dockHasAuthority,
        allowsSearchFocus = dockHasAuthority,
        authorityEpoch = authorityEpoch,
    )
}

internal fun contentStackPresentsMainDock(
    scene: ContentSceneSnapshot,
): Boolean = scene.contentStack.none { entry ->
    entry.route == AppRoute.Settings
}

internal fun predictiveDockPresenceProgress(
    originPresented: Boolean,
    previewPresented: Boolean,
    progress: Float,
): Float? {
    if (originPresented == previewPresented) return null
    val coercedProgress = progress.coerceIn(0f, 1f)
    return if (previewPresented) coercedProgress else 1f - coercedProgress
}
