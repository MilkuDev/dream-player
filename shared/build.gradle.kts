plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}


val generateAppConfigTask = tasks.register<GenerateAppConfigTask>("generateAppConfig") {
    description = "Generate AppConfig with versioning info"

    versionName.set(libs.versions.app.version.name)
    versionCode.set(libs.versions.app.version.code)
    desktopVersionName.set(libs.versions.app.desktop.version)

    outputDirectory.set(project.layout.buildDirectory.dir("generated/appConfig/commonMain/kotlin"))
}

kotlin {
    android {
        namespace = "org.milkdev.dreamplayer.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    configure(listOf(iosArm64(), iosSimulatorArm64(), macosArm64())) {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.androidx.datastore.preferences)
                implementation(libs.room.runtime)
                implementation(libs.sqlite.bundled)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
            kotlin.srcDir(generateAppConfigTask.map { it.outputs.files.singleFile })
        }
        androidMain.dependencies {
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
            implementation(libs.room.ktx)
            implementation(libs.ktor.client.android)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        appleTest.dependencies {
            // TODO: not implemented yet
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.mp3spi)
            implementation(libs.flannel)
            implementation(libs.kotlinx.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspMacosArm64", libs.room.compiler)
}

abstract class GenerateAppConfigTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val desktopVersionName: Property<String>

    @get:Input
    abstract val versionCode: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val configFile = outputDirectory.get().file("org/milkdev/dreamplayer/shared/AppConfig.kt").asFile
        configFile.parentFile.mkdirs()
        configFile.writeText("""
            package org.milkdev.dreamplayer.shared

            object AppConfig {
                const val VERSION_NAME = "${versionName.get()}"
                const val VERSION_CODE = ${versionCode.get()}
                const val DESKTOP_VERSION_NAME = "${desktopVersionName.get()}"
            }
        """.trimIndent())
    }
}