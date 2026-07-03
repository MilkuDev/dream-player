plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "org.milkdev.dreamplayer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.milkdev.dreamplayer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-alpha1"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "myKey"
            val keystoreFile = rootProject.file(keystorePath)

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "ТВОЙ_ПАРОЛЬ_ЛОКАЛЬНО"
                keyAlias = System.getenv("KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "ТВОЙ_ПАРОЛЬ_ЛОКАЛЬНО"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    // Optional but recommended for clean build setups
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false

            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "myKey"
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
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}