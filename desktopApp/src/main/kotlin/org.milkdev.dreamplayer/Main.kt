package org.milkdev.dreamplayer

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.milkdev.dreamplayer.app.App
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState(width = 400.dp, height = 800.dp)

    LaunchedEffect(windowState.placement) {
        if (windowState.placement == WindowPlacement.Maximized) {
            windowState.placement = WindowPlacement.Floating
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "DreamPlayer",
        resizable = false,
        state = windowState
    ) {
        App()
    }
}