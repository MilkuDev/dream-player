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

internal enum class BackSwipeEdge {
    Left,
    Right,
    None,
}

internal data class PlatformBackEvent(
    val progress: Float,
    val swipeEdge: BackSwipeEdge,
)

internal enum class NavigationMotionKind {
    None,
    Forward,
    Backward,
    FadeThrough,
}

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

    if (
        motionContext.operation == NavigationOperation.MainSwitch ||
        motionContext.operation == NavigationOperation.SearchOpen ||
        motionContext.operation == NavigationOperation.SearchClose
    ) {
        return NavigationMotionKind.FadeThrough
    }

    return when (motionContext.operation) {
        NavigationOperation.Push -> NavigationMotionKind.Forward
        NavigationOperation.Pop -> NavigationMotionKind.Backward

        NavigationOperation.MainSwitch,
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
        sizeTransform = SizeTransform(clip = false),
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

internal fun predictiveBackContentTransform(
    swipeEdge: BackSwipeEdge,
    target: ContentSceneSnapshot,
): ContentTransform {
    val direction = if (swipeEdge == BackSwipeEdge.Right) -1 else 1
    val exit = slideOutHorizontally(
        animationSpec = tween(220, easing = MotionExitEasing),
        targetOffsetX = { width -> direction * width / 5 },
    ) + scaleOut(
        targetScale = 0.92f,
        animationSpec = tween(220, easing = MotionExitEasing),
    )
    return ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = exit,
        targetContentZIndex = target.contentLayer,
        sizeTransform = SizeTransform(clip = false),
    )
}

internal fun predictiveBackCancelContentTransform(
    target: ContentSceneSnapshot,
): ContentTransform {
    return ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        targetContentZIndex = target.contentLayer,
        sizeTransform = SizeTransform(clip = false),
    )
}

private val MotionEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val MotionExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
