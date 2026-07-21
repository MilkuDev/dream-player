package org.milkdev.dreamplayer.ui

import androidx.compose.runtime.Composable

@Composable
internal expect fun PlatformBackHandler(
    enabled: Boolean = true,
    onBackStarted: () -> Unit,
    onBackProgressed: (org.milkdev.dreamplayer.app.PlatformBackEvent) -> Unit,
    onBackCancelled: () -> Unit,
    onBackCommitted: (hadProgress: Boolean) -> Unit,
)
