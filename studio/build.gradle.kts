/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Lumen Studio: the Studio and the video editor of Lumen as a separate app, without NewPipe and YouTube.
// The Kotlin sources are the files of app/src/main/java/org/schabi/newpipe/lumen listed in shared-files.txt
// (copied at build time), plus src/main: Flavor (STUDIO = true), its own settings screen and stubs in place of
// the YouTube and analysis parts.

import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(21)
}

/** Copies the shared Lumen files into a generated source folder of this module. */
abstract class SharedLumenSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("org/schabi/newpipe/lumen")
        out.deleteRecursively()
        out.mkdirs()
        sources.files.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}

val lumenDir = rootProject.file("app/src/main/java/org/schabi/newpipe/lumen")
val sharedNames = file("shared-files.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
val sharedLumenSources = tasks.register<SharedLumenSources>("sharedLumenSources") {
    sources.from(sharedNames.map { lumenDir.resolve(it) })
    outputDir.set(layout.buildDirectory.dir("generated/lumenShared"))
}

configure<ApplicationExtension> {
    compileSdk {
        version = release(NEWPIPE_VERSION_SDK_COMPILE_MAJOR) {
            minorApiLevel = NEWPIPE_VERSION_SDK_COMPILE_MINOR
        }
    }
    namespace = "org.lumen.studio"

    defaultConfig {
        applicationId = "org.lumen.studio"
        resValue("string", "app_name", "Lumen Studio")
        minSdk {
            version = release(26)
        }
        targetSdk {
            version = release(NEWPIPE_VERSION_SDK_TARGET)
        }
        versionCode = System.getProperty("lumenBuild")?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        versionName = "1.0"
    }

    val lumenKeystore = System.getenv("LUMEN_KEYSTORE_FILE")
    signingConfigs {
        // the same fixed debug key as Lumen, so a newer CI build installs over the old one
        getByName("debug") {
            storeFile = rootProject.file("app/lumen-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (lumenKeystore != null) {
            create("lumenRelease") {
                storeFile = file(lumenKeystore)
                storePassword = System.getenv("LUMEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LUMEN_KEY_ALIAS")
                keyPassword = System.getenv("LUMEN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Lumen Studio Debug")
        }
        release {
            if (lumenKeystore != null) signingConfig = signingConfigs.getByName("lumenRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        encoding = "utf-8"
    }

    sourceSets {
        getByName("main") {
            // fonts of the captions and the catalogue of free AI models
            assets.directories += rootProject.file("app/src/main/assets").path
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    lint {
        abortOnError = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(sharedLumenSources, SharedLumenSources::outputDir)
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)
    implementation(libs.androidx.core)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.exoplayer.core)
    implementation(libs.google.exoplayer.datasource)
    implementation(libs.squareup.okhttp)
    implementation(libs.lottie)
}
