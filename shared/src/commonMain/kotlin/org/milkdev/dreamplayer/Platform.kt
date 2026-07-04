package org.milkdev.dreamplayer


interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect fun currentTimeMillis(): Long

expect fun SetSystemBarAppearance(isDark: Boolean)
