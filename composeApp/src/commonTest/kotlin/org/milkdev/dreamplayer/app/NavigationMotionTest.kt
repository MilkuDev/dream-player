package org.milkdev.dreamplayer.app

import androidx.compose.ui.unit.dp
import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.MainTab
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
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
    fun mainSwitchUsesCarouselDirectionWhileSearchUsesFadeThrough() {
        val home = scene(0L, AppRoute.Home)
        val library = scene(1L, AppRoute.Library)
        val search = scene(2L, AppRoute.Search)

        assertEquals(
            NavigationMotionKind.MainForward,
            resolveNavigationMotion(
                home,
                library,
                transaction(NavigationOperation.MainSwitch, home, library),
            ),
        )
        assertEquals(
            NavigationMotionKind.MainBackward,
            resolveNavigationMotion(
                library,
                home,
                transaction(NavigationOperation.MainSwitch, library, home),
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
                .selectMainTab(MainTab.Library)
                .push(AppRoute.Settings)
                .push(AppRoute.AiDebugSettings),
            AppNavigationState()
                .selectMainTab(MainTab.Library)
                .push(AppRoute.Settings),
            AppNavigationState()
                .selectMainTab(MainTab.Library),
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
                .selectMainTab(MainTab.Library)
                .push(AppRoute.Settings)
                .backStack,
        )
        val preview = contentSceneSnapshot(
            AppNavigationState()
                .selectMainTab(MainTab.Library)
                .backStack,
        )

        assertEquals(
            preview.contentLayer,
            predictiveStackContentTransform(
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

    @Test
    fun predictiveStackUsesEightPercentShiftAndScalesToPointEight() {
        assertEquals(
            PredictiveStackVisualTransform(scale = 1f, offsetX = 0f, alpha = 1f),
            predictiveStackVisualTransform(
                progress = 0f,
                swipeEdge = BackSwipeEdge.Left,
                fullWidthPx = 1_000f,
            ),
        )
        assertEquals(
            PredictiveStackVisualTransform(scale = 0.9f, offsetX = 40f, alpha = 1f),
            predictiveStackVisualTransform(
                progress = 0.5f,
                swipeEdge = BackSwipeEdge.Left,
                fullWidthPx = 1_000f,
            ),
        )
        assertEquals(
            PredictiveStackVisualTransform(scale = 0.9f, offsetX = -40f, alpha = 1f),
            predictiveStackVisualTransform(
                progress = 0.5f,
                swipeEdge = BackSwipeEdge.Right,
                fullWidthPx = 1_000f,
            ),
        )
        assertEquals(
            PredictiveStackVisualTransform(
                scale = PredictiveStackTargetScale,
                offsetX = 80f,
                alpha = 0f,
            ),
            predictiveStackVisualTransform(
                progress = 2f,
                swipeEdge = BackSwipeEdge.None,
                fullWidthPx = 1_000f,
            ),
        )
    }

    @Test
    fun predictiveStackOriginFadesOnlyNearTheTerminalFrame() {
        assertEquals(1f, predictiveStackOriginAlpha(progress = 0.8f))
        assertEquals(
            0.5f,
            predictiveStackOriginAlpha(progress = 0.91f),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(0f, predictiveStackOriginAlpha(progress = 1f))
    }

    @Test
    fun predictiveStackRoundsTheOriginAsItShrinks() {
        assertEquals(0.dp, predictiveStackCornerRadius(progress = 0f))
        assertEquals(14.dp, predictiveStackCornerRadius(progress = 0.5f))
        assertEquals(28.dp, predictiveStackCornerRadius(progress = 1f))
    }

    @Test
    fun mainTabPredictiveBackUsesFullWidthCarouselFromEitherEdge() {
        val origin = contentSceneSnapshot(
            AppNavigationState()
                .selectMainTab(MainTab.Library)
                .backStack,
        )
        val preview = contentSceneSnapshot(AppNavigationState().backStack)

        assertEquals(
            PredictiveBackMotionStyle.MainTabCarousel,
            resolvePredictiveBackMotionStyle(
                origin = origin,
                preview = preview,
                operation = NavigationOperation.MainSwitch,
            ),
        )
        assertEquals(
            PredictiveCarouselOffsets(originX = 500, previewX = -500),
            predictiveCarouselOffsets(
                progress = 0.5f,
                swipeEdge = BackSwipeEdge.Left,
                fullWidth = 1_000,
                origin = origin,
                preview = preview,
            ),
        )
        assertEquals(
            PredictiveCarouselOffsets(originX = -500, previewX = 500),
            predictiveCarouselOffsets(
                progress = 0.5f,
                swipeEdge = BackSwipeEdge.Right,
                fullWidth = 1_000,
                origin = origin,
                preview = preview,
            ),
        )
        assertEquals(
            PredictiveCarouselOffsets(originX = 0, previewX = -1_000),
            predictiveCarouselOffsets(
                progress = 0f,
                swipeEdge = BackSwipeEdge.None,
                fullWidth = 1_000,
                origin = origin,
                preview = preview,
            ),
        )
        assertEquals(
            PredictiveCarouselOffsets(originX = 1_000, previewX = 0),
            predictiveCarouselOffsets(
                progress = 1f,
                swipeEdge = BackSwipeEdge.None,
                fullWidth = 1_000,
                origin = origin,
                preview = preview,
            ),
        )
    }

    @Test
    fun rootTabsShareCarouselIdentityButStackDestinationsRemainDistinct() {
        val home = scene(0L, AppRoute.Home)
        val library = scene(1L, AppRoute.Library)
        val settings = scene(10L, AppRoute.Settings)
        val homeFrame = ContentTransitionFrame(home)
        val libraryFrame = ContentTransitionFrame(library)
        val settingsFrame = ContentTransitionFrame(settings)

        assertSame(
            mainTabCarouselContentKey(homeFrame),
            mainTabCarouselContentKey(libraryFrame),
        )
        assertNotEquals(
            mainTabCarouselContentKey(homeFrame),
            mainTabCarouselContentKey(settingsFrame),
        )
        assertTrue(
            isDirectRootTabSwitch(
                transaction(NavigationOperation.MainSwitch, home, library),
            ),
        )
        assertTrue(
            !isDirectRootTabSwitch(
                transaction(NavigationOperation.MainSwitch, settings, home),
            ),
        )
    }

    @Test
    fun predictiveCarouselSettleKeepsAVisibleMinimumDuration() {
        assertEquals(300, mainTabCarouselSettleDurationMillis(progress = 0f))
        assertEquals(260, mainTabCarouselSettleDurationMillis(progress = 0.5f))
        assertEquals(220, mainTabCarouselSettleDurationMillis(progress = 1f))
        assertEquals(300, mainTabCarouselSettleDurationMillis(progress = -1f))
        assertEquals(220, mainTabCarouselSettleDurationMillis(progress = 2f))
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
