package org.milkdev.dreamplayer.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.milkdev.dreamplayer.app.BackSwipeEdge
import org.milkdev.dreamplayer.app.PlatformBackEvent

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBackStarted: () -> Unit,
    onBackProgressed: (PlatformBackEvent) -> Unit,
    onBackCancelled: () -> Unit,
    onBackCommitted: (hadProgress: Boolean) -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { progress ->
        var hadProgress = false
        onBackStarted()
        try {
            progress.collect { event ->
                hadProgress = true
                onBackProgressed(event.toPlatformBackEvent())
            }
            onBackCommitted(hadProgress)
        } catch (cancellation: CancellationException) {
            onBackCancelled()
            throw cancellation
        }
    }
}

private fun BackEventCompat.toPlatformBackEvent(): PlatformBackEvent {
    return PlatformBackEvent(
        progress = progress.coerceIn(0f, 1f),
        swipeEdge = when (swipeEdge) {
            BackEventCompat.EDGE_LEFT -> BackSwipeEdge.Left
            BackEventCompat.EDGE_RIGHT -> BackSwipeEdge.Right
            else -> BackSwipeEdge.None
        },
    )
}
