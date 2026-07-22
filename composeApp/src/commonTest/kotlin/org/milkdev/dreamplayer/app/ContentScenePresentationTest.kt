package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainDestination
import org.milkdev.dreamplayer.navigation.NavigationEntry
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
        val settings = scene(2L, AppRoute.Settings)
        val library = scene(1L, AppRoute.Library)

        val layers = resolveContentChromeLayers(
            committedScene = settings,
            backSession = session(settings, library),
        )

        assertNull(layers.persistent)
        assertEquals(library.currentEntry.entryId, layers.destination?.entryId)
        assertEquals(MainDestination.Library, layers.destination?.chrome?.activeMainDestination)
    }

    @Test
    fun visibleOriginKeepsOnePersistentChromeUntilCommit() {
        val details = scene(3L, AppRoute.LibraryCollection(
            type = org.milkdev.dreamplayer.library.LibraryCollectionType.GENRE,
            collectionId = 9L,
        ))
        val library = scene(1L, AppRoute.Library)

        val layers = resolveContentChromeLayers(
            committedScene = details,
            backSession = session(details, library),
        )

        assertEquals(MainDestination.Library, layers.persistent?.activeMainDestination)
        assertNull(layers.destination)
    }

    @Test
    fun hiddenOriginAndHiddenDestinationRenderNoChrome() {
        val debug = scene(3L, AppRoute.AiDebugSettings)
        val settings = scene(2L, AppRoute.Settings)

        val layers = resolveContentChromeLayers(
            committedScene = debug,
            backSession = session(debug, settings),
        )

        assertNull(layers.persistent)
        assertNull(layers.destination)
    }

    private fun session(
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
    ) = ContentBackSession(
        sessionId = 1L,
        originTopEntryId = origin.currentEntry.entryId,
        origin = origin,
        preview = preview,
    )

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
