package org.milkdev.dreamplayer

// import androidx.compose.runtime.Composable

class DesktopPlatform : Platform {
    override val name: String = "Desktop JVM (${System.getProperty("os.name")})"
}

actual fun getPlatform(): Platform = DesktopPlatform() // TODO: something

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun setSystemBarAppearance(isDark: Boolean) {

}