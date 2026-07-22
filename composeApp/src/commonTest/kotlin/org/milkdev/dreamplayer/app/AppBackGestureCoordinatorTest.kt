package org.milkdev.dreamplayer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppBackGestureCoordinatorTest {
    @Test
    fun gestureRemainsBoundToSurfaceAndEntryThatAcceptedStart() {
        val coordinator = AppBackGestureCoordinator()
        val gesture = AppBackGesture(
            surface = AppBackSurface.Queue,
            expectedTopEntryId = 42L,
        )

        coordinator.begin(gesture, acceptedBySurface = true)

        assertEquals(gesture, coordinator.routedGesture)
        assertEquals(gesture, coordinator.finish())
        assertNull(coordinator.routedGesture)
    }

    @Test
    fun rejectedStartIsConsumedWithoutDispatchingTerminalEvent() {
        val coordinator = AppBackGestureCoordinator()

        coordinator.begin(
            gesture = AppBackGesture(AppBackSurface.Content, expectedTopEntryId = 7L),
            acceptedBySurface = false,
        )

        assertNull(coordinator.routedGesture)
        assertNull(coordinator.finish())
    }
}
