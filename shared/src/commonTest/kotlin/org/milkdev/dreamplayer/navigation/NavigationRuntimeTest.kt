package org.milkdev.dreamplayer.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavigationRuntimeTest {
    @Test
    fun planIsImmutableUntilCommitted() {
        val snapshot = AppNavigationSnapshot()

        val plan = assertNotNull(
            AppNavigator.plan(
                snapshot,
                NavigationIntent.Push(AppRoute.Settings),
            ),
        )

        assertEquals(AppRoute.Home, snapshot.state.currentDestination)
        assertEquals(0L, snapshot.revision)
        assertEquals(AppRoute.Settings, plan.targetState.currentDestination)
        assertEquals(NavigationOperation.Push, plan.operation)
    }

    @Test
    fun commitPublishesStateAndTransactionAtomically() {
        val snapshot = AppNavigationSnapshot()
        val plan = assertNotNull(
            AppNavigator.plan(snapshot, NavigationIntent.Push(AppRoute.Settings)),
        )

        val commit = assertNotNull(AppNavigator.commit(snapshot, plan))

        assertSame(plan.targetState, commit.snapshot.state)
        assertEquals(1L, commit.snapshot.revision)
        assertSame(commit.transaction, commit.snapshot.lastTransaction)
        assertEquals(NavigationOperation.Push, commit.transaction.operation)
        assertTrue(commit.transaction.affectsContent)
    }

    @Test
    fun staleRevisionRejectsPlanEvenWhenTopEntryDidNotChange() {
        val snapshot = AppNavigationSnapshot()
        val plan = assertNotNull(
            AppNavigator.plan(snapshot, NavigationIntent.Push(AppRoute.Settings)),
        )
        val revisedSnapshot = snapshot.copy(revision = snapshot.revision + 1L)

        assertNull(AppNavigator.commit(revisedSnapshot, plan))
    }

    @Test
    fun staleExpectedEntryCannotCreateBackPlan() {
        val snapshot = committedSnapshot(
            AppNavigationSnapshot(),
            NavigationIntent.Push(AppRoute.Settings),
        )

        assertNull(
            AppNavigator.plan(
                snapshot,
                NavigationIntent.Back(expectedTopEntryId = 999L),
            ),
        )
    }

    @Test
    fun droppingBackPlanLeavesSnapshotUnchanged() {
        val settings = committedSnapshot(
            AppNavigationSnapshot(),
            NavigationIntent.Push(AppRoute.Settings),
        )
        val backPlan = assertNotNull(
            AppNavigator.plan(settings, NavigationIntent.Back()),
        )

        assertEquals(AppRoute.Home, backPlan.targetState.currentDestination)
        assertEquals(AppRoute.Settings, settings.state.currentDestination)
        assertEquals(1L, settings.revision)
    }

    @Test
    fun operationsDistinguishMainSearchAndOverlayChanges() {
        val home = AppNavigationSnapshot()
        val libraryPlan = assertNotNull(
            AppNavigator.plan(home, NavigationIntent.SelectMainTab(MainTab.Library)),
        )
        assertEquals(NavigationOperation.MainSwitch, libraryPlan.operation)

        val library = assertNotNull(AppNavigator.commit(home, libraryPlan)).snapshot
        val searchPlan = assertNotNull(AppNavigator.plan(library, NavigationIntent.OpenSearch))
        assertEquals(NavigationOperation.SearchOpen, searchPlan.operation)

        val search = assertNotNull(AppNavigator.commit(library, searchPlan)).snapshot
        val closeSearchPlan = assertNotNull(AppNavigator.plan(search, NavigationIntent.Back()))
        assertEquals(NavigationOperation.SearchClose, closeSearchPlan.operation)

        val playerPlan = assertNotNull(
            AppNavigator.plan(library, NavigationIntent.Push(AppRoute.Player)),
        )
        assertEquals(NavigationOperation.OverlayOpen, playerPlan.operation)
        val player = assertNotNull(AppNavigator.commit(library, playerPlan)).snapshot
        val closePlayerPlan = assertNotNull(AppNavigator.plan(player, NavigationIntent.Back()))
        assertEquals(NavigationOperation.OverlayClose, closePlayerPlan.operation)
        assertFalse(
            assertNotNull(AppNavigator.commit(player, closePlayerPlan))
                .transaction
                .affectsContent,
        )
    }

    @Test
    fun backFromSecondaryTabPlansMainSwitchToHome() {
        val home = AppNavigationSnapshot()
        val library = committedSnapshot(
            home,
            NavigationIntent.SelectMainTab(MainTab.Library),
        )

        val backPlan = assertNotNull(AppNavigator.plan(library, NavigationIntent.Back()))

        assertEquals(MainTab.Home, backPlan.targetState.activeMainTab)
        assertEquals(listOf(AppRoute.Home), backPlan.targetState.backStack.map { it.route })
        assertEquals(NavigationOperation.MainSwitch, backPlan.operation)
        assertEquals(NavigationCause.Back, backPlan.cause)
    }

    private fun committedSnapshot(
        snapshot: AppNavigationSnapshot,
        intent: NavigationIntent,
    ): AppNavigationSnapshot {
        val plan = assertNotNull(AppNavigator.plan(snapshot, intent))
        return assertNotNull(AppNavigator.commit(snapshot, plan)).snapshot
    }
}
