package org.milkdev.dreamplayer.ui

import androidx.compose.ui.platform.ClipEntry

// Ожидаем платформенную реализацию создания элемента буфера обмена
expect fun buildTextClipEntry(text: String): ClipEntry