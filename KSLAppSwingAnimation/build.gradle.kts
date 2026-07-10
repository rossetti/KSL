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
    implementation(project(":KSLCore"))            // ksl.animation (event model, layout, sinks, emitters)
    implementation(project(":KSLApp"))             // ksl.app.* session/config/editor/bundle substrate
    implementation(project(":KSLAppSwingCommon"))  // theming, BundleModelPickerDialog, workspace, editor panels
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Test fixtures (example models) live in KSLTestModels; the released app ships no
    // baked-in models — it discovers bundles from the workspace bundles dir (KSLWork/bundles) at runtime.
    testImplementation(project(":KSLTestModels"))
    testImplementation(project(":KSLTestSupport"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // The animation authoring/playback app in bundle-picker mode (Capture · Run · Layout · Replay),
    // matching the other KSL apps: starts with no baked-in model and discovers bundles the user
    // installed into their KSLWork/bundles folder (or loaded via Open Model… → Load JAR…).
    mainClass.set("ksl.app.swing.animation.app.MainKt")
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
        val appImageName = "KSL-Animation"

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

// Doc tooling: render real animation frames from a captured `.atf` trace through the app's own
// SimulationCanvas, headless — used to produce the images in docs/guides/apps/animation.md.
// Usage: ./gradlew :KSLAppSwingAnimation:renderFrames -Ptrace=<run.atf> [-Pframes=N -Pout=<dir> -Pw=1200 -Ph=820]
tasks.register<JavaExec>("renderFrames") {
    group = "documentation"
    description = "Render animation frames from a captured .atf trace (docs/guides/apps/animation.md visuals)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ksl.app.swing.animation.examples.RenderFramesKt")
    jvmArgs("-Xmx4g", "-Djava.awt.headless=true")
    listOf("trace", "frames", "out", "w", "h").forEach { p ->
        if (project.hasProperty(p)) systemProperty(p, project.property(p)!!)
    }
}
