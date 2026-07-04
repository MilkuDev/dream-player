package org.milkdev.dreamplayer.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
actual fun buildTextClipEntry(text: String): ClipEntry {
    return ClipEntry(StringSelection(text))
}