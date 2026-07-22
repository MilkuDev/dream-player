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

internal data class ContentBackSession(
    val sessionId: Long,
    val originTopEntryId: Long,
    val origin: ContentSceneSnapshot,
    val preview: ContentSceneSnapshot,
    val progress: Float = 0f,
    val swipeEdge: BackSwipeEdge = BackSwipeEdge.None,
)

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
                if (state.session.originTopEntryId != event.session.originTopEntryId) {
                    ContentNavigationPresentationState.Tracking(event.session)
                } else {
                    state
                }
            }
        }

        is ContentNavigationPresentationEvent.BackProgressed -> {
            val tracking = state as? ContentNavigationPresentationState.Tracking
            if (tracking?.session?.sessionId == event.sessionId) {
                tracking.copy(
                    session = tracking.session.copy(
                        progress = event.progress.coerceIn(0f, 1f),
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
            } else if (event.hadProgress) {
                ContentNavigationPresentationState.Committing(tracking.session)
            } else {
                ContentNavigationPresentationState.Idle
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

    data class Immediate(
        val session: ContentBackSession,
    ) : ContentBackCommitResult

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
    val transitionState = DeferredTransitionState(initialSnapshot)

    var state: ContentNavigationPresentationState by mutableStateOf(
        ContentNavigationPresentationState.Idle,
    )
        private set

    val backSession: ContentBackSession?
        get() = state.backSession

    private var nextSessionId = 1L

    fun canRequestContentBack(isTransitionRunning: Boolean): Boolean {
        return state is ContentNavigationPresentationState.Idle && !isTransitionRunning
    }

    fun onCommittedSnapshotChanged(snapshot: ContentSceneSnapshot) {
        if (state.backSession != null) return
        if (
            transitionState.currentState == snapshot &&
            transitionState.targetState == snapshot &&
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
        transitionState.animateTo(snapshot)
    }

    fun startPredictiveBack(
        originTopEntryId: Long,
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
    ): ContentBackSession? {
        val session = ContentBackSession(
            sessionId = nextSessionId++,
            originTopEntryId = originTopEntryId,
            origin = origin,
            preview = preview,
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
        transitionState.defer(preview)
        return session
    }

    fun progressPredictiveBack(
        event: PlatformBackEvent,
        currentTopEntryId: Long,
    ) {
        val tracking = state as? ContentNavigationPresentationState.Tracking ?: return
        if (tracking.session.originTopEntryId != currentTopEntryId) return

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
        return if (hadProgress) {
            transitionState.animateTo(tracking.session.preview)
            ContentBackCommitResult.Animated(tracking.session)
        } else {
            transitionState.animateTo(tracking.session.origin)
            ContentBackCommitResult.Immediate(tracking.session)
        }
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
            transitionState.animateTo(current.session.origin)
        }
    }

    fun invalidateIfOriginChanged(
        currentTopEntryId: Long,
        committedSnapshot: ContentSceneSnapshot,
    ): ContentBackSession? {
        val session = state.backSession ?: return null
        if (session.originTopEntryId == currentTopEntryId) return null

        state = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.RouteInvalidated(committedSnapshot),
        )
        transitionState.animateTo(committedSnapshot)
        return session
    }

    fun onTransitionObserved(
        currentState: ContentSceneSnapshot,
        targetState: ContentSceneSnapshot,
        pendingTargetState: ContentSceneSnapshot?,
        isRunning: Boolean,
    ): ContentTransitionCompletion? {
        if (pendingTargetState != null || isRunning || currentState != targetState) return null

        val previousState = state
        val updatedState = reduceContentNavigationPresentation(
            state,
            ContentNavigationPresentationEvent.TransitionSettled(currentState),
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
            transitionState.animateTo(recoveryTarget)
        }
    }
}
