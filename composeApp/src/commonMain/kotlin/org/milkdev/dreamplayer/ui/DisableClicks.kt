package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

fun Modifier.disableClicks() =
    clickable(enabled = false) {}
