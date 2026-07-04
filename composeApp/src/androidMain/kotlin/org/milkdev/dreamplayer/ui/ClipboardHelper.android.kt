package org.milkdev.dreamplayer.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

actual fun buildTextClipEntry(text: String): ClipEntry {
    return ClipEntry(ClipData.newPlainText("Log", text))
}