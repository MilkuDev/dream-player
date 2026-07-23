package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.MainPage
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun pushAndPopRemainDirectionalWhenMainRouteParticipates() {
        val home = scene(0L, AppRoute.Home)
        val library = scene(1L, AppRoute.Library)
        val settings = scene(10L, AppRoute.Settings)
        val genre = scene(
            entryId = 11L,
            route = AppRoute.LibraryCollection(LibraryCollectionType.GENRE, 1L),
        )

        listOf(home, library).forEach { mainScene ->
            assertEquals(
                NavigationMotionKind.Forward,
                resolveNavigationMotion(
                    mainScene,
                    settings,
                    transaction(NavigationOperation.Push, mainScene, settings),
                ),
            )
            assertEquals(
                NavigationMotionKind.Backward,
                resolveNavigationMotion(
                    settings,
                    mainScene,
                    transaction(NavigationOperation.Pop, settings, mainScene),
                ),
            )
        }

        assertEquals(
            NavigationMotionKind.Forward,
            resolveNavigationMotion(
                library,
                genre,
                transaction(NavigationOperation.Push, library, genre),
            ),
        )
        assertEquals(
            NavigationMotionKind.Backward,
            resolveNavigationMotion(
                genre,
                library,
                transaction(NavigationOperation.Pop, genre, library),
            ),
        )
    }

    @Test
    fun mainSwitchAndSearchTransactionsUseFadeThrough() {
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

    @Test
    fun timeDrivenBackUsesStableDestinationLayerAcrossConsecutivePops() {
        val states = listOf(
            AppNavigationState()
                .selectMainPage(MainPage.Library)
                .push(AppRoute.Settings)
                .push(AppRoute.AiDebugSettings),
            AppNavigationState()
                .selectMainPage(MainPage.Library)
                .push(AppRoute.Settings),
            AppNavigationState()
                .selectMainPage(MainPage.Library),
            AppNavigationState(),
        )

        states.zipWithNext().forEach { (originState, previewState) ->
            val origin = contentSceneSnapshot(originState.backStack)
            val preview = contentSceneSnapshot(previewState.backStack)
            val transform = navigationContentTransform(
                initial = origin,
                target = preview,
                context = transaction(
                    operation = NavigationOperation.Pop,
                    initial = origin,
                    target = preview,
                ).toMotionContext(),
            )

            assertTrue(origin.contentLayer > preview.contentLayer)
            assertEquals(preview.contentLayer, transform.targetContentZIndex)
        }
    }

    @Test
    fun predictiveBackAndCancellationUseTheTargetScenesStableLayer() {
        val origin = contentSceneSnapshot(
            AppNavigationState()
                .selectMainPage(MainPage.Library)
                .push(AppRoute.Settings)
                .backStack,
        )
        val preview = contentSceneSnapshot(
            AppNavigationState()
                .selectMainPage(MainPage.Library)
                .backStack,
        )

        assertEquals(
            preview.contentLayer,
            predictiveBackContentTransform(
                swipeEdge = BackSwipeEdge.Left,
                target = preview,
            ).targetContentZIndex,
        )
        assertEquals(
            origin.contentLayer,
            predictiveBackCancelContentTransform(
                target = origin,
            ).targetContentZIndex,
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
