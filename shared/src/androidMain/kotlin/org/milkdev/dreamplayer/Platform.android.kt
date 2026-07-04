package org.milkdev.dreamplayer

import android.os.Build
// import android.app.Activity
// import androidx.compose.runtime.Composable
// import androidx.compose.runtime.SideEffect
// import androidx.compose.ui.platform.LocalView
// import androidx.core.view.WindowInsetsControllerCompat

class AndroidPlatform : Platform {
    override val name: String = "Android API ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

// @Composable
// actual fun SetSystemBarAppearance(isDark: Boolean) {
//     val view = LocalView.current
//     if (!view.isInEditMode) {
//         SideEffect {
//             val window = (view.context as Activity).window
//             val controller = WindowInsetsControllerCompat(window, view)
//             controller.isAppearanceLightStatusBars = !isDark
//         }
//     }
// }
actual fun SetSystemBarAppearance(isDark: Boolean) {
}