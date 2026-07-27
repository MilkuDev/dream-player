package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.navigation.AppNavigationSnapshot
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.planBack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppBackGestureCoordinatorTest {
    @Test
    fun gestureRemainsBoundToSurfaceAndEntryThatAcceptedStart() {
        val coordinator = AppBackGestureCoordinator()
        val state = AppNavigationState()
            .push(AppRoute.Player)
            .push(AppRoute.Queue)
        val plan = checkNotNull(AppNavigationSnapshot(state = state).planBack())
        val gesture = AppBackGesture(
            surface = AppBackSurface.Queue,
            backPlan = plan,
        )

        coordinator.begin(gesture, acceptedBySurface = true)

        assertEquals(gesture, coordinator.routedGesture)
        assertEquals(gesture, coordinator.finish())
        assertNull(coordinator.routedGesture)
    }

    @Test
    fun rejectedStartIsConsumedWithoutDispatchingTerminalEvent() {
        val coordinator = AppBackGestureCoordinator()
        val state = AppNavigationState().push(AppRoute.Settings)
        val plan = checkNotNull(AppNavigationSnapshot(state = state).planBack())

        coordinator.begin(
            gesture = AppBackGesture(AppBackSurface.Content, backPlan = plan),
            acceptedBySurface = false,
        )

        assertNull(coordinator.routedGesture)
        assertNull(coordinator.finish())
    }
}
