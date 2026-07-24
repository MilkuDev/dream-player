package org.milkdev.dreamplayer.app

import androidx.compose.animation.core.ExperimentalDeferredTransitionApi
import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.navigation.AppNavigationSnapshot
import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainTab
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import org.milkdev.dreamplayer.navigation.planBack
import org.milkdev.dreamplayer.navigation.toMainTabOrNull
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
    fun trackingCarriesProgressVelocityIntoTheSettleSession() {
        val session = session()
        val first = assertIs<ContentNavigationPresentationState.Tracking>(
            reduceContentNavigationPresentation(
                ContentNavigationPresentationState.Tracking(session),
                ContentNavigationPresentationEvent.BackProgressed(
                    sessionId = session.sessionId,
                    progress = 0.2f,
                    swipeEdge = BackSwipeEdge.Left,
                    frameTimeMillis = 1_000L,
                ),
            ),
        )
        val second = assertIs<ContentNavigationPresentationState.Tracking>(
            reduceContentNavigationPresentation(
                first,
                ContentNavigationPresentationEvent.BackProgressed(
                    sessionId = session.sessionId,
                    progress = 0.36f,
                    swipeEdge = BackSwipeEdge.Left,
                    frameTimeMillis = 1_016L,
                ),
            ),
        )

        assertEquals(10f, second.session.progressVelocity, absoluteTolerance = 0.0001f)
        assertEquals(1_016L, second.session.lastProgressFrameTimeMillis)
        val committing = assertIs<ContentNavigationPresentationState.Committing>(
            reduceContentNavigationPresentation(
                second,
                ContentNavigationPresentationEvent.BackCommitted(
                    sessionId = session.sessionId,
                    hadProgress = true,
                ),
            ),
        )
        assertEquals(second.session.progressVelocity, committing.session.progressVelocity)
    }

    @Test
    fun progressVelocityRejectsMissingOrStaleFrameTimesAndClampsSpikes() {
        assertEquals(
            null,
            predictiveBackProgressVelocity(
                previousProgress = 0.2f,
                previousFrameTimeMillis = 0L,
                progress = 0.3f,
                frameTimeMillis = 16L,
            ),
        )
        assertEquals(
            null,
            predictiveBackProgressVelocity(
                previousProgress = 0.2f,
                previousFrameTimeMillis = 1_000L,
                progress = 0.3f,
                frameTimeMillis = 1_200L,
            ),
        )
        assertEquals(
            20f,
            predictiveBackProgressVelocity(
                previousProgress = 0f,
                previousFrameTimeMillis = 1_000L,
                progress = 1f,
                frameTimeMillis = 1_001L,
            ),
        )
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
    fun mainTabTimeDrivenBackWaitsForCarouselBeforeChangingOuterHost() {
        val navigationState = AppNavigationState()
            .selectMainTab(MainTab.Library)
        val navigationSnapshot = AppNavigationSnapshot(
            state = navigationState,
            revision = 21L,
        )
        val backPlan = checkNotNull(navigationSnapshot.planBack())
        val origin = contentSceneSnapshot(navigationState.backStack)
        val preview = contentSceneSnapshot(backPlan.targetState.backStack)
        val controller = ContentNavigationPresentationController(origin)

        val session = checkNotNull(
            controller.startTimeDrivenBack(
                backPlan = backPlan,
                origin = origin,
                preview = preview,
            ),
        )

        assertEquals(PredictiveBackMotionStyle.MainTabCarousel, session.motionStyle)
        assertEquals(origin, controller.transitionState.targetState.scene)
        assertEquals(null, controller.transitionState.pendingTargetState)
        assertEquals(0f, controller.backSession?.progress)
    }

    @Test
    fun directMainTabSwitchKeepsOuterHostOnStableTabSurface() {
        val homeState = AppNavigationState()
        val libraryState = homeState.selectMainTab(MainTab.Library)
        val home = contentSceneSnapshot(homeState.backStack)
        val library = contentSceneSnapshot(libraryState.backStack)
        val controller = ContentNavigationPresentationController(home)
        val transaction = NavigationTransaction(
            id = 1L,
            operation = NavigationOperation.MainSwitch,
            cause = NavigationCause.Direct,
            fromEntry = homeState.currentEntry,
            toEntry = libraryState.currentEntry,
            fromContentEntry = homeState.currentContentEntry,
            toContentEntry = libraryState.currentContentEntry,
        )

        controller.onCommittedSnapshotChanged(
            snapshot = library,
            transaction = transaction,
        )

        assertEquals(home, controller.transitionState.currentState.scene)
        assertEquals(home, controller.transitionState.targetState.scene)
        assertEquals(null, controller.transitionState.pendingTargetState)
        assertIs<ContentNavigationPresentationState.Idle>(controller.state)
    }

    @Test
    fun mainTabPredictiveCommitLeavesOuterHostUntouchedUntilCarouselSettles() {
        val navigationState = AppNavigationState()
            .selectMainTab(MainTab.Library)
        val navigationSnapshot = AppNavigationSnapshot(
            state = navigationState,
            revision = 22L,
        )
        val backPlan = checkNotNull(navigationSnapshot.planBack())
        val origin = contentSceneSnapshot(navigationState.backStack)
        val preview = contentSceneSnapshot(backPlan.targetState.backStack)
        val controller = ContentNavigationPresentationController(origin)
        val session = checkNotNull(
            controller.startPredictiveBack(
                backPlan = backPlan,
                origin = origin,
                preview = preview,
            ),
        )
        controller.progressPredictiveBack(
            event = PlatformBackEvent(0.5f, BackSwipeEdge.Right),
            currentTopEntryId = navigationState.currentEntry.entryId,
            currentRevision = navigationSnapshot.revision,
        )

        controller.commitPredictiveBack(hadProgress = true)

        assertEquals(PredictiveBackMotionStyle.MainTabCarousel, session.motionStyle)
        assertEquals(origin, controller.transitionState.targetState.scene)
        assertEquals(origin, controller.transitionState.currentState.scene)
        assertEquals(null, controller.transitionState.pendingTargetState)
        assertEquals(0.5f, controller.backSession?.progress)
    }

    @Test
    fun platformBackWithoutProgressKeepsTheOrdinaryTimeDrivenTransition() {
        val navigationState = AppNavigationState()
            .push(AppRoute.Settings)
        val navigationSnapshot = AppNavigationSnapshot(
            state = navigationState,
            revision = 23L,
        )
        val backPlan = checkNotNull(navigationSnapshot.planBack())
        val origin = contentSceneSnapshot(navigationState.backStack)
        val preview = contentSceneSnapshot(backPlan.targetState.backStack)
        val controller = ContentNavigationPresentationController(origin)
        controller.startPredictiveBack(
            backPlan = backPlan,
            origin = origin,
            preview = preview,
        )

        val commit = assertIs<ContentBackCommitResult.Animated>(
            controller.commitPredictiveBack(hadProgress = false),
        )

        assertEquals(ContentBackMode.TimeDriven, commit.session.mode)
        assertEquals(null, controller.transitionState.pendingTargetState)
        assertEquals(preview, controller.transitionState.targetState.scene)
    }

    @Test
    fun predictiveStackCancellationSettlesWithoutRetargetingTheBackingHost() {
        val navigationState = AppNavigationState()
            .push(AppRoute.Settings)
        val navigationSnapshot = AppNavigationSnapshot(
            state = navigationState,
            revision = 24L,
        )
        val backPlan = checkNotNull(navigationSnapshot.planBack())
        val origin = contentSceneSnapshot(navigationState.backStack)
        val preview = contentSceneSnapshot(backPlan.targetState.backStack)
        val controller = ContentNavigationPresentationController(origin)
        val session = checkNotNull(
            controller.startPredictiveBack(
                backPlan = backPlan,
                origin = origin,
                preview = preview,
            ),
        )
        controller.progressPredictiveBack(
            event = PlatformBackEvent(
                progress = 0.6f,
                swipeEdge = BackSwipeEdge.Left,
                frameTimeMillis = 1_000L,
            ),
            currentTopEntryId = navigationState.currentEntry.entryId,
            currentRevision = navigationSnapshot.revision,
        )

        controller.cancelPredictiveBack()
        val completion = assertNotNull(
            controller.onPredictiveStackCancellationSettled(session.sessionId),
        )

        assertSame(session.backPlan, completion.session.backPlan)
        assertIs<ContentNavigationPresentationState.Idle>(controller.state)
        assertEquals(origin, controller.transitionState.currentState.scene)
        assertEquals(origin, controller.transitionState.targetState.scene)
        assertEquals(null, controller.transitionState.pendingTargetState)
    }

    @Test
    fun stableOuterRootCannotPrematurelyCompleteMainTabCommit() {
        val homeState = AppNavigationState()
        val libraryState = homeState.selectMainTab(MainTab.Library)
        val home = contentSceneSnapshot(homeState.backStack)
        val library = contentSceneSnapshot(libraryState.backStack)
        val controller = ContentNavigationPresentationController(home)
        controller.onCommittedSnapshotChanged(
            snapshot = library,
            transaction = NavigationTransaction(
                id = 1L,
                operation = NavigationOperation.MainSwitch,
                cause = NavigationCause.Direct,
                fromEntry = homeState.currentEntry,
                toEntry = libraryState.currentEntry,
                fromContentEntry = homeState.currentContentEntry,
                toContentEntry = libraryState.currentContentEntry,
            ),
        )
        val navigationSnapshot = AppNavigationSnapshot(
            state = libraryState,
            revision = 1L,
        )
        val backPlan = checkNotNull(navigationSnapshot.planBack())
        val session = checkNotNull(
            controller.startPredictiveBack(
                backPlan = backPlan,
                origin = library,
                preview = home,
            ),
        )
        controller.progressPredictiveBack(
            event = PlatformBackEvent(0.2f, BackSwipeEdge.Left),
            currentTopEntryId = libraryState.currentEntry.entryId,
            currentRevision = navigationSnapshot.revision,
        )
        controller.commitPredictiveBack(hadProgress = true)

        val completion = controller.onTransitionObserved(
            currentState = controller.transitionState.currentState,
            targetState = controller.transitionState.targetState,
            pendingTargetState = null,
            isRunning = false,
        )

        assertEquals(null, completion)
        val committing =
            assertIs<ContentNavigationPresentationState.Committing>(controller.state)
        assertEquals(session.sessionId, committing.session.sessionId)
        assertEquals(0.2f, committing.session.progress)
        assertEquals(false, committing.popRequested)
    }

    @Test
    fun consecutiveTimeDrivenBacksReachHomeAndLibraryWithDistinctMotionRuns() {
        val roots = listOf(
            AppNavigationState() to AppRoute.Home,
            AppNavigationState().selectMainTab(MainTab.Library) to AppRoute.Library,
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
            AppNavigationState().selectMainTab(MainTab.Library) to AppRoute.Library,
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
                assertEquals(null, controller.transitionState.pendingTargetState)
                assertEquals(origin, controller.transitionState.targetState.scene)

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
                assertEquals(origin, controller.transitionState.targetState.scene)
                assertTrue(
                    controller.onPredictiveStackVisualSettled(commit.session.sessionId),
                )
                assertEquals(null, controller.transitionState.pendingTargetState)
                assertEquals(preview, controller.transitionState.targetState.scene)
                assertTrue(
                    assertIs<ContentNavigationPresentationState.Committing>(
                        controller.state,
                    ).visualSettled,
                )
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
                assertTrue(
                    assertIs<ContentNavigationPresentationState.Committing>(
                        controller.state,
                    ).popCompleted,
                )
                assertEquals(
                    null,
                    controller.invalidateIfOriginChanged(
                        currentTopEntryId = backPlan.targetState.currentEntry.entryId,
                        currentRevision = navigationSnapshot.revision + 1L,
                        committedSnapshot = preview,
                    ),
                )
                navigationSnapshot = AppNavigationSnapshot(
                    state = backPlan.targetState,
                    revision = navigationSnapshot.revision + 1L,
                )
                assertTrue(
                    controller.onPredictiveStackHandoffSettled(
                        completion.session.sessionId,
                    ),
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
            val mainTab: MainTab?,
            val pushedRoutes: List<AppRoute>,
        )

        val cases = listOf(
            HistoryCase(
                mainTab = null,
                pushedRoutes = listOf(AppRoute.Settings, AppRoute.AiDebugSettings),
            ),
            HistoryCase(
                mainTab = MainTab.Library,
                pushedRoutes = listOf(AppRoute.Settings, AppRoute.AiDebugSettings),
            ),
            HistoryCase(
                mainTab = MainTab.Library,
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
                mainTab = MainTab.Library,
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
                val targetScene = contentSceneSnapshot(
                    navigationSnapshot.state.backStack,
                )
                controller.onCommittedSnapshotChanged(
                    snapshot = targetScene,
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
                return if (
                    operation == NavigationOperation.MainSwitch &&
                    transaction.fromContentEntry.route.toMainTabOrNull() != null &&
                    transaction.toContentEntry.route.toMainTabOrNull() != null
                ) {
                    ContentTransitionFrame(targetScene)
                } else {
                    targetFrame
                }
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
                val completion = assertIs<ContentTransitionCompletion.CommitReady>(
                    if (session.motionStyle == PredictiveBackMotionStyle.MainTabCarousel) {
                        controller.onMainTabCarouselSettled(session.sessionId)
                    } else {
                        assertTrue(
                            controller.onPredictiveStackVisualSettled(session.sessionId),
                        )
                        val targetFrame = controller.transitionState.targetState
                        controller.onTransitionObserved(
                            currentState = targetFrame,
                            targetState = targetFrame,
                            pendingTargetState = null,
                            isRunning = false,
                        )
                    },
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
                if (session.motionStyle == PredictiveBackMotionStyle.Stack) {
                    assertTrue(
                        controller.onPredictiveStackHandoffSettled(
                            completion.session.sessionId,
                        ),
                    )
                }
                assertIs<ContentNavigationPresentationState.Idle>(controller.state)
                return session
            }

            val forwardFrames = mutableListOf(controller.transitionState.currentState)
            historyCase.mainTab?.let { tab ->
                forwardFrames += commitAndSettle(
                    targetState = navigationSnapshot.state.selectMainTab(tab),
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
                if (session.backPlan.operation == NavigationOperation.MainSwitch) {
                    assertEquals(
                        PredictiveBackMotionStyle.MainTabCarousel,
                        session.motionStyle,
                    )
                    assertEquals(session.origin.contentLayer, session.preview.contentLayer)
                } else {
                    assertTrue(session.origin.contentLayer > session.preview.contentLayer)
                }
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
    fun committingCarouselAcceptsOnlyItsOwnSettleProgress() {
        val session = session(progress = 0.4f).copy(
            motionStyle = PredictiveBackMotionStyle.MainTabCarousel,
        )
        val committing = ContentNavigationPresentationState.Committing(session)

        assertSame(
            committing,
            reduceContentNavigationPresentation(
                committing,
                ContentNavigationPresentationEvent.CommitProgressed(
                    sessionId = session.sessionId + 1,
                    progress = 0.8f,
                ),
            ),
        )
        val updated = reduceContentNavigationPresentation(
            committing,
            ContentNavigationPresentationEvent.CommitProgressed(
                sessionId = session.sessionId,
                progress = 1.5f,
            ),
        )
        val updatedCommitting =
            assertIs<ContentNavigationPresentationState.Committing>(updated)
        assertEquals(1f, updatedCommitting.session.progress)

        val popRequested = updatedCommitting.copy(popRequested = true)
        assertSame(
            popRequested,
            reduceContentNavigationPresentation(
                popRequested,
                ContentNavigationPresentationEvent.CommitProgressed(
                    sessionId = session.sessionId,
                    progress = 0f,
                ),
            ),
        )
    }

    @Test
    fun predictiveStackRequestsOneGuardedPopOnlyAfterVisualHandoff() {
        val session = session(progress = 1f)
        val committing = ContentNavigationPresentationState.Committing(session)

        assertEquals(
            committing,
            reduceContentNavigationPresentation(
                committing,
                ContentNavigationPresentationEvent.TransitionSettled(session.preview),
            ),
        )
        val ready = reduceContentNavigationPresentation(
            committing.copy(visualSettled = true),
            ContentNavigationPresentationEvent.TransitionSettled(session.preview),
        )
        val popRequested = assertIs<ContentNavigationPresentationState.Committing>(ready)
        assertEquals(true, popRequested.popRequested)
        assertEquals(popRequested, reduceContentNavigationPresentation(
            popRequested,
            ContentNavigationPresentationEvent.TransitionSettled(session.preview),
        ))
        val popCompleted = assertIs<ContentNavigationPresentationState.Committing>(
            reduceContentNavigationPresentation(
                popRequested,
                ContentNavigationPresentationEvent.CommitPopCompleted(
                    sessionId = session.sessionId,
                    didPop = true,
                    recoveryTarget = session.origin,
                ),
            ),
        )
        assertEquals(true, popCompleted.popCompleted)
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
