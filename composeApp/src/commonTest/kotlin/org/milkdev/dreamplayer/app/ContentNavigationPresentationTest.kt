package org.milkdev.dreamplayer.app

import androidx.compose.animation.core.ExperimentalDeferredTransitionApi
import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.navigation.AppNavigationSnapshot
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainPage
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import org.milkdev.dreamplayer.navigation.planBack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalDeferredTransitionApi::class)
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
    fun backStartCannotDuplicateCommitButCanFollowEntryOrRevisionChange() {
        val session = session(progress = 1f)
        val committing = ContentNavigationPresentationState.Committing(session)
        val sameEntry = session.copy(sessionId = 11L, progress = 0f)
        val nextEntry = session(
            sessionId = 12L,
            route = AppRoute.Search,
        )
        val revisedSameEntry = session(
            sessionId = 13L,
            route = AppRoute.Settings,
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
        assertEquals(
            ContentNavigationPresentationState.Tracking(revisedSameEntry),
            reduceContentNavigationPresentation(
                committing,
                ContentNavigationPresentationEvent.BackStarted(revisedSameEntry),
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
    fun trackingBranchesToCancelPredictiveOrTimeDrivenCommit() {
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
        val timeDrivenSession = session.copy(mode = ContentBackMode.TimeDriven)
        assertEquals(
            ContentNavigationPresentationState.Committing(timeDrivenSession),
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
    fun timeDrivenBackDoesNotEnterDeferredPredictivePhase() {
        val navigationState = AppNavigationState()
            .push(AppRoute.Settings)
            .push(AppRoute.AiDebugSettings)
        val navigationSnapshot = AppNavigationSnapshot(
            state = navigationState,
            revision = 20L,
        )
        val backPlan = checkNotNull(navigationSnapshot.planBack())
        val origin = contentSceneSnapshot(navigationState.backStack)
        val preview = contentSceneSnapshot(backPlan.targetState.backStack)
        val controller = ContentNavigationPresentationController(origin)

        val session = controller.startTimeDrivenBack(
            backPlan = backPlan,
            origin = origin,
            preview = preview,
        )

        assertNotNull(session)
        assertEquals(ContentBackMode.TimeDriven, session.mode)
        assertEquals(ContentBackSource.Ui, session.source)
        assertIs<ContentNavigationPresentationState.Committing>(controller.state)
        assertEquals(null, controller.transitionState.pendingTargetState)
        assertEquals(preview, controller.transitionState.targetState.scene)
        assertEquals(
            NavigationOperation.Pop,
            controller.transitionState.targetState.motionContext?.operation,
        )
    }

    @Test
    fun consecutiveTimeDrivenBacksReachHomeAndLibraryWithDistinctMotionRuns() {
        val roots = listOf(
            AppNavigationState() to AppRoute.Home,
            AppNavigationState().selectMainPage(MainPage.Library) to AppRoute.Library,
        )

        roots.forEach { (rootState, expectedRootRoute) ->
            var navigationSnapshot = AppNavigationSnapshot(
                state = rootState
                    .push(AppRoute.Settings)
                    .push(AppRoute.AiDebugSettings),
                revision = 30L,
            )
            val controller = ContentNavigationPresentationController(
                contentSceneSnapshot(navigationSnapshot.state.backStack),
            )
            val transitionIds = mutableListOf<Long>()

            repeat(2) {
                val origin = contentSceneSnapshot(navigationSnapshot.state.backStack)
                val backPlan = checkNotNull(navigationSnapshot.planBack())
                val preview = contentSceneSnapshot(backPlan.targetState.backStack)
                val session = checkNotNull(
                    controller.startTimeDrivenBack(
                        backPlan = backPlan,
                        origin = origin,
                        preview = preview,
                    ),
                )

                assertEquals(ContentBackMode.TimeDriven, session.mode)
                assertEquals(ContentBackSource.Ui, session.source)
                assertTrue(session.origin.contentLayer > session.preview.contentLayer)
                assertEquals(null, controller.transitionState.pendingTargetState)
                assertEquals(preview, controller.transitionState.targetState.scene)
                transitionIds += checkNotNull(
                    controller.transitionState.targetState.motionContext,
                ).transitionId
                val committedTransaction = NavigationTransaction(
                    id = navigationSnapshot.revision + 1L,
                    operation = backPlan.operation,
                    cause = NavigationCause.Back,
                    fromEntry = navigationSnapshot.state.currentEntry,
                    toEntry = backPlan.targetState.currentEntry,
                    fromContentEntry = navigationSnapshot.state.currentContentEntry,
                    toContentEntry = backPlan.targetState.currentContentEntry,
                )
                assertEquals(
                    controller.transitionState.targetState.motionContext,
                    committedTransaction.toMotionContext(),
                )

                val settledFrame = controller.transitionState.targetState
                val completion = assertIs<ContentTransitionCompletion.CommitReady>(
                    controller.onTransitionObserved(
                        currentState = settledFrame,
                        targetState = settledFrame,
                        pendingTargetState = null,
                        isRunning = false,
                    ),
                )
                assertSame(backPlan, completion.session.backPlan)

                controller.onCommitPopCompleted(
                    sessionId = completion.session.sessionId,
                    didPop = true,
                    recoveryTarget = preview,
                )
                navigationSnapshot = AppNavigationSnapshot(
                    state = backPlan.targetState,
                    revision = navigationSnapshot.revision + 1L,
                )
            }

            assertEquals(expectedRootRoute, navigationSnapshot.state.currentDestination)
            assertEquals(2, transitionIds.distinct().size)
            assertIs<ContentNavigationPresentationState.Idle>(controller.state)
        }
    }

    @Test
    fun consecutivePredictiveBacksReachHomeAndLibraryWithLiveProgress() {
        val roots = listOf(
            AppNavigationState() to AppRoute.Home,
            AppNavigationState().selectMainPage(MainPage.Library) to AppRoute.Library,
        )

        roots.forEach { (rootState, expectedRootRoute) ->
            var navigationSnapshot = AppNavigationSnapshot(
                state = rootState
                    .push(AppRoute.Settings)
                    .push(AppRoute.AiDebugSettings),
                revision = 40L,
            )
            val controller = ContentNavigationPresentationController(
                contentSceneSnapshot(navigationSnapshot.state.backStack),
            )
            val transitionIds = mutableListOf<Long>()

            repeat(2) {
                val origin = contentSceneSnapshot(navigationSnapshot.state.backStack)
                val backPlan = checkNotNull(navigationSnapshot.planBack())
                val preview = contentSceneSnapshot(backPlan.targetState.backStack)
                val started = checkNotNull(
                    controller.startPredictiveBack(
                        backPlan = backPlan,
                        origin = origin,
                        preview = preview,
                    ),
                )

                assertEquals(ContentBackMode.Predictive, started.mode)
                assertEquals(ContentBackSource.Platform, started.source)
                assertTrue(started.origin.contentLayer > started.preview.contentLayer)
                assertEquals(preview, controller.transitionState.pendingTargetState?.scene)

                controller.progressPredictiveBack(
                    event = PlatformBackEvent(
                        progress = 0.5f,
                        swipeEdge = BackSwipeEdge.Left,
                    ),
                    currentTopEntryId = navigationSnapshot.state.currentEntry.entryId,
                    currentRevision = navigationSnapshot.revision,
                )
                val commit = assertIs<ContentBackCommitResult.Animated>(
                    controller.commitPredictiveBack(hadProgress = true),
                )
                assertEquals(ContentBackMode.Predictive, commit.session.mode)
                assertEquals(1, commit.session.progressEventCount)
                assertEquals(0.5f, commit.session.maxProgress)
                assertEquals(null, controller.transitionState.pendingTargetState)
                assertEquals(preview, controller.transitionState.targetState.scene)
                transitionIds += checkNotNull(
                    controller.transitionState.targetState.motionContext,
                ).transitionId

                val settledFrame = controller.transitionState.targetState
                val completion = assertIs<ContentTransitionCompletion.CommitReady>(
                    controller.onTransitionObserved(
                        currentState = settledFrame,
                        targetState = settledFrame,
                        pendingTargetState = null,
                        isRunning = false,
                    ),
                )
                controller.onCommitPopCompleted(
                    sessionId = completion.session.sessionId,
                    didPop = true,
                    recoveryTarget = preview,
                )
                navigationSnapshot = AppNavigationSnapshot(
                    state = backPlan.targetState,
                    revision = navigationSnapshot.revision + 1L,
                )
            }

            assertEquals(expectedRootRoute, navigationSnapshot.state.currentDestination)
            assertEquals(2, transitionIds.distinct().size)
            assertIs<ContentNavigationPresentationState.Idle>(controller.state)
        }
    }

    @Test
    fun restoredEntriesKeepTheirStackLayersAcrossFullPresentationHistory() {
        data class HistoryCase(
            val mainPage: MainPage?,
            val pushedRoutes: List<AppRoute>,
        )

        val cases = listOf(
            HistoryCase(
                mainPage = null,
                pushedRoutes = listOf(AppRoute.Settings, AppRoute.AiDebugSettings),
            ),
            HistoryCase(
                mainPage = MainPage.Library,
                pushedRoutes = listOf(AppRoute.Settings, AppRoute.AiDebugSettings),
            ),
            HistoryCase(
                mainPage = MainPage.Library,
                pushedRoutes = listOf(
                    AppRoute.LibraryCollection(
                        type = LibraryCollectionType.GENRE,
                        collectionId = 7L,
                    ),
                    AppRoute.LibraryCollection(
                        type = LibraryCollectionType.ALBUM,
                        collectionId = 11L,
                    ),
                ),
            ),
            HistoryCase(
                mainPage = MainPage.Library,
                pushedRoutes = listOf(
                    AppRoute.LibraryCollection(
                        type = LibraryCollectionType.GENRE,
                        collectionId = 13L,
                    ),
                ),
            ),
        )

        cases.forEach { historyCase ->
            var navigationSnapshot = AppNavigationSnapshot()
            val controller = ContentNavigationPresentationController(
                contentSceneSnapshot(navigationSnapshot.state.backStack),
            )

            fun publishNavigation(
                targetState: AppNavigationState,
                operation: NavigationOperation,
                cause: NavigationCause,
            ): NavigationTransaction {
                val previousSnapshot = navigationSnapshot
                val transaction = NavigationTransaction(
                    id = previousSnapshot.revision + 1L,
                    operation = operation,
                    cause = cause,
                    fromEntry = previousSnapshot.state.currentEntry,
                    toEntry = targetState.currentEntry,
                    fromContentEntry = previousSnapshot.state.currentContentEntry,
                    toContentEntry = targetState.currentContentEntry,
                )
                navigationSnapshot = AppNavigationSnapshot(
                    state = targetState,
                    revision = transaction.id,
                    lastTransaction = transaction,
                )
                return transaction
            }

            fun commitAndSettle(
                targetState: AppNavigationState,
                operation: NavigationOperation,
            ): ContentTransitionFrame {
                val transaction = publishNavigation(
                    targetState = targetState,
                    operation = operation,
                    cause = NavigationCause.Direct,
                )
                controller.onCommittedSnapshotChanged(
                    snapshot = contentSceneSnapshot(navigationSnapshot.state.backStack),
                    transaction = transaction,
                )
                val targetFrame = controller.transitionState.targetState
                controller.onTransitionObserved(
                    currentState = targetFrame,
                    targetState = targetFrame,
                    pendingTargetState = null,
                    isRunning = false,
                )
                assertIs<ContentNavigationPresentationState.Idle>(controller.state)
                return targetFrame
            }

            fun predictiveBackAndSettle(): ContentBackSession {
                val origin = contentSceneSnapshot(navigationSnapshot.state.backStack)
                val backPlan = checkNotNull(navigationSnapshot.planBack())
                val preview = contentSceneSnapshot(backPlan.targetState.backStack)
                val session = checkNotNull(
                    controller.startPredictiveBack(
                        backPlan = backPlan,
                        origin = origin,
                        preview = preview,
                    ),
                )
                controller.progressPredictiveBack(
                    event = PlatformBackEvent(
                        progress = 0.5f,
                        swipeEdge = BackSwipeEdge.Left,
                    ),
                    currentTopEntryId = navigationSnapshot.state.currentEntry.entryId,
                    currentRevision = navigationSnapshot.revision,
                )
                assertIs<ContentBackCommitResult.Animated>(
                    controller.commitPredictiveBack(hadProgress = true),
                )
                val targetFrame = controller.transitionState.targetState
                val completion = assertIs<ContentTransitionCompletion.CommitReady>(
                    controller.onTransitionObserved(
                        currentState = targetFrame,
                        targetState = targetFrame,
                        pendingTargetState = null,
                        isRunning = false,
                    ),
                )
                publishNavigation(
                    targetState = backPlan.targetState,
                    operation = backPlan.operation,
                    cause = backPlan.cause,
                )
                controller.onCommitPopCompleted(
                    sessionId = completion.session.sessionId,
                    didPop = true,
                    recoveryTarget = preview,
                )
                assertIs<ContentNavigationPresentationState.Idle>(controller.state)
                return session
            }

            val forwardFrames = mutableListOf(controller.transitionState.currentState)
            historyCase.mainPage?.let { page ->
                forwardFrames += commitAndSettle(
                    targetState = navigationSnapshot.state.selectMainPage(page),
                    operation = NavigationOperation.MainSwitch,
                )
            }
            historyCase.pushedRoutes.forEach { route ->
                forwardFrames += commitAndSettle(
                    targetState = navigationSnapshot.state.push(route),
                    operation = NavigationOperation.Push,
                )
            }
            val backSessions = List(2) { predictiveBackAndSettle() }
            backSessions.forEachIndexed { index, session ->
                val restoredFrame = forwardFrames[forwardFrames.lastIndex - index - 1]
                assertEquals(
                    restoredFrame.scene.currentEntry.entryId,
                    session.previewFrame.scene.currentEntry.entryId,
                )
                assertEquals(
                    restoredFrame.scene.contentLayer,
                    session.previewFrame.scene.contentLayer,
                )
                assertTrue(session.origin.contentLayer > session.preview.contentLayer)
            }

            assertEquals(
                forwardFrames[forwardFrames.lastIndex - backSessions.size]
                    .scene.currentEntry.route,
                navigationSnapshot.state.currentDestination,
            )
        }
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
            backPlan = origin.backPlan,
            origin = origin.origin,
            preview = origin.preview,
        )

        val invalidated = controller.invalidateIfOriginChanged(
            currentTopEntryId = origin.originTopEntryId,
            currentRevision = origin.originRevision + 1L,
            committedSnapshot = recovery,
        )

        assertEquals(origin.originTopEntryId, invalidated?.originTopEntryId)
        assertIs<ContentNavigationPresentationState.Animating>(controller.state)
        assertEquals(
            ContentBackCommitResult.Ignored,
            controller.commitPredictiveBack(hadProgress = true),
        )
    }

    private fun session(
        progress: Float = 0f,
        sessionId: Long = 10L,
        route: AppRoute = AppRoute.Settings,
    ): ContentBackSession {
        val navigationState = when (route) {
            AppRoute.Settings -> AppNavigationState().push(AppRoute.Settings)
            AppRoute.Search -> AppNavigationState()
                .push(AppRoute.Settings)
                .openSearch()

            else -> error("Unsupported test route: $route")
        }
        val navigationSnapshot = AppNavigationSnapshot(
            state = navigationState,
            revision = sessionId,
        )
        val backPlan = checkNotNull(navigationSnapshot.planBack())
        return ContentBackSession(
            sessionId = sessionId,
            backPlan = backPlan,
            originFrame = ContentTransitionFrame(
                scene = contentSceneSnapshot(navigationState.backStack),
            ),
            previewFrame = ContentTransitionFrame(
                scene = contentSceneSnapshot(backPlan.targetState.backStack),
            ),
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
