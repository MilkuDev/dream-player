import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "org.milkdev.dreamplayer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        androidResources {
            enable = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
            implementation(libs.room.ktx)
            implementation(libs.ktor.client.android)
        }
        commonMain.dependencies {
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.layout)
            implementation(libs.androidx.graphics.shapes)
            implementation(libs.wavy.slider)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.components.resources)
            implementation(libs.haze)
            implementation(libs.haze.materials)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.mp3spi)
            implementation(libs.flannel)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// Настройка генерации ресурсов Compose Multiplatform 1.10+
compose.resources {
    // publicResClass set to true makes the generated Res class public.
    // By default, the generated class is internal.
    publicResClass = true

    // packageOfResClass allows you to assign the generated Res class to a particular package
    // (to access within the code, as well as for isolation in a final artifact).
    // By default, Compose Multiplatform assigns the {group name}.{module name}.generated.resources package to the class.
    packageOfResClass = "org.milkdev.dreamplayer.generated.resources"

    // generateResClass set to always makes the project unconditionally generate the Res class.
    // This may be useful when the resource library is only available transitively.
    // By default, Compose Multiplatform uses the auto value to generate the Res class only if the
    // current project has an explicit implementation or api dependency on the resource library.
    generateResClass = always
}
