package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.NavigationEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ContentNavigationPresentationTest {
    @Test
    fun idleAcceptsAnimationAndBackStartButIgnoresGestureEvents() {
        val session = session()
        val idle = ContentNavigationPresentationState.Idle

        assertEquals(
            ContentNavigationPresentationState.Animating(session.preview),
            reduceContentNavigationPresentation(
                idle,
                ContentNavigationPresentationEvent.AnimationStarted(session.preview),
            ),
        )
        assertEquals(
            ContentNavigationPresentationState.Tracking(session),
            reduceContentNavigationPresentation(
                idle,
                ContentNavigationPresentationEvent.BackStarted(session),
            ),
        )
        assertSame(
            idle,
            reduceContentNavigationPresentation(
                idle,
                ContentNavigationPresentationEvent.BackCancelled(session.sessionId),
            ),
        )
        assertSame(
            idle,
            reduceContentNavigationPresentation(
                idle,
                ContentNavigationPresentationEvent.BackCommitted(session.sessionId, hadProgress = true),
            ),
        )
    }

    @Test
    fun animatingRetargetsAndSettlesOnlyAtItsTarget() {
        val session = session()
        val replacement = snapshot(3L, AppRoute.Search)
        val animating = ContentNavigationPresentationState.Animating(session.preview)

        assertEquals(
            ContentNavigationPresentationState.Animating(replacement),
            reduceContentNavigationPresentation(
                animating,
                ContentNavigationPresentationEvent.AnimationStarted(replacement),
            ),
        )
        assertSame(
            animating,
            reduceContentNavigationPresentation(
                animating,
                ContentNavigationPresentationEvent.TransitionSettled(session.origin),
            ),
        )
        assertEquals(
            ContentNavigationPresentationState.Idle,
            reduceContentNavigationPresentation(
                animating,
                ContentNavigationPresentationEvent.TransitionSettled(session.preview),
            ),
        )
    }

    @Test
    fun backStartInterruptsOrdinaryAnimationAndCancellation() {
        val session = session()
        val replacement = session().copy(sessionId = 11L)

        listOf(
            ContentNavigationPresentationState.Animating(session.origin),
            ContentNavigationPresentationState.Cancelling(session.copy(progress = 0.5f)),
        ).forEach { interruptibleState ->
            assertEquals(
                ContentNavigationPresentationState.Tracking(replacement),
                reduceContentNavigationPresentation(
                    interruptibleState,
                    ContentNavigationPresentationEvent.BackStarted(replacement),
                ),
            )
        }
    }

    @Test
    fun backStartCannotDuplicateCommitForSameEntryButCanFollowCommittedRouteChange() {
        val session = session(progress = 1f)
        val committing = ContentNavigationPresentationState.Committing(session)
        val sameEntry = session.copy(sessionId = 11L, progress = 0f)
        val nextEntry = ContentBackSession(
            sessionId = 12L,
            originTopEntryId = 4L,
            origin = snapshot(4L, AppRoute.Search),
            preview = snapshot(1L, AppRoute.Home),
        )

        assertSame(
            committing,
            reduceContentNavigationPresentation(
                committing,
                ContentNavigationPresentationEvent.BackStarted(sameEntry),
            ),
        )
        assertEquals(
            ContentNavigationPresentationState.Tracking(nextEntry),
            reduceContentNavigationPresentation(
                committing,
                ContentNavigationPresentationEvent.BackStarted(nextEntry),
            ),
        )
    }

    @Test
    fun trackingUpdatesOnlyMatchingSessionAndClampsProgress() {
        val session = session()
        val tracking = ContentNavigationPresentationState.Tracking(session)

        assertSame(
            tracking,
            reduceContentNavigationPresentation(
                tracking,
                ContentNavigationPresentationEvent.BackProgressed(
                    sessionId = session.sessionId + 1,
                    progress = 0.5f,
                    swipeEdge = BackSwipeEdge.Right,
                ),
            ),
        )

        val updated = reduceContentNavigationPresentation(
            tracking,
            ContentNavigationPresentationEvent.BackProgressed(
                sessionId = session.sessionId,
                progress = 1.5f,
                swipeEdge = BackSwipeEdge.Right,
            ),
        )
        val updatedTracking = assertIs<ContentNavigationPresentationState.Tracking>(updated)
        assertEquals(1f, updatedTracking.session.progress)
        assertEquals(BackSwipeEdge.Right, updatedTracking.session.swipeEdge)
    }

    @Test
    fun trackingBranchesToCancelAnimatedCommitOrImmediateCommit() {
        val session = session(progress = 0.6f)
        val tracking = ContentNavigationPresentationState.Tracking(session)

        assertEquals(
            ContentNavigationPresentationState.Cancelling(session),
            reduceContentNavigationPresentation(
                tracking,
                ContentNavigationPresentationEvent.BackCancelled(session.sessionId),
            ),
        )
        assertEquals(
            ContentNavigationPresentationState.Committing(session),
            reduceContentNavigationPresentation(
                tracking,
                ContentNavigationPresentationEvent.BackCommitted(
                    sessionId = session.sessionId,
                    hadProgress = true,
                ),
            ),
        )
        assertEquals(
            ContentNavigationPresentationState.Idle,
            reduceContentNavigationPresentation(
                tracking,
                ContentNavigationPresentationEvent.BackCommitted(
                    sessionId = session.sessionId,
                    hadProgress = false,
                ),
            ),
        )
    }

    @Test
    fun cancellingAcceptsOnlyItsProgressAndSettlesAtOrigin() {
        val session = session(progress = 0.8f)
        val cancelling = ContentNavigationPresentationState.Cancelling(session)

        val updated = reduceContentNavigationPresentation(
            cancelling,
            ContentNavigationPresentationEvent.CancelProgressed(
                sessionId = session.sessionId,
                progress = -1f,
            ),
        )
        val updatedCancelling = assertIs<ContentNavigationPresentationState.Cancelling>(updated)
        assertEquals(0f, updatedCancelling.session.progress)
        assertSame(
            cancelling,
            reduceContentNavigationPresentation(
                cancelling,
                ContentNavigationPresentationEvent.CancelProgressed(
                    sessionId = session.sessionId + 1,
                    progress = 0f,
                ),
            ),
        )
        assertEquals(
            ContentNavigationPresentationState.Idle,
            reduceContentNavigationPresentation(
                cancelling,
                ContentNavigationPresentationEvent.TransitionSettled(session.origin),
            ),
        )
    }

    @Test
    fun committingRequestsOneGuardedPopAndHandlesItsResult() {
        val session = session(progress = 1f)
        val committing = ContentNavigationPresentationState.Committing(session)

        val ready = reduceContentNavigationPresentation(
            committing,
            ContentNavigationPresentationEvent.TransitionSettled(session.preview),
        )
        val popRequested = assertIs<ContentNavigationPresentationState.Committing>(ready)
        assertEquals(true, popRequested.popRequested)
        assertEquals(popRequested, reduceContentNavigationPresentation(
            popRequested,
            ContentNavigationPresentationEvent.TransitionSettled(session.preview),
        ))
        assertEquals(
            ContentNavigationPresentationState.Idle,
            reduceContentNavigationPresentation(
                popRequested,
                ContentNavigationPresentationEvent.CommitPopCompleted(
                    sessionId = session.sessionId,
                    didPop = true,
                    recoveryTarget = session.origin,
                ),
            ),
        )
        assertEquals(
            ContentNavigationPresentationState.Animating(session.origin),
            reduceContentNavigationPresentation(
                popRequested,
                ContentNavigationPresentationEvent.CommitPopCompleted(
                    sessionId = session.sessionId,
                    didPop = false,
                    recoveryTarget = session.origin,
                ),
            ),
        )
    }

    @Test
    fun routeInvalidationRecoversEveryActiveGesturePhase() {
        val session = session(progress = 0.5f)
        val recovery = snapshot(4L, AppRoute.Search)
        val activeStates = listOf(
            ContentNavigationPresentationState.Tracking(session),
            ContentNavigationPresentationState.Cancelling(session),
            ContentNavigationPresentationState.Committing(session),
        )

        activeStates.forEach { activeState ->
            assertEquals(
                ContentNavigationPresentationState.Animating(recovery),
                reduceContentNavigationPresentation(
                    activeState,
                    ContentNavigationPresentationEvent.RouteInvalidated(recovery),
                ),
            )
        }
    }

    @Test
    fun staleGestureCommitIsIgnoredAfterControllerInvalidatesItsOrigin() {
        val origin = session()
        val recovery = snapshot(4L, AppRoute.Search)
        val controller = ContentNavigationPresentationController(origin.origin)
        controller.startPredictiveBack(
            originTopEntryId = origin.originTopEntryId,
            origin = origin.origin,
            preview = origin.preview,
        )

        val invalidated = controller.invalidateIfOriginChanged(
            currentTopEntryId = 4L,
            committedSnapshot = recovery,
        )

        assertEquals(origin.originTopEntryId, invalidated?.originTopEntryId)
        assertIs<ContentNavigationPresentationState.Animating>(controller.state)
        assertEquals(
            ContentBackCommitResult.Ignored,
            controller.commitPredictiveBack(hadProgress = true),
        )
    }

    private fun session(progress: Float = 0f): ContentBackSession {
        return ContentBackSession(
            sessionId = 10L,
            originTopEntryId = 2L,
            origin = ContentSceneSnapshot(
                currentEntry = NavigationEntry(2L, AppRoute.Settings),
                contentStack = listOf(
                    NavigationEntry(1L, AppRoute.Home),
                    NavigationEntry(2L, AppRoute.Settings),
                ),
            ),
            preview = snapshot(1L, AppRoute.Home),
            progress = progress,
        )
    }

    private fun snapshot(entryId: Long, route: AppRoute): ContentSceneSnapshot {
        val entry = NavigationEntry(entryId, route)
        return ContentSceneSnapshot(
            currentEntry = entry,
            contentStack = listOf(entry),
        )
    }
}
