package org.milkdev.dreamplayer.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import org.milkdev.dreamplayer.navigation.MainTab
import org.milkdev.dreamplayer.navigation.NavigationCause
import org.milkdev.dreamplayer.navigation.NavigationOperation
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import org.milkdev.dreamplayer.navigation.toMainTabOrNull
import kotlin.math.roundToInt

@Stable
internal class MainTabCarouselState(
    initialTab: MainTab,
) {
    private val animatedPosition = Animatable(initialTab.position.toFloat())

    val position: Float
        get() = animatedPosition.value

    suspend fun settleTo(
        tab: MainTab,
        animated: Boolean,
    ) {
        val target = tab.position.toFloat()
        if (animated) {
            animatedPosition.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = MainCarouselDurationMillis,
                    easing = MotionEnterEasing,
                ),
            )
        } else {
            animatedPosition.snapTo(target)
        }
    }
}

@Composable
internal fun MainTabCarouselHost(
    state: MainTabCarouselState,
    activeTab: MainTab,
    animateActiveTab: Boolean,
    backSession: ContentBackSession?,
    modifier: Modifier = Modifier,
    content: @Composable (MainTab) -> Unit,
) {
    val parentExecutionPolicy = LocalSceneExecutionPolicy.current
    val carouselSession = backSession
        ?.takeIf { it.motionStyle == PredictiveBackMotionStyle.MainTabCarousel }
    val originTab = carouselSession?.origin?.currentEntry?.route?.toMainTabOrNull()
    val previewTab = carouselSession?.preview?.currentEntry?.route?.toMainTabOrNull()
    val settledPosition = if (animateActiveTab) {
        state.position
    } else {
        activeTab.position.toFloat()
    }

    LaunchedEffect(
        activeTab,
        animateActiveTab,
    ) {
        if (carouselSession == null) {
            state.settleTo(
                tab = activeTab,
                animated = animateActiveTab,
            )
        }
    }

    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            MainTab.entries.forEach { tab ->
                key(tab) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .then(
                                if (tab == activeTab && carouselSession == null) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics { }
                                },
                            ),
                    ) {
                        CompositionLocalProvider(
                            LocalSceneExecutionPolicy provides if (
                                tab == activeTab && carouselSession == null
                            ) {
                                parentExecutionPolicy
                            } else {
                                parentExecutionPolicy.restricted()
                            },
                        ) {
                            content(tab)
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints)
        }
        val predictiveOffsets = if (
            carouselSession != null &&
            originTab != null &&
            previewTab != null
        ) {
            predictiveCarouselOffsets(
                progress = carouselSession.progress,
                swipeEdge = carouselSession.swipeEdge,
                fullWidth = width,
                origin = carouselSession.origin,
                preview = carouselSession.preview,
            )
        } else {
            null
        }

        layout(width, height) {
            MainTab.entries.forEachIndexed { index, tab ->
                val x = when (tab) {
                    originTab -> predictiveOffsets?.originX
                    previewTab -> predictiveOffsets?.previewX
                    else -> null
                } ?: if (predictiveOffsets == null) {
                    ((tab.position - settledPosition) * width).roundToInt()
                } else {
                    width * (MainTab.entries.size + tab.position + 1)
                }
                placeables[index].place(x = x, y = 0)
            }
        }
    }
}

internal fun isDirectRootTabSwitch(
    transaction: NavigationTransaction?,
): Boolean {
    return transaction?.operation == NavigationOperation.MainSwitch &&
        transaction.cause == NavigationCause.Direct &&
        transaction.fromContentEntry.route.toMainTabOrNull() != null &&
        transaction.toContentEntry.route.toMainTabOrNull() != null
}

internal fun mainTabCarouselContentKey(
    frame: ContentTransitionFrame,
): Any {
    return if (frame.scene.currentEntry.route.toMainTabOrNull() != null) {
        MainTabCarouselContentKey
    } else {
        frame.scene.currentEntry.entryId
    }
}

internal object MainTabCarouselContentKey
