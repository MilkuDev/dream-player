import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.AZUL)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "org.milkdev.dreamplayer.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Exe,
                TargetFormat.Msi,
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "DreamPlayer"
            packageVersion = "1.0.0"
            description = "DreamPlayer - Kotlin Desktop Player"
            copyright = "2026 Milku"

            windows {
//                iconFile.set(project.file("src/main/resources/icons/icon.ico"))
                perUserInstall = true
                menu = true
                shortcut = true
            }

            macOS {
                bundleID = "org.milkdev.dreamplayer"
                dockName = "DreamPlayer"
            }
        }
    }
}
