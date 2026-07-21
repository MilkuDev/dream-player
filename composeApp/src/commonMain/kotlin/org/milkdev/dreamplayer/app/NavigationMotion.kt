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
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainDestination
import org.milkdev.dreamplayer.navigation.NavigationEntry

internal enum class BackSwipeEdge {
    Left,
    Right,
    None,
}

internal data class PlatformBackEvent(
    val progress: Float,
    val swipeEdge: BackSwipeEdge,
)

internal data class ContentNavigationSnapshot(
    val currentEntry: NavigationEntry,
    val contentStack: List<NavigationEntry>,
) {
    val activeMainDestination: MainDestination
        get() = contentStack.asReversed().firstNotNullOfOrNull { entry ->
            when (entry.route) {
                AppRoute.Home -> MainDestination.Home
                AppRoute.Library -> MainDestination.Library
                AppRoute.Search -> MainDestination.Search
                else -> null
            }
        } ?: MainDestination.Home
}

internal data class ContentBackSession(
    val originTopEntryId: Long,
    val origin: ContentNavigationSnapshot,
    val preview: ContentNavigationSnapshot,
    val phase: ContentBackPhase = ContentBackPhase.Tracking,
    val progress: Float = 0f,
    val swipeEdge: BackSwipeEdge = BackSwipeEdge.None,
)

internal enum class ContentBackPhase {
    Tracking,
    Cancelling,
    Committing,
}

internal enum class NavigationMotionKind {
    None,
    Forward,
    Backward,
    FadeThrough,
}

internal fun contentNavigationSnapshot(
    backStack: List<NavigationEntry>,
): ContentNavigationSnapshot {
    val contentStack = backStack.takeWhile { entry ->
        entry.route != AppRoute.Player && entry.route != AppRoute.Queue
    }
    return ContentNavigationSnapshot(
        currentEntry = contentStack.last(),
        contentStack = contentStack,
    )
}

internal fun resolveNavigationMotion(
    initial: ContentNavigationSnapshot,
    target: ContentNavigationSnapshot,
): NavigationMotionKind {
    if (initial.currentEntry.entryId == target.currentEntry.entryId) {
        return NavigationMotionKind.None
    }

    val initialRoute = initial.currentEntry.route
    val targetRoute = target.currentEntry.route
    if (
        initialRoute.isMainMotionRoute() ||
        targetRoute.isMainMotionRoute() ||
        kotlin.math.abs(initial.contentStack.size - target.contentStack.size) > 1
    ) {
        return NavigationMotionKind.FadeThrough
    }

    val initialRemainsInTarget = target.contentStack.any {
        it.entryId == initial.currentEntry.entryId
    }
    if (initialRemainsInTarget) return NavigationMotionKind.Forward

    val targetExistedInInitial = initial.contentStack.any {
        it.entryId == target.currentEntry.entryId
    }
    return if (targetExistedInInitial) {
        NavigationMotionKind.Backward
    } else {
        NavigationMotionKind.FadeThrough
    }
}

internal fun navigationContentTransform(
    initial: ContentNavigationSnapshot,
    target: ContentNavigationSnapshot,
): ContentTransform {
    val motionKind = resolveNavigationMotion(initial, target)
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
        targetContentZIndex = when (motionKind) {
            NavigationMotionKind.Backward -> -1f

            NavigationMotionKind.Forward,
            NavigationMotionKind.FadeThrough -> 1f

            NavigationMotionKind.None -> 0f
        },
        sizeTransform = SizeTransform(clip = false),
    )
}

internal fun predictiveBackContentTransform(
    swipeEdge: BackSwipeEdge,
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
        targetContentZIndex = -1f,
        sizeTransform = SizeTransform(clip = false),
    )
}

internal fun predictiveBackCancelContentTransform(): ContentTransform {
    return ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        targetContentZIndex = 1f,
        sizeTransform = SizeTransform(clip = false),
    )
}

private fun AppRoute.isMainMotionRoute(): Boolean {
    return this == AppRoute.Home || this == AppRoute.Library || this == AppRoute.Search
}

private val MotionEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val MotionExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
