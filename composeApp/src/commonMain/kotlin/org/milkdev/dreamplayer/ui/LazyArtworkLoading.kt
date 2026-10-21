package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalLazyArtworkLoadingState = staticCompositionLocalOf<State<Boolean>> {
    mutableStateOf(true)
}

@Composable
internal fun rememberLazyArtworkLoadingState(
    listState: LazyListState,
    enabled: Boolean = true,
    idleDelayMillis: Long = 0L,
): State<Boolean> {
    return rememberUpdatedState(enabled)
}

@Composable
internal fun rememberLazyArtworkLoadingEnabled(
    listState: LazyListState,
    enabled: Boolean = true,
    idleDelayMillis: Long = 0L,
): Boolean {
    return enabled
}

@Composable
internal fun rememberLazyArtworkLoadingState(
    gridState: LazyGridState,
    enabled: Boolean = true,
    idleDelayMillis: Long = 0L,
): State<Boolean> {
    return rememberUpdatedState(enabled)
}

@Composable
internal fun rememberLazyArtworkLoadingEnabled(
    gridState: LazyGridState,
    enabled: Boolean = true,
    idleDelayMillis: Long = 0L,
): Boolean {
    return enabled
}
