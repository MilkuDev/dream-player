package org.milkdev.dreamplayer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppNavigationSnapshot
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.planBack

class RetainedContentStackStateTest {
    @Test
    fun navigationRevisionMustBeAcceptedBeforeAuthorityIsSettled() {
        val state = RetainedContentStackState(initialNavigationRevision = 4L)
        val initialEpoch = state.authorityEpoch

        assertTrue(state.isSettledAt(4L))
        assertFalse(state.isSettledAt(5L))

        state.settleNavigationRevision(5L)

        assertTrue(state.isSettledAt(5L))
        assertTrue(state.authorityEpoch > initialEpoch)
    }

    @Test
    fun predictiveCancellationKeepsNavigationUncommittedAndRestoresAuthority() {
        val fixture = settingsFixture()
        val state = RetainedContentStackState()
        val initialEpoch = state.authorityEpoch
        val session = assertNotNull(
            state.startPredictiveBack(
                backPlan = fixture.backPlan,
                origin = fixture.origin,
                preview = fixture.preview,
            ),
        )

        state.progressPredictiveBack(
            event = PlatformBackEvent(
                progress = 0.42f,
                swipeEdge = BackSwipeEdge.Left,
                frameTimeMillis = 20L,
            ),
            currentTopEntryId = session.originTopEntryId,
            currentRevision = session.originRevision,
        )
        assertEquals(0.42f, state.visualBackProgress)
        assertFalse(state.canRequestBack())

        state.cancelPredictiveBack()
        assertIs<ContentNavigationPresentationState.Cancelling>(
            state.presentationState,
        )
        state.updateSettlingProgress(session.sessionId, 0f)
        state.finishCancellation(session.sessionId)

        assertIs<ContentNavigationPresentationState.Idle>(state.presentationState)
        assertEquals(0f, state.visualBackProgress)
        assertTrue(state.authorityEpoch > initialEpoch)
        assertTrue(state.canRequestBack())
    }

    @Test
    fun commitRequiresMatchingNavigationAcknowledgement() {
        val fixture = settingsFixture()
        val state = RetainedContentStackState()
        val session = assertNotNull(
            state.startPredictiveBack(
                backPlan = fixture.backPlan,
                origin = fixture.origin,
                preview = fixture.preview,
            ),
        )

        assertNotNull(state.commitPredictiveBack(hadProgress = true))
        state.updateSettlingProgress(session.sessionId, 1f)
        state.markCommitAwaitingNavigation(session.sessionId)

        assertTrue(state.isAwaitingCommittedNavigation(session.sessionId))
        state.finishCommittedNavigation(session.sessionId + 1L)
        assertIs<ContentNavigationPresentationState.Committing>(
            state.presentationState,
        )

        state.finishCommittedNavigation(session.sessionId)
        assertIs<ContentNavigationPresentationState.Idle>(state.presentationState)
        assertNull(state.backSession)
        assertTrue(state.canRequestBack())
    }

    @Test
    fun deferredBackIsCoalescedAndConsumedOnlyWhenReady() {
        val state = RetainedContentStackState()

        state.beginDeferredBackGesture()
        state.commitDeferredBackGesture()
        state.beginDeferredBackGesture()
        state.commitDeferredBackGesture()

        assertTrue(state.hasQueuedTimeDrivenBack)
        assertFalse(state.takeQueuedTimeDrivenBackIf(ready = false))
        assertTrue(state.hasQueuedTimeDrivenBack)
        assertTrue(state.takeQueuedTimeDrivenBackIf(ready = true))
        assertFalse(state.hasQueuedTimeDrivenBack)
        assertFalse(state.takeQueuedTimeDrivenBackIf(ready = true))

        state.beginDeferredBackGesture()
        state.cancelDeferredBackGesture()
        state.commitDeferredBackGesture()
        assertFalse(state.hasQueuedTimeDrivenBack)
    }

    private fun settingsFixture(): BackFixture {
        val pushedState = AppNavigationState().push(AppRoute.Settings)
        val pushed = AppNavigationSnapshot(
            state = pushedState,
            revision = 1L,
        )
        val backPlan = assertNotNull(pushed.planBack())
        return BackFixture(
            origin = contentSceneSnapshot(pushed.state.backStack),
            preview = contentSceneSnapshot(backPlan.targetState.backStack),
            backPlan = backPlan,
        )
    }

    private data class BackFixture(
        val origin: ContentSceneSnapshot,
        val preview: ContentSceneSnapshot,
        val backPlan: org.milkdev.dreamplayer.navigation.NavigationPlan,
    )
}
