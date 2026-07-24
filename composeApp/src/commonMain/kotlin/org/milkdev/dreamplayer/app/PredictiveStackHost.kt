package org.milkdev.dreamplayer.app

import androidx.compose.foundation.shape.RoundedCornerShape
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

internal fun predictiveStackCornerRadius(progress: Float): androidx.compose.ui.unit.Dp {
    val coercedProgress = progress.coerceIn(0f, 1f)
    val remainingProgress = 1f - coercedProgress
    val cornerProgress = 1f - remainingProgress * remainingProgress * remainingProgress
    return PredictiveStackMaximumCornerRadius * cornerProgress
}

internal fun Modifier.predictiveStackSceneTransform(
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

internal const val PredictiveStackTargetScale = 0.8f
internal const val PredictiveStackMaximumShiftFraction = 0.08f
private const val PredictiveStackScaleDistance = 1f - PredictiveStackTargetScale
private const val PredictiveStackFadeStartProgress = 0.82f
internal val PredictiveStackMaximumCornerRadius = 28.dp
