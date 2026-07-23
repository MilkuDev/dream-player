package org.milkdev.dreamplayer.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DeferredTransitionState
import androidx.compose.animation.core.ExperimentalDeferredTransitionApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationPlan
import org.milkdev.dreamplayer.navigation.NavigationTransaction

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
    val progress: Float = 0f,
    val progressEventCount: Int = 0,
    val maxProgress: Float = 0f,
    val swipeEdge: BackSwipeEdge = BackSwipeEdge.None,
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
        val popRequested: Boolean = false,
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
                tracking.copy(
                    session = tracking.session.copy(
                        progress = event.progress.coerceIn(0f, 1f),
                        progressEventCount = tracking.session.progressEventCount + 1,
                        maxProgress = maxOf(
                            tracking.session.maxProgress,
                            event.progress.coerceIn(0f, 1f),
                        ),
                        swipeEdge = event.swipeEdge,
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
                if (state.session.preview == event.snapshot) {
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
                ContentNavigationPresentationState.Idle
            } else {
                ContentNavigationPresentationState.Animating(event.recoveryTarget)
            }
        }
    }
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
        val targetFrame = newTransitionFrame(
            scene = snapshot,
            motionContext = transaction?.toMotionContext(),
        )

        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.AnimationStarted(snapshot),
        )
        transitionState.animateTo(targetFrame)
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
        if (
            transitionState.targetState.scene != origin ||
            transitionState.pendingTargetState != null
        ) {
            return null
        }
        val originFrame = transitionState.targetState
        val previewFrame = newTransitionFrame(
            scene = preview,
            motionContext = backPlan.toMotionContext(origin, preview),
        )
        val session = ContentBackSession(
            sessionId = nextSessionId++,
            backPlan = backPlan,
            originFrame = originFrame,
            previewFrame = previewFrame,
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
        transitionState.defer(previewFrame)
        return session
    }

    fun startTimeDrivenBack(
        backPlan: NavigationPlan,
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
    ): ContentBackSession? {
        if (
            state !is ContentNavigationPresentationState.Idle ||
            backPlan.cause != NavigationCause.Back ||
            backPlan.expectedTopEntryId != origin.currentEntry.entryId ||
            backPlan.targetState.currentContentEntry.entryId != preview.currentEntry.entryId ||
            transitionState.targetState.scene != origin ||
            transitionState.pendingTargetState != null
        ) {
            return null
        }

        val session = ContentBackSession(
            sessionId = nextSessionId++,
            backPlan = backPlan,
            originFrame = transitionState.targetState,
            previewFrame = newTransitionFrame(
                scene = preview,
                motionContext = backPlan.toMotionContext(origin, preview),
            ),
            mode = ContentBackMode.TimeDriven,
            source = ContentBackSource.Ui,
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
        transitionState.animateTo(session.previewFrame)
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
        transitionState.animateTo(committing.session.previewFrame)
        return ContentBackCommitResult.Animated(committing.session)
    }

    suspend fun settleCancellation(sessionId: Long) {
        val cancelling = state as? ContentNavigationPresentationState.Cancelling ?: return
        if (cancelling.session.sessionId != sessionId) return

        val cancelProgress = Animatable(cancelling.session.progress.coerceIn(0f, 1f))
        cancelProgress.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
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
            transitionState.animateTo(current.session.originFrame)
        }
    }

    fun invalidateIfOriginChanged(
        currentTopEntryId: Long,
        currentRevision: Long,
        committedSnapshot: ContentSceneSnapshot,
    ): ContentBackSession? {
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
        transitionState.animateTo(newTransitionFrame(scene = committedSnapshot))
        return session
    }

    fun onTransitionObserved(
        currentState: ContentTransitionFrame,
        targetState: ContentTransitionFrame,
        pendingTargetState: ContentTransitionFrame?,
        isRunning: Boolean,
    ): ContentTransitionCompletion? {
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
            transitionState.animateTo(recoveryFrame)
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
