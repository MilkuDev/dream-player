package org.milkdev.dreamplayer.database

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.io.path.createDirectories

val applicationDirectory: String = run {
    val appName = "DreamPlayer"
    val os = System.getProperty("os.name")
        .lowercase(Locale.ROOT)

    val baseDir: Path = when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")
                ?: error("APPDATA is not set")

            Paths.get(appData, appName)
        }

        os.contains("mac") || os.contains("darwin") -> {
            Paths.get(
                System.getProperty("user.home"),
                "Library",
                "Application Support",
                appName
            )
        }

        else -> {
            val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")

            if (!xdgConfigHome.isNullOrBlank()) {
                Paths.get(xdgConfigHome, appName)
            } else {
                Paths.get(
                    System.getProperty("user.home"),
                    ".config",
                    appName
                )
            }
        }
    }

    baseDir.createDirectories()

    baseDir.toString()
}
