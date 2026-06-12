import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.2.0"
    application
    // Packaging spike (Step 0, app-packaging-plan §6): badass-runtime drives
    // jlink + jpackage. 2.0.0+ adds Gradle 9 compatibility (plan's 1.13.1 would
    // not configure under Gradle 9). Pinned 2.0.1 (current).
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // Launches the results-analysis app: ./gradlew :KSLAppSwingResults:run
    // or the green gutter arrow next to main() in KSLResultsApp.kt.
    mainClass.set("ksl.app.swing.results.KSLResultsAppKt")
}

kotlin {
    jvmToolchain(21)
}

// ── KSL app installer packaging (STANDARDIZED TEMPLATE) ──────────────────────
// Each app is its own standalone Gradle build (own settings.gradle.kts), so this
// block is copied verbatim into every app module; the ONLY per-app differences
// are `appImageName` below and the app's mainClass above. badass-runtime drives
// jlink + jpackage to emit a native installer with a bundled JRE (no Java needed).
runtime {
    // Correctness-first module set, identical for every app: java.se aggregates
    // the SE modules (Swing/java.desktop, java.sql, java.naming, java.management,
    // java.prefs, java.xml, java.datatransfer); the jdk.* additions cover
    // reflective paths jlink can miss — Postgres TLS (jdk.crypto.ec /
    // jdk.crypto.cryptoki), sun.misc.Unsafe (jdk.unsupported), and extra
    // charsets/locales. Trim later via suggestModules only if size matters.
    modules.set(listOf(
        "java.se",
        "jdk.crypto.ec",
        "jdk.crypto.cryptoki",
        "jdk.unsupported",
        "jdk.charsets",
        "jdk.localedata"
    ))
    jpackage {
        // >>> the only per-app value to change when copying this block <<<
        val appImageName = "KSL-Results"

        imageName = appImageName
        installerName = appImageName
        // jpackage requires a numeric MAJOR[.MINOR[.PATCH]]. CI passes the tag's
        // version via -PreleaseVersion; any '-rcN' suffix is stripped. Local
        // builds fall back to 1.0.0.
        appVersion = ((project.findProperty("releaseVersion") as String?)
            ?: "1.0.0").substringBefore("-")
        // One installer per OS (dmg / msi / deb) so users get a single obvious
        // download instead of dmg+pkg (mac) or msi+exe (Windows).
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
