package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun rememberLazyArtworkLoadingEnabled(
    listState: LazyListState,
    idleDelayMillis: Long = 0L,
): Boolean {
    var canLoadArtwork by remember { mutableStateOf(true) }

    LaunchedEffect(listState, idleDelayMillis) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { isScrolling ->
                if (isScrolling) {
                    canLoadArtwork = false
                } else {
                    delay(idleDelayMillis.milliseconds)
                    canLoadArtwork = true
                }
            }
    }

    return canLoadArtwork
}

@Composable
internal fun rememberLazyArtworkLoadingEnabled(
    gridState: LazyGridState,
    idleDelayMillis: Long = 0L,
): Boolean {
    var canLoadArtwork by remember { mutableStateOf(true) }

    LaunchedEffect(gridState, idleDelayMillis) {
        snapshotFlow { gridState.isScrollInProgress }
            .collectLatest { isScrolling ->
                if (isScrolling) {
                    canLoadArtwork = false
                } else {
                    delay(idleDelayMillis.milliseconds)
                    canLoadArtwork = true
                }
            }
    }

    return canLoadArtwork
}
