import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "org.milkdev.dreamplayer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.milkdev.dreamplayer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.app.version.code.get().toInt()
        versionName = libs.versions.app.version.name.get()
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "../myKey"
            val keystoreFile = rootProject.file(keystorePath)

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: localProperties.getProperty("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: localProperties.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false

            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "../myKey"
            if (rootProject.file(keystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
                logger.warn("Release keystore file not found! Falling back to debug signing configuration.")
            }
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":shared"))
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}