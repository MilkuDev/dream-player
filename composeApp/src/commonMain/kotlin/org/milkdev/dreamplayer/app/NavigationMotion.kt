package org.milkdev.dreamplayer.app

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import org.milkdev.dreamplayer.navigation.toMainTabOrNull
import kotlin.math.roundToInt

internal enum class BackSwipeEdge {
    Left,
    Right,
    None,
}

internal data class PlatformBackEvent(
    val progress: Float,
    val swipeEdge: BackSwipeEdge,
    val frameTimeMillis: Long = 0L,
)

internal enum class NavigationMotionKind {
    None,
    Forward,
    Backward,
    MainForward,
    MainBackward,
    FadeThrough,
}

internal enum class PredictiveBackMotionStyle {
    Stack,
    MainTabCarousel,
}

internal data class PredictiveCarouselOffsets(
    val originX: Int,
    val previewX: Int,
)

internal data class NavigationMotionContext(
    val transitionId: Long,
    val operation: NavigationOperation,
    val fromContentEntryId: Long,
    val toContentEntryId: Long,
)

internal fun resolveNavigationMotion(
    initial: ContentSceneSnapshot,
    target: ContentSceneSnapshot,
    transaction: NavigationTransaction?,
): NavigationMotionKind {
    return resolveNavigationMotion(
        initial = initial,
        target = target,
        context = transaction?.toMotionContext(),
    )
}

private fun resolveNavigationMotion(
    initial: ContentSceneSnapshot,
    target: ContentSceneSnapshot,
    context: NavigationMotionContext?,
): NavigationMotionKind {
    if (initial.currentEntry.entryId == target.currentEntry.entryId) {
        return NavigationMotionKind.None
    }

    val motionContext = context?.takeIf {
        it.fromContentEntryId == initial.currentEntry.entryId &&
            it.toContentEntryId == target.currentEntry.entryId
    } ?: return NavigationMotionKind.FadeThrough

    return when (motionContext.operation) {
        NavigationOperation.Push -> NavigationMotionKind.Forward
        NavigationOperation.Pop -> NavigationMotionKind.Backward

        NavigationOperation.MainSwitch -> {
            val initialTab = initial.currentEntry.route.toMainTabOrNull()
            val targetTab = target.currentEntry.route.toMainTabOrNull()
            when {
                initialTab == null || targetTab == null -> NavigationMotionKind.FadeThrough
                targetTab.position > initialTab.position -> NavigationMotionKind.MainForward
                else -> NavigationMotionKind.MainBackward
            }
        }

        NavigationOperation.SearchOpen,
        NavigationOperation.SearchClose -> NavigationMotionKind.FadeThrough

        NavigationOperation.OverlayOpen,
        NavigationOperation.OverlayClose,
        NavigationOperation.OverlayReset -> NavigationMotionKind.None
    }
}

internal fun navigationContentTransform(
    initial: ContentSceneSnapshot,
    target: ContentSceneSnapshot,
    context: NavigationMotionContext?,
): ContentTransform {
    val motionKind = resolveNavigationMotion(initial, target, context)
    val transform = when (motionKind) {
        NavigationMotionKind.None -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))

        NavigationMotionKind.Forward -> {
            (slideInHorizontally(
                animationSpec = tween(300, easing = MotionEnterEasing),
                initialOffsetX = { width -> width / 8 },
            ) + fadeIn(tween(220, delayMillis = 40))) togetherWith
                (slideOutHorizontally(
                    animationSpec = tween(220, easing = MotionExitEasing),
                    targetOffsetX = { width -> -width / 24 },
                ) + fadeOut(tween(160)))
        }

        NavigationMotionKind.Backward -> {
            (slideInHorizontally(
                animationSpec = tween(260, easing = MotionEnterEasing),
                initialOffsetX = { width -> -width / 8 },
            ) + fadeIn(tween(200)) + scaleIn(
                initialScale = 0.98f,
                animationSpec = tween(260, easing = MotionEnterEasing),
            )) togetherWith
                (slideOutHorizontally(
                    animationSpec = tween(240, easing = MotionExitEasing),
                    targetOffsetX = { width -> width / 10 },
                ) + fadeOut(tween(180)) + scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tween(240, easing = MotionExitEasing),
                ))
        }

        NavigationMotionKind.MainForward -> {
            slideInHorizontally(
                animationSpec = tween(MainCarouselDurationMillis, easing = MotionEnterEasing),
                initialOffsetX = { width -> width },
            ) togetherWith slideOutHorizontally(
                animationSpec = tween(MainCarouselDurationMillis, easing = MotionEnterEasing),
                targetOffsetX = { width -> -width },
            )
        }

        NavigationMotionKind.MainBackward -> {
            slideInHorizontally(
                animationSpec = tween(MainCarouselDurationMillis, easing = MotionEnterEasing),
                initialOffsetX = { width -> -width },
            ) togetherWith slideOutHorizontally(
                animationSpec = tween(MainCarouselDurationMillis, easing = MotionEnterEasing),
                targetOffsetX = { width -> width },
            )
        }

        NavigationMotionKind.FadeThrough -> {
            (fadeIn(tween(220, delayMillis = 80, easing = MotionEnterEasing)) +
                scaleIn(
                    initialScale = 0.985f,
                    animationSpec = tween(220, delayMillis = 40, easing = MotionEnterEasing),
                )) togetherWith fadeOut(tween(120, easing = MotionExitEasing))
        }
    }
    return ContentTransform(
        targetContentEnter = transform.targetContentEnter,
        initialContentExit = transform.initialContentExit,
        targetContentZIndex = target.contentLayer,
        sizeTransform = SizeTransform(
            clip = motionKind == NavigationMotionKind.MainForward ||
                motionKind == NavigationMotionKind.MainBackward,
        ),
    )
}

internal fun NavigationTransaction.toMotionContext(): NavigationMotionContext? {
    if (!affectsContent) return null
    return NavigationMotionContext(
        transitionId = id,
        operation = operation,
        fromContentEntryId = fromContentEntry.entryId,
        toContentEntryId = toContentEntry.entryId,
    )
}

internal fun resolvePredictiveBackMotionStyle(
    origin: ContentSceneSnapshot,
    preview: ContentSceneSnapshot,
    operation: NavigationOperation,
): PredictiveBackMotionStyle {
    val isMainTabSwitch =
        operation == NavigationOperation.MainSwitch &&
            origin.currentEntry.route.toMainTabOrNull() != null &&
            preview.currentEntry.route.toMainTabOrNull() != null
    return if (isMainTabSwitch) {
        PredictiveBackMotionStyle.MainTabCarousel
    } else {
        PredictiveBackMotionStyle.Stack
    }
}

internal fun predictiveCarouselOffsets(
    progress: Float,
    swipeEdge: BackSwipeEdge,
    fullWidth: Int,
    origin: ContentSceneSnapshot,
    preview: ContentSceneSnapshot,
): PredictiveCarouselOffsets {
    val coercedProgress = progress.coerceIn(0f, 1f)
    val direction = predictiveCarouselDirection(
        swipeEdge = swipeEdge,
        origin = origin,
        preview = preview,
    )
    return PredictiveCarouselOffsets(
        originX = (direction * fullWidth * coercedProgress).roundToInt(),
        previewX = (-direction * fullWidth * (1f - coercedProgress)).roundToInt(),
    )
}

internal fun mainTabCarouselSettleDurationMillis(
    progress: Float,
): Int {
    val remainingFraction = 1f - progress.coerceIn(0f, 1f)
    return (
        MainCarouselMinimumSettleDurationMillis +
            (MainCarouselDurationMillis - MainCarouselMinimumSettleDurationMillis) *
            remainingFraction
        ).roundToInt()
}

internal fun predictiveStackContentTransform(
    target: ContentSceneSnapshot,
): ContentTransform {
    return ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        targetContentZIndex = target.contentLayer,
        sizeTransform = SizeTransform(clip = false),
    )
}

internal fun predictiveBackCancelContentTransform(
    target: ContentSceneSnapshot,
    motionStyle: PredictiveBackMotionStyle = PredictiveBackMotionStyle.Stack,
): ContentTransform {
    return ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        targetContentZIndex = target.contentLayer,
        sizeTransform = SizeTransform(
            clip = motionStyle == PredictiveBackMotionStyle.MainTabCarousel,
        ),
    )
}

private fun predictiveCarouselDirection(
    swipeEdge: BackSwipeEdge,
    origin: ContentSceneSnapshot,
    preview: ContentSceneSnapshot,
): Int {
    return when (swipeEdge) {
        BackSwipeEdge.Left -> 1
        BackSwipeEdge.Right -> -1
        BackSwipeEdge.None -> {
            val originPosition = origin.currentEntry.route.toMainTabOrNull()?.position
            val previewPosition = preview.currentEntry.route.toMainTabOrNull()?.position
            if (
                originPosition != null &&
                previewPosition != null &&
                previewPosition > originPosition
            ) {
                -1
            } else {
                1
            }
        }
    }
}

internal const val MainCarouselDurationMillis = 300
private const val MainCarouselMinimumSettleDurationMillis = 220
internal val MotionEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val MotionExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
