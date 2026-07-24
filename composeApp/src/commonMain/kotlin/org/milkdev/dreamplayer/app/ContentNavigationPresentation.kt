package org.milkdev.dreamplayer.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DeferredTransitionState
import androidx.compose.animation.core.ExperimentalDeferredTransitionApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationPlan
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import org.milkdev.dreamplayer.navigation.toMainTabOrNull

/** A scene paired with the transaction-scoped motion that presents it. */
internal data class ContentTransitionFrame(
    val scene: ContentSceneSnapshot,
    val motionContext: NavigationMotionContext? = null,
)

internal enum class ContentBackMode {
    Predictive,
    TimeDriven,
}

internal enum class ContentBackSource {
    Platform,
    Ui,
}

internal data class ContentBackSession(
    val sessionId: Long,
    val backPlan: NavigationPlan,
    val originFrame: ContentTransitionFrame,
    val previewFrame: ContentTransitionFrame,
    val mode: ContentBackMode = ContentBackMode.Predictive,
    val source: ContentBackSource = ContentBackSource.Platform,
    val motionStyle: PredictiveBackMotionStyle = PredictiveBackMotionStyle.Stack,
    val progress: Float = 0f,
    val progressEventCount: Int = 0,
    val maxProgress: Float = 0f,
    val swipeEdge: BackSwipeEdge = BackSwipeEdge.None,
    val progressVelocity: Float = 0f,
    val lastProgressFrameTimeMillis: Long = 0L,
) {
    val origin: ContentSceneSnapshot
        get() = originFrame.scene

    val preview: ContentSceneSnapshot
        get() = previewFrame.scene

    val originTopEntryId: Long
        get() = backPlan.expectedTopEntryId

    val originRevision: Long
        get() = backPlan.expectedRevision
}

internal sealed interface ContentNavigationPresentationState {
    data object Idle : ContentNavigationPresentationState

    data class Animating(
        val target: ContentSceneSnapshot,
    ) : ContentNavigationPresentationState

    data class Tracking(
        val session: ContentBackSession,
    ) : ContentNavigationPresentationState

    data class Cancelling(
        val session: ContentBackSession,
    ) : ContentNavigationPresentationState

    data class Committing(
        val session: ContentBackSession,
        val visualSettled: Boolean = false,
        val popRequested: Boolean = false,
        val popCompleted: Boolean = false,
    ) : ContentNavigationPresentationState
}

internal val ContentNavigationPresentationState.backSession: ContentBackSession?
    get() = when (this) {
        ContentNavigationPresentationState.Idle,
        is ContentNavigationPresentationState.Animating -> null

        is ContentNavigationPresentationState.Tracking -> session
        is ContentNavigationPresentationState.Cancelling -> session
        is ContentNavigationPresentationState.Committing -> session
    }

internal sealed interface ContentNavigationPresentationEvent {
    data class AnimationStarted(
        val target: ContentSceneSnapshot,
    ) : ContentNavigationPresentationEvent

    data class BackStarted(
        val session: ContentBackSession,
    ) : ContentNavigationPresentationEvent

    data class TimeDrivenBackStarted(
        val session: ContentBackSession,
    ) : ContentNavigationPresentationEvent

    data class BackProgressed(
        val sessionId: Long,
        val progress: Float,
        val swipeEdge: BackSwipeEdge,
        val frameTimeMillis: Long = 0L,
    ) : ContentNavigationPresentationEvent

    data class BackCancelled(
        val sessionId: Long,
    ) : ContentNavigationPresentationEvent

    data class BackCommitted(
        val sessionId: Long,
        val hadProgress: Boolean,
    ) : ContentNavigationPresentationEvent

    data class CancelProgressed(
        val sessionId: Long,
        val progress: Float,
    ) : ContentNavigationPresentationEvent

    data class CommitProgressed(
        val sessionId: Long,
        val progress: Float,
    ) : ContentNavigationPresentationEvent

    data class RouteInvalidated(
        val target: ContentSceneSnapshot,
    ) : ContentNavigationPresentationEvent

    data class TransitionSettled(
        val snapshot: ContentSceneSnapshot,
    ) : ContentNavigationPresentationEvent

    data class CommitPopCompleted(
        val sessionId: Long,
        val didPop: Boolean,
        val recoveryTarget: ContentSceneSnapshot,
    ) : ContentNavigationPresentationEvent
}

internal fun reduceContentNavigationPresentation(
    state: ContentNavigationPresentationState,
    event: ContentNavigationPresentationEvent,
): ContentNavigationPresentationState {
    return when (event) {
        is ContentNavigationPresentationEvent.AnimationStarted -> when (state) {
            ContentNavigationPresentationState.Idle,
            is ContentNavigationPresentationState.Animating ->
                ContentNavigationPresentationState.Animating(event.target)

            is ContentNavigationPresentationState.Tracking,
            is ContentNavigationPresentationState.Cancelling,
            is ContentNavigationPresentationState.Committing -> state
        }

        is ContentNavigationPresentationEvent.BackStarted -> when (state) {
            ContentNavigationPresentationState.Idle,
            is ContentNavigationPresentationState.Animating,
            is ContentNavigationPresentationState.Cancelling ->
                ContentNavigationPresentationState.Tracking(event.session)

            is ContentNavigationPresentationState.Tracking -> state
            is ContentNavigationPresentationState.Committing -> {
                if (
                    state.session.originTopEntryId != event.session.originTopEntryId ||
                    state.session.originRevision != event.session.originRevision
                ) {
                    ContentNavigationPresentationState.Tracking(event.session)
                } else {
                    state
                }
            }
        }

        is ContentNavigationPresentationEvent.TimeDrivenBackStarted -> when (state) {
            ContentNavigationPresentationState.Idle ->
                ContentNavigationPresentationState.Committing(event.session)

            is ContentNavigationPresentationState.Animating,
            is ContentNavigationPresentationState.Tracking,
            is ContentNavigationPresentationState.Cancelling,
            is ContentNavigationPresentationState.Committing -> state
        }

        is ContentNavigationPresentationEvent.BackProgressed -> {
            val tracking = state as? ContentNavigationPresentationState.Tracking
            if (tracking?.session?.sessionId == event.sessionId) {
                val progress = event.progress.coerceIn(0f, 1f)
                val progressVelocity = predictiveBackProgressVelocity(
                    previousProgress = tracking.session.progress,
                    previousFrameTimeMillis = tracking.session.lastProgressFrameTimeMillis,
                    progress = progress,
                    frameTimeMillis = event.frameTimeMillis,
                ) ?: tracking.session.progressVelocity
                tracking.copy(
                    session = tracking.session.copy(
                        progress = progress,
                        progressEventCount = tracking.session.progressEventCount + 1,
                        maxProgress = maxOf(
                            tracking.session.maxProgress,
                            progress,
                        ),
                        swipeEdge = event.swipeEdge,
                        progressVelocity = progressVelocity,
                        lastProgressFrameTimeMillis = event.frameTimeMillis.takeIf { it > 0L }
                            ?: tracking.session.lastProgressFrameTimeMillis,
                    ),
                )
            } else {
                state
            }
        }

        is ContentNavigationPresentationEvent.BackCancelled -> {
            val tracking = state as? ContentNavigationPresentationState.Tracking
            if (tracking?.session?.sessionId == event.sessionId) {
                ContentNavigationPresentationState.Cancelling(tracking.session)
            } else {
                state
            }
        }

        is ContentNavigationPresentationEvent.BackCommitted -> {
            val tracking = state as? ContentNavigationPresentationState.Tracking
            if (tracking?.session?.sessionId != event.sessionId) {
                state
            } else {
                ContentNavigationPresentationState.Committing(
                    tracking.session.copy(
                        mode = if (event.hadProgress) {
                            ContentBackMode.Predictive
                        } else {
                            ContentBackMode.TimeDriven
                        },
                    ),
                )
            }
        }

        is ContentNavigationPresentationEvent.CancelProgressed -> {
            val cancelling = state as? ContentNavigationPresentationState.Cancelling
            if (cancelling?.session?.sessionId == event.sessionId) {
                cancelling.copy(
                    session = cancelling.session.copy(
                        progress = event.progress.coerceIn(0f, 1f),
                    ),
                )
            } else {
                state
            }
        }

        is ContentNavigationPresentationEvent.CommitProgressed -> {
            val committing = state as? ContentNavigationPresentationState.Committing
            if (
                committing?.session?.sessionId == event.sessionId &&
                !committing.popRequested
            ) {
                committing.copy(
                    session = committing.session.copy(
                        progress = event.progress.coerceIn(0f, 1f),
                    ),
                )
            } else {
                state
            }
        }

        is ContentNavigationPresentationEvent.RouteInvalidated ->
            ContentNavigationPresentationState.Animating(event.target)

        is ContentNavigationPresentationEvent.TransitionSettled -> when (state) {
            is ContentNavigationPresentationState.Animating -> {
                if (state.target == event.snapshot) {
                    ContentNavigationPresentationState.Idle
                } else {
                    state
                }
            }

            is ContentNavigationPresentationState.Cancelling -> {
                if (state.session.origin == event.snapshot) {
                    ContentNavigationPresentationState.Idle
                } else {
                    state
                }
            }

            is ContentNavigationPresentationState.Committing -> {
                val predictiveStackCanComplete =
                    state.session.mode != ContentBackMode.Predictive ||
                        state.session.motionStyle != PredictiveBackMotionStyle.Stack ||
                        state.visualSettled
                if (state.session.preview == event.snapshot && predictiveStackCanComplete) {
                    state.copy(popRequested = true)
                } else {
                    state
                }
            }

            ContentNavigationPresentationState.Idle,
            is ContentNavigationPresentationState.Tracking -> state
        }

        is ContentNavigationPresentationEvent.CommitPopCompleted -> {
            val committing = state as? ContentNavigationPresentationState.Committing
            if (
                committing?.session?.sessionId != event.sessionId ||
                !committing.popRequested
            ) {
                state
            } else if (event.didPop) {
                if (
                    committing.session.mode == ContentBackMode.Predictive &&
                    committing.session.motionStyle == PredictiveBackMotionStyle.Stack
                ) {
                    committing.copy(popCompleted = true)
                } else {
                    ContentNavigationPresentationState.Idle
                }
            } else {
                ContentNavigationPresentationState.Animating(event.recoveryTarget)
            }
        }
    }
}

internal fun predictiveBackProgressVelocity(
    previousProgress: Float,
    previousFrameTimeMillis: Long,
    progress: Float,
    frameTimeMillis: Long,
): Float? {
    val elapsedMillis = frameTimeMillis - previousFrameTimeMillis
    if (
        previousFrameTimeMillis <= 0L ||
        frameTimeMillis <= 0L ||
        elapsedMillis !in 1L..PredictiveBackMaximumVelocitySampleMillis
    ) {
        return null
    }
    return (
        (progress.coerceIn(0f, 1f) - previousProgress.coerceIn(0f, 1f)) *
            MillisPerSecond / elapsedMillis
        ).coerceIn(-PredictiveBackMaximumProgressVelocity, PredictiveBackMaximumProgressVelocity)
}

internal sealed interface ContentBackCommitResult {
    data object NoSession : ContentBackCommitResult
    data object Ignored : ContentBackCommitResult

    data class Animated(
        val session: ContentBackSession,
    ) : ContentBackCommitResult
}

internal sealed interface ContentTransitionCompletion {
    data class Cancelled(
        val session: ContentBackSession,
    ) : ContentTransitionCompletion

    data class CommitReady(
        val session: ContentBackSession,
    ) : ContentTransitionCompletion
}

@Stable
@OptIn(ExperimentalDeferredTransitionApi::class)
internal class ContentNavigationPresentationController(
    initialSnapshot: ContentSceneSnapshot,
) {
    val transitionState = DeferredTransitionState(
        ContentTransitionFrame(
            scene = initialSnapshot,
        ),
    )

    var state: ContentNavigationPresentationState by mutableStateOf(
        ContentNavigationPresentationState.Idle,
    )
        private set

    val backSession: ContentBackSession?
        get() = state.backSession

    private var nextSessionId = 1L

    fun canRequestContentBack(isTransitionRunning: Boolean): Boolean {
        return state is ContentNavigationPresentationState.Idle &&
            !isTransitionRunning &&
            transitionState.pendingTargetState == null &&
            transitionState.currentState == transitionState.targetState
    }

    fun onCommittedSnapshotChanged(
        snapshot: ContentSceneSnapshot,
        transaction: NavigationTransaction?,
    ) {
        if (state.backSession != null) return
        if (
            transitionState.currentState.scene == snapshot &&
            transitionState.targetState.scene == snapshot &&
            transitionState.pendingTargetState == null
        ) {
            state = reduceContentNavigationPresentation(
                state,
                ContentNavigationPresentationEvent.AnimationStarted(snapshot),
            )
            state = reduceContentNavigationPresentation(
                state,
                ContentNavigationPresentationEvent.TransitionSettled(snapshot),
            )
            return
        }
        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.AnimationStarted(snapshot),
        )
        if (
            transaction?.operation == NavigationOperation.MainSwitch &&
            transaction.fromContentEntry.route.toMainTabOrNull() != null &&
            transaction.toContentEntry.route.toMainTabOrNull() != null
        ) {
            state = reduceContentNavigationPresentation(
                state,
                ContentNavigationPresentationEvent.TransitionSettled(snapshot),
            )
        } else {
            val targetFrame = newTransitionFrame(
                scene = snapshot,
                motionContext = transaction?.toMotionContext(),
            )
            transitionState.animateTo(targetFrame)
        }
    }

    fun startPredictiveBack(
        backPlan: NavigationPlan,
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
    ): ContentBackSession? {
        if (
            backPlan.cause != NavigationCause.Back ||
            backPlan.expectedTopEntryId != origin.currentEntry.entryId ||
            backPlan.targetState.currentContentEntry.entryId != preview.currentEntry.entryId
        ) {
            return null
        }
        val motionStyle = resolvePredictiveBackMotionStyle(
            origin = origin,
            preview = preview,
            operation = backPlan.operation,
        )
        val transitionRepresentsOrigin =
            transitionState.targetState.scene == origin ||
                (
                    motionStyle == PredictiveBackMotionStyle.MainTabCarousel &&
                        transitionState.targetState.scene.currentEntry.route
                            .toMainTabOrNull() != null
                    )
        if (!transitionRepresentsOrigin || transitionState.pendingTargetState != null) {
            return null
        }
        val originFrame = if (motionStyle == PredictiveBackMotionStyle.MainTabCarousel) {
            newTransitionFrame(scene = origin)
        } else {
            transitionState.targetState
        }
        val previewFrame = newTransitionFrame(
            scene = preview,
            motionContext = backPlan.toMotionContext(origin, preview),
        )
        val session = ContentBackSession(
            sessionId = nextSessionId++,
            backPlan = backPlan,
            originFrame = originFrame,
            previewFrame = previewFrame,
            motionStyle = motionStyle,
        )
        val updatedState = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.BackStarted(session),
        )
        if (
            updatedState !is ContentNavigationPresentationState.Tracking ||
            updatedState.session.sessionId != session.sessionId
        ) {
            return null
        }

        state = updatedState
        return session
    }

    fun startTimeDrivenBack(
        backPlan: NavigationPlan,
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
    ): ContentBackSession? {
        val motionStyle = resolvePredictiveBackMotionStyle(
            origin = origin,
            preview = preview,
            operation = backPlan.operation,
        )
        val transitionRepresentsOrigin =
            transitionState.targetState.scene == origin ||
                (
                    motionStyle == PredictiveBackMotionStyle.MainTabCarousel &&
                        transitionState.targetState.scene.currentEntry.route
                            .toMainTabOrNull() != null
                    )
        if (
            state !is ContentNavigationPresentationState.Idle ||
            backPlan.cause != NavigationCause.Back ||
            backPlan.expectedTopEntryId != origin.currentEntry.entryId ||
            backPlan.targetState.currentContentEntry.entryId != preview.currentEntry.entryId ||
            !transitionRepresentsOrigin ||
            transitionState.pendingTargetState != null
        ) {
            return null
        }

        val session = ContentBackSession(
            sessionId = nextSessionId++,
            backPlan = backPlan,
            originFrame = if (motionStyle == PredictiveBackMotionStyle.MainTabCarousel) {
                newTransitionFrame(scene = origin)
            } else {
                transitionState.targetState
            },
            previewFrame = newTransitionFrame(
                scene = preview,
                motionContext = backPlan.toMotionContext(origin, preview),
            ),
            mode = ContentBackMode.TimeDriven,
            source = ContentBackSource.Ui,
            motionStyle = motionStyle,
        )
        val updatedState = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.TimeDrivenBackStarted(session),
        )
        if (
            updatedState !is ContentNavigationPresentationState.Committing ||
            updatedState.session.sessionId != session.sessionId
        ) {
            return null
        }

        state = updatedState
        if (session.motionStyle != PredictiveBackMotionStyle.MainTabCarousel) {
            transitionState.animateTo(session.previewFrame)
        }
        return session
    }

    fun progressPredictiveBack(
        event: PlatformBackEvent,
        currentTopEntryId: Long,
        currentRevision: Long,
    ) {
        val tracking = state as? ContentNavigationPresentationState.Tracking ?: return
        if (
            tracking.session.originTopEntryId != currentTopEntryId ||
            tracking.session.originRevision != currentRevision
        ) {
            return
        }

        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.BackProgressed(
                sessionId = tracking.session.sessionId,
                progress = event.progress,
                swipeEdge = event.swipeEdge,
                frameTimeMillis = event.frameTimeMillis,
            ),
        )
    }

    fun cancelPredictiveBack(): ContentBackSession? {
        val tracking = state as? ContentNavigationPresentationState.Tracking ?: return null
        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.BackCancelled(tracking.session.sessionId),
        )
        return tracking.session
    }

    fun commitPredictiveBack(hadProgress: Boolean): ContentBackCommitResult {
        val tracking = state as? ContentNavigationPresentationState.Tracking
            ?: return if (state is ContentNavigationPresentationState.Idle) {
                ContentBackCommitResult.NoSession
            } else {
                ContentBackCommitResult.Ignored
            }

        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.BackCommitted(
                sessionId = tracking.session.sessionId,
                hadProgress = hadProgress,
            ),
        )
        val committing = state as? ContentNavigationPresentationState.Committing
            ?: return ContentBackCommitResult.Ignored
        if (
            committing.session.motionStyle == PredictiveBackMotionStyle.Stack &&
            committing.session.mode == ContentBackMode.TimeDriven
        ) {
            transitionState.animateTo(committing.session.previewFrame)
        }
        return ContentBackCommitResult.Animated(committing.session)
    }

    fun onPredictiveStackVisualSettled(
        sessionId: Long,
    ): Boolean {
        val committing = state as? ContentNavigationPresentationState.Committing ?: return false
        if (
            committing.session.sessionId != sessionId ||
            committing.session.mode != ContentBackMode.Predictive ||
            committing.session.motionStyle != PredictiveBackMotionStyle.Stack ||
            committing.visualSettled ||
            committing.popRequested
        ) {
            return false
        }

        state = committing.copy(visualSettled = true)
        transitionState.animateTo(committing.session.previewFrame)
        return true
    }

    fun onPredictiveStackCancellationSettled(
        sessionId: Long,
    ): ContentTransitionCompletion.Cancelled? {
        val cancelling = state as? ContentNavigationPresentationState.Cancelling ?: return null
        if (
            cancelling.session.sessionId != sessionId ||
            cancelling.session.mode != ContentBackMode.Predictive ||
            cancelling.session.motionStyle != PredictiveBackMotionStyle.Stack
        ) {
            return null
        }
        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.TransitionSettled(cancelling.session.origin),
        )
        return ContentTransitionCompletion.Cancelled(cancelling.session)
    }

    fun onPredictiveStackHandoffSettled(
        sessionId: Long,
    ): Boolean {
        val committing = state as? ContentNavigationPresentationState.Committing ?: return false
        if (
            committing.session.sessionId != sessionId ||
            committing.session.mode != ContentBackMode.Predictive ||
            committing.session.motionStyle != PredictiveBackMotionStyle.Stack ||
            !committing.visualSettled ||
            !committing.popRequested ||
            !committing.popCompleted
        ) {
            return false
        }
        state = ContentNavigationPresentationState.Idle
        return true
    }

    suspend fun settleMainTabCarouselCommit(
        sessionId: Long,
    ): ContentTransitionCompletion.CommitReady? {
        val committing =
            state as? ContentNavigationPresentationState.Committing ?: return null
        if (
            committing.session.sessionId != sessionId ||
            committing.session.motionStyle != PredictiveBackMotionStyle.MainTabCarousel ||
            committing.popRequested
        ) {
            return null
        }

        val initialProgress = committing.session.progress.coerceIn(0f, 1f)
        val commitProgress = Animatable(initialProgress)
        commitProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = mainTabCarouselSettleDurationMillis(initialProgress),
                easing = MotionEnterEasing,
            ),
        ) {
            state = reduceContentNavigationPresentation(
                state,
                ContentNavigationPresentationEvent.CommitProgressed(
                    sessionId = sessionId,
                    progress = value,
                ),
            )
        }

        return onMainTabCarouselSettled(sessionId)
    }

    fun onMainTabCarouselSettled(
        sessionId: Long,
    ): ContentTransitionCompletion.CommitReady? {
        val current = state as? ContentNavigationPresentationState.Committing
        if (
            current?.session?.sessionId == sessionId &&
            current.session.motionStyle == PredictiveBackMotionStyle.MainTabCarousel &&
            !current.popRequested
        ) {
            state = reduceContentNavigationPresentation(
                state,
                ContentNavigationPresentationEvent.TransitionSettled(
                    current.session.preview,
                ),
            )
            return ContentTransitionCompletion.CommitReady(current.session)
        }
        return null
    }

    suspend fun settleCancellation(
        sessionId: Long,
    ): ContentTransitionCompletion.Cancelled? {
        val cancelling =
            state as? ContentNavigationPresentationState.Cancelling ?: return null
        if (cancelling.session.sessionId != sessionId) return null
        if (
            cancelling.session.mode == ContentBackMode.Predictive &&
            cancelling.session.motionStyle == PredictiveBackMotionStyle.Stack
        ) {
            return null
        }

        val cancelProgress = Animatable(cancelling.session.progress.coerceIn(0f, 1f))
        cancelProgress.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = if (
                    cancelling.session.motionStyle == PredictiveBackMotionStyle.Stack
                ) {
                    Spring.StiffnessMedium
                } else {
                    Spring.StiffnessMediumLow
                },
            ),
        ) {
            state = reduceContentNavigationPresentation(
                state,
                ContentNavigationPresentationEvent.CancelProgressed(
                    sessionId = sessionId,
                    progress = value,
                ),
            )
        }

        val current = state as? ContentNavigationPresentationState.Cancelling
        if (current?.session?.sessionId == sessionId) {
            if (current.session.motionStyle == PredictiveBackMotionStyle.MainTabCarousel) {
                state = reduceContentNavigationPresentation(
                    state,
                    ContentNavigationPresentationEvent.TransitionSettled(
                        current.session.origin,
                    ),
                )
                return ContentTransitionCompletion.Cancelled(current.session)
            }
            transitionState.animateTo(current.session.originFrame)
        }
        return null
    }

    fun invalidateIfOriginChanged(
        currentTopEntryId: Long,
        currentRevision: Long,
        committedSnapshot: ContentSceneSnapshot,
    ): ContentBackSession? {
        val committing = state as? ContentNavigationPresentationState.Committing
        if (committing?.popCompleted == true) return null
        val session = state.backSession ?: return null
        if (
            session.originTopEntryId == currentTopEntryId &&
            session.originRevision == currentRevision
        ) {
            return null
        }

        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.RouteInvalidated(committedSnapshot),
        )
        val recoveryFrame = newTransitionFrame(scene = committedSnapshot)
        if (session.motionStyle == PredictiveBackMotionStyle.MainTabCarousel) {
            state = reduceContentNavigationPresentation(
                state,
                ContentNavigationPresentationEvent.TransitionSettled(committedSnapshot),
            )
        } else {
            transitionState.animateTo(recoveryFrame)
        }
        return session
    }

    fun onTransitionObserved(
        currentState: ContentTransitionFrame,
        targetState: ContentTransitionFrame,
        pendingTargetState: ContentTransitionFrame?,
        isRunning: Boolean,
    ): ContentTransitionCompletion? {
        // Main-tab back is settled exclusively by MainTabCarouselHost. The outer
        // DeferredAnimatedContent intentionally stays on an arbitrary stable root
        // frame, which can already equal the preview and must not complete the back.
        if (state.backSession?.motionStyle == PredictiveBackMotionStyle.MainTabCarousel) {
            return null
        }
        if (pendingTargetState != null || isRunning || currentState != targetState) return null

        val previousState = state
        val updatedState = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.TransitionSettled(currentState.scene),
        )
        state = updatedState
        return when {
            previousState is ContentNavigationPresentationState.Cancelling &&
                updatedState is ContentNavigationPresentationState.Idle ->
                ContentTransitionCompletion.Cancelled(previousState.session)

            previousState is ContentNavigationPresentationState.Committing &&
                !previousState.popRequested &&
                updatedState is ContentNavigationPresentationState.Committing &&
                updatedState.popRequested ->
                ContentTransitionCompletion.CommitReady(previousState.session)

            else -> null
        }
    }

    fun onCommitPopCompleted(
        sessionId: Long,
        didPop: Boolean,
        recoveryTarget: ContentSceneSnapshot,
    ) {
        val committing = state as? ContentNavigationPresentationState.Committing
        val matchesPendingPop = committing?.session?.sessionId == sessionId &&
            committing.popRequested
        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.CommitPopCompleted(
                sessionId = sessionId,
                didPop = didPop,
                recoveryTarget = recoveryTarget,
            ),
        )
        if (matchesPendingPop && !didPop) {
            val recoveryFrame = committing.session.originFrame
                .takeIf { it.scene == recoveryTarget }
                ?: newTransitionFrame(scene = recoveryTarget)
            if (committing.session.motionStyle == PredictiveBackMotionStyle.MainTabCarousel) {
                state = reduceContentNavigationPresentation(
                    state,
                    ContentNavigationPresentationEvent.TransitionSettled(recoveryTarget),
                )
            } else {
                transitionState.animateTo(recoveryFrame)
            }
        }
    }

    private fun newTransitionFrame(
        scene: ContentSceneSnapshot,
        motionContext: NavigationMotionContext? = null,
    ): ContentTransitionFrame {
        return ContentTransitionFrame(
            scene = scene,
            motionContext = motionContext,
        )
    }
}

private const val MillisPerSecond = 1_000f
private const val PredictiveBackMaximumVelocitySampleMillis = 100L
private const val PredictiveBackMaximumProgressVelocity = 20f

private fun NavigationPlan.toMotionContext(
    origin: ContentSceneSnapshot,
    preview: ContentSceneSnapshot,
): NavigationMotionContext {
    return NavigationMotionContext(
        transitionId = expectedRevision + 1L,
        operation = operation,
        fromContentEntryId = origin.currentEntry.entryId,
        toContentEntryId = preview.currentEntry.entryId,
    )
}
