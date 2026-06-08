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

// --- Step 0 packaging spike (app-packaging-plan §4.2/§4.3) -------------------
// Correctness-first, generous module set: java.se aggregates the SE modules
// (Swing/java.desktop, java.sql, java.naming, java.management, java.prefs,
// java.xml, java.datatransfer). The jdk.* additions cover reflective paths
// jlink can miss: TLS for Postgres (jdk.crypto.ec / jdk.crypto.cryptoki),
// sun.misc.Unsafe (jdk.unsupported), and extra charsets/locales (CSV + Excel
// via fastexcel — note: NOT POI's jdk.zipfs anymore). Trim later (§6 step 5).
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
        imageName = "KSL-Results"
        installerName = "KSL-Results"
        // jpackage requires numeric MAJOR[.MINOR[.PATCH]] — the project version
        // "1.0-SNAPSHOT" would be rejected. Hard-coded for the spike; the real
        // tag->version mapping (§4.4) comes with the convention plugin.
        appVersion = "1.0.0"
    }
}

tasks.test {
    useJUnitPlatform()
}
