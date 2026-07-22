package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationMotionTest {
    @Test
    fun committedOperationDeterminesDirectionWithoutStackHeuristics() {
        val genre = scene(
            entryId = 10L,
            route = AppRoute.LibraryCollection(LibraryCollectionType.GENRE, 1L),
        )
        val album = scene(
            entryId = 11L,
            route = AppRoute.LibraryCollection(LibraryCollectionType.ALBUM, 2L),
        )

        assertEquals(
            NavigationMotionKind.Forward,
            resolveNavigationMotion(
                genre,
                album,
                transaction(NavigationOperation.Push, genre, album),
            ),
        )
        assertEquals(
            NavigationMotionKind.Backward,
            resolveNavigationMotion(
                album,
                genre,
                transaction(NavigationOperation.Pop, album, genre),
            ),
        )
    }

    @Test
    fun mainAndSearchTransactionsUseFadeThrough() {
        val home = scene(0L, AppRoute.Home)
        val library = scene(1L, AppRoute.Library)
        val search = scene(2L, AppRoute.Search)

        assertEquals(
            NavigationMotionKind.FadeThrough,
            resolveNavigationMotion(
                home,
                library,
                transaction(NavigationOperation.MainSwitch, home, library),
            ),
        )
        assertEquals(
            NavigationMotionKind.FadeThrough,
            resolveNavigationMotion(
                library,
                search,
                transaction(NavigationOperation.SearchOpen, library, search),
            ),
        )
    }

    @Test
    fun missingOrMismatchedTransactionFallsBackWithoutInventingDirection() {
        val genre = scene(
            entryId = 10L,
            route = AppRoute.LibraryCollection(LibraryCollectionType.GENRE, 1L),
        )
        val album = scene(
            entryId = 11L,
            route = AppRoute.LibraryCollection(LibraryCollectionType.ALBUM, 2L),
        )
        val other = scene(12L, AppRoute.Settings)

        assertEquals(
            NavigationMotionKind.FadeThrough,
            resolveNavigationMotion(genre, album, transaction = null),
        )
        assertEquals(
            NavigationMotionKind.FadeThrough,
            resolveNavigationMotion(
                genre,
                album,
                transaction(NavigationOperation.Push, other, album),
            ),
        )
    }

    private fun transaction(
        operation: NavigationOperation,
        initial: ContentSceneSnapshot,
        target: ContentSceneSnapshot,
    ): NavigationTransaction {
        return NavigationTransaction(
            id = 1L,
            operation = operation,
            cause = NavigationCause.Direct,
            fromEntry = initial.currentEntry,
            toEntry = target.currentEntry,
            fromContentEntry = initial.currentEntry,
            toContentEntry = target.currentEntry,
        )
    }

    private fun scene(
        entryId: Long,
        route: AppRoute,
    ): ContentSceneSnapshot {
        val entry = NavigationEntry(entryId, route)
        return ContentSceneSnapshot(
            currentEntry = entry,
            contentStack = listOf(entry),
        )
    }
}
