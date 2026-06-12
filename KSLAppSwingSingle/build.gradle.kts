import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.2.0"
    application
    // badass-runtime drives jlink + jpackage for the native installer.
    id("org.beryx.runtime") version "2.0.1"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":KSLCore"))
    implementation(project(":KSLAppSwingCommon"))
    // KSLExamples is a TEST-ONLY dependency: the released app ships no
    // baked-in bundles (it discovers them from ~/.ksl/bundles/), but the
    // tests load the example bundles off the test classpath.  Keeping it
    // out of `implementation` keeps KSLExamples (and its dogfood bundles)
    // out of the distribution's lib/.
    testImplementation(project(":KSLExamples"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // Bundle-mode entry point for the released app: starts with no baked-in
    // model and discovers bundles the user installed into ~/.ksl/bundles/
    // (or loaded via Bundles → Load JAR…).  The M/M/1 demo (MM1SingleApp,
    // which uses KSLExamples) now lives in the test source set.
    mainClass.set("ksl.app.swing.single.BundleLaunchedSingleAppKt")
}

kotlin {
    jvmToolchain(21)
}

// ── KSL app installer packaging (standardized template; see KSLAppSwingResults
//    build.gradle.kts for the full rationale) ──────────────────────────────────
// Per-app self-contained (each app is its own standalone Gradle build). The only
// per-app value is `appImageName` below; the module set, version mapping, and
// installer-type logic are identical across all apps.
runtime {
    modules.set(listOf(
        "java.se",
        "jdk.crypto.ec",
        "jdk.crypto.cryptoki",
        "jdk.unsupported",
        "jdk.charsets",
        "jdk.localedata"
    ))
    jpackage {
        val appImageName = "KSL-Single"

        imageName = appImageName
        installerName = appImageName
        appVersion = ((project.findProperty("releaseVersion") as String?)
            ?: "1.0.0").substringBefore("-")
        installerType = when {
            OperatingSystem.current().isMacOsX  -> "dmg"
            OperatingSystem.current().isWindows -> "msi"
            else                                -> "deb"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
