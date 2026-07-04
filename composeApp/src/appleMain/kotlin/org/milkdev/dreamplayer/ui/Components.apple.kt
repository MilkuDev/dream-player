package org.milkdev.dreamplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.DrawableResource

@Composable
actual fun TrackImage(
    uri: String?,
    modifier: Modifier,
    contentDescription: String?,
    fallbackIcon: DrawableResource,
    maxDecodeSizePx: Int,
    loadUncached: Boolean
) {
}