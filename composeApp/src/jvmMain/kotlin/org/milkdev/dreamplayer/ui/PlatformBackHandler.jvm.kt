package org.milkdev.dreamplayer.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBackStarted: () -> Unit,
    onBackProgressed: (org.milkdev.dreamplayer.app.PlatformBackEvent) -> Unit,
    onBackCancelled: () -> Unit,
    onBackCommitted: (hadProgress: Boolean) -> Unit,
) = Unit
