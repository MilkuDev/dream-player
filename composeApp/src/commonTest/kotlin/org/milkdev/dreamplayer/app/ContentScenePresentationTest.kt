package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.navigation.AppNavigationSnapshot
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainDestination
import org.milkdev.dreamplayer.navigation.MainPage
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.navigation.planBack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentScenePresentationTest {
    @Test
    fun stableSceneUsesItsChromeAsTheOnlyPersistentLayer() {
        val home = scene(0L, AppRoute.Home)

        val layers = resolveContentChromeLayers(home, backSession = null)

        assertEquals(MainDestination.Home, layers.persistent?.activeMainDestination)
        assertNull(layers.destination)
    }

    @Test
    fun hiddenOriginRevealsDestinationChromeBelowPredictiveForeground() {
        val backSession = session(
            AppNavigationState()
                .selectMainPage(MainPage.Library)
                .push(AppRoute.Settings),
        )

        val layers = resolveContentChromeLayers(
            committedScene = backSession.origin,
            backSession = backSession,
        )

        assertNull(layers.persistent)
        assertEquals(backSession.preview.currentEntry.entryId, layers.destination?.entryId)
        assertEquals(MainDestination.Library, layers.destination?.chrome?.activeMainDestination)
    }

    @Test
    fun visibleOriginKeepsOnePersistentChromeUntilCommit() {
        val backSession = session(
            AppNavigationState()
                .selectMainPage(MainPage.Library)
                .push(AppRoute.LibraryCollection(
                    type = org.milkdev.dreamplayer.library.LibraryCollectionType.GENRE,
                    collectionId = 9L,
                )),
        )

        val layers = resolveContentChromeLayers(
            committedScene = backSession.origin,
            backSession = backSession,
        )

        assertEquals(MainDestination.Library, layers.persistent?.activeMainDestination)
        assertNull(layers.destination)
    }

    @Test
    fun hiddenOriginAndHiddenDestinationRenderNoChrome() {
        val backSession = session(
            AppNavigationState()
                .push(AppRoute.Settings)
                .push(AppRoute.AiDebugSettings),
        )

        val layers = resolveContentChromeLayers(
            committedScene = backSession.origin,
            backSession = backSession,
        )

        assertNull(layers.persistent)
        assertNull(layers.destination)
    }

    private fun session(
        state: AppNavigationState,
    ): ContentBackSession {
        val backPlan = checkNotNull(AppNavigationSnapshot(state = state).planBack())
        return ContentBackSession(
            sessionId = 1L,
            backPlan = backPlan,
            origin = contentSceneSnapshot(state.backStack),
            preview = contentSceneSnapshot(backPlan.targetState.backStack),
        )
    }

    private fun scene(
        entryId: Long,
        route: AppRoute,
    ): ContentSceneSnapshot {
        val entry = NavigationEntry(entryId, route)
        val stack = when (route) {
            AppRoute.Home -> listOf(entry)
            AppRoute.Library -> listOf(
                NavigationEntry(0L, AppRoute.Home),
                entry,
            )

            is AppRoute.Playlist,
            is AppRoute.LibraryCollection -> listOf(
                NavigationEntry(0L, AppRoute.Home),
                NavigationEntry(1L, AppRoute.Library),
                entry,
            )

            else -> listOf(entry)
        }
        return ContentSceneSnapshot(
            currentEntry = entry,
            contentStack = stack,
        )
    }
}
