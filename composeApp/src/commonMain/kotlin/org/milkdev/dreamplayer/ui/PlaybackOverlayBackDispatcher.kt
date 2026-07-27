package org.milkdev.dreamplayer.ui

import org.milkdev.dreamplayer.app.AppBackGesture
import org.milkdev.dreamplayer.app.PlatformBackEvent

/**
 * Common callback bridge from the app-level platform Back handler to the
 * Player/Queue presentation owned by [PlayerOverlayHost].
 */
internal class PlaybackOverlayBackDispatcher {
    private var onStarted: (AppBackGesture) -> Boolean = { false }
    private var onProgressed: (PlatformBackEvent) -> Unit = {}
    private var onCancelled: () -> Unit = {}
    private var onCommitted: (Boolean) -> Unit = {}

    fun bind(
        onStarted: (AppBackGesture) -> Boolean,
        onProgressed: (PlatformBackEvent) -> Unit,
        onCancelled: () -> Unit,
        onCommitted: (hadProgress: Boolean) -> Unit,
    ) {
        this.onStarted = onStarted
        this.onProgressed = onProgressed
        this.onCancelled = onCancelled
        this.onCommitted = onCommitted
    }

    fun start(gesture: AppBackGesture): Boolean = onStarted(gesture)

    fun progress(event: PlatformBackEvent) = onProgressed(event)

    fun cancel() = onCancelled()

    fun commit(hadProgress: Boolean) = onCommitted(hadProgress)
}
