import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Astrion TV Helper (1.3.6): a small app for the Android TV itself (NVIDIA SHIELD…). It tells the
// remote what's playing (title, artwork, position: the Android TV Remote protocol only says which
// app is in front) and opens apps, which Google's protocol no longer reliably does. No libraries.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.custom.astrion.tvhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.custom.astrion.tvhelper"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

ktlint {
    android.set(true)
    verbose.set(true)
    outputToConsole.set(true)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
