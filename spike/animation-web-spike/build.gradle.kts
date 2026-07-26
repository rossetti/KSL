/*
 * PHASE S SPIKE — DISPOSABLE. See settings.gradle.kts for why this build exists.
 *
 * Kotlin/JS (IR) targeting the browser, matching the Kotlin and kotlinx-serialization versions the
 * KSL repo already uses (Kotlin 2.2.0, serialization 1.9.0) so that findings transfer to Phase 1.
 * Note the dependency is `kotlinx-serialization-json` (platform-agnostic), NOT the `-jvm` artifact
 * KSLCore declares — this is gotcha G7 in practice.
 */

plugins {
    kotlin("multiplatform") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

repositories {
    mavenCentral()
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "spike.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
    }
}
