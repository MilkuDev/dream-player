package org.milkdev.dreamplayer

// import androidx.compose.runtime.Composable

class DesktopPlatform : Platform {
    override val name: String = "Desktop JVM (${System.getProperty("os.name")})"
}

actual fun getPlatform(): Platform = DesktopPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

// @Composable
// actual fun SetSystemBarAppearance(isDark: Boolean) { }