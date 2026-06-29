package org.milkdev.dreamplayer

import androidx.compose.runtime.Composable

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect fun currentTimeMillis(): Long

@Composable
expect fun SetSystemBarAppearance(isDark: Boolean)