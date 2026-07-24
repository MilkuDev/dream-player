package org.milkdev.dreamplayer.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal data class PredictiveStackVisualTransform(
    val scale: Float,
    val offsetX: Float,
    val alpha: Float,
)

internal fun predictiveStackVisualTransform(
    progress: Float,
    swipeEdge: BackSwipeEdge,
    fullWidthPx: Float,
): PredictiveStackVisualTransform {
    val coercedProgress = progress.coerceIn(0f, 1f)
    val direction = if (swipeEdge == BackSwipeEdge.Right) -1f else 1f
    return PredictiveStackVisualTransform(
        scale = 1f - (PredictiveStackScaleDistance * coercedProgress),
        offsetX = direction * fullWidthPx * PredictiveStackMaximumShiftFraction *
            coercedProgress,
        alpha = predictiveStackOriginAlpha(coercedProgress),
    )
}

internal fun predictiveStackOriginAlpha(progress: Float): Float {
    val fadeProgress = (
        (progress.coerceIn(0f, 1f) - PredictiveStackFadeStartProgress) /
            (1f - PredictiveStackFadeStartProgress)
        ).coerceIn(0f, 1f)
    val smoothFadeProgress = fadeProgress * fadeProgress * (3f - 2f * fadeProgress)
    return 1f - smoothFadeProgress
}

internal fun predictiveStackCornerRadius(progress: Float) =
    PredictiveStackMaximumCornerRadius * progress.coerceIn(0f, 1f)

@Composable
internal fun PredictiveStackHost(
    session: ContentBackSession?,
    presentationState: ContentNavigationPresentationState,
    modifier: Modifier = Modifier,
    onCancellationSettled: (sessionId: Long) -> Unit,
    onCommitVisualSettled: (sessionId: Long) -> Unit,
    onCommitHandoffSettled: (sessionId: Long) -> Unit,
    previewContent: @Composable (ContentBackSession) -> Unit,
    originContent: @Composable (ownsChrome: Boolean) -> Unit,
) {
    val predictiveSession = session
        ?.takeIf { it.mode == ContentBackMode.Predictive }
        ?.takeIf { it.motionStyle == PredictiveBackMotionStyle.Stack }
    val animatedProgress = remember(predictiveSession?.sessionId) {
        Animatable(predictiveSession?.progress?.coerceIn(0f, 1f) ?: 0f)
    }
    val committing = presentationState as? ContentNavigationPresentationState.Committing
    val visualHandoffStarted =
        predictiveSession != null &&
            committing?.session?.sessionId == predictiveSession.sessionId &&
            committing.visualSettled
    val commitPopCompleted =
        predictiveSession != null &&
            committing?.session?.sessionId == predictiveSession.sessionId &&
            committing.popCompleted

    LaunchedEffect(
        predictiveSession?.sessionId,
        presentationState::class,
        visualHandoffStarted,
        commitPopCompleted,
    ) {
        val activeSession = predictiveSession ?: return@LaunchedEffect
        when (val activeState = presentationState) {
            is ContentNavigationPresentationState.Cancelling -> {
                if (activeState.session.sessionId != activeSession.sessionId) {
                    return@LaunchedEffect
                }
                animatedProgress.snapTo(activeState.session.progress.coerceIn(0f, 1f))
                animatedProgress.updateBounds(0f, 1f)
                animatedProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = predictiveStackSettleSpring(),
                    initialVelocity = activeState.session.progressVelocity,
                )
                // Let Compose draw the terminal visual state before removing the two-layer host.
                withFrameNanos { }
                onCancellationSettled(activeSession.sessionId)
            }

            is ContentNavigationPresentationState.Committing -> {
                if (activeState.session.sessionId != activeSession.sessionId) {
                    return@LaunchedEffect
                }
                if (activeState.popCompleted) {
                    // Keep the retained preview visible while the committed backing scene draws.
                    withFrameNanos { }
                    withFrameNanos { }
                    onCommitHandoffSettled(activeSession.sessionId)
                    return@LaunchedEffect
                }
                if (activeState.visualSettled || activeState.popRequested) return@LaunchedEffect
                animatedProgress.snapTo(activeState.session.progress.coerceIn(0f, 1f))
                animatedProgress.updateBounds(0f, 1f)
                animatedProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = predictiveStackSettleSpring(),
                    initialVelocity = activeState.session.progressVelocity,
                )
                // The backing host is retargeted only after one frame can present progress == 1.
                withFrameNanos { }
                onCommitVisualSettled(activeSession.sessionId)
            }

            ContentNavigationPresentationState.Idle,
            is ContentNavigationPresentationState.Animating,
            is ContentNavigationPresentationState.Tracking -> Unit
        }
    }

    val visualProgress = when {
        predictiveSession == null -> 0f
        visualHandoffStarted -> 1f
        presentationState is ContentNavigationPresentationState.Tracking ->
            predictiveSession.progress

        else -> animatedProgress.value
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (predictiveSession != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                previewContent(predictiveSession)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (predictiveSession != null) {
                        Modifier.predictiveStackSceneTransform(
                            progress = visualProgress,
                            swipeEdge = predictiveSession.swipeEdge,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            originContent(predictiveSession != null)
        }
    }
}

private fun Modifier.predictiveStackSceneTransform(
    progress: Float,
    swipeEdge: BackSwipeEdge,
): Modifier {
    return graphicsLayer {
        val transform = predictiveStackVisualTransform(
            progress = progress,
            swipeEdge = swipeEdge,
            fullWidthPx = size.width,
        )
        scaleX = transform.scale
        scaleY = transform.scale
        translationX = transform.offsetX
        alpha = transform.alpha
        shape = RoundedCornerShape(predictiveStackCornerRadius(progress))
        clip = progress > 0f
    }
}

private fun predictiveStackSettleSpring() = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = PredictiveStackVisibilityThreshold,
)

internal const val PredictiveStackTargetScale = 0.8f
internal const val PredictiveStackMaximumShiftFraction = 0.08f
private const val PredictiveStackScaleDistance = 1f - PredictiveStackTargetScale
private const val PredictiveStackFadeStartProgress = 0.82f
private const val PredictiveStackVisibilityThreshold = 0.001f
internal val PredictiveStackMaximumCornerRadius = 28.dp
