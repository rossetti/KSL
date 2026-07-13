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
    // KSLApp hosts the bundle-authoring + manifest infrastructure the Workbench
    // drives: ksl.app.bundle (BundleAuthoringSession/BundleAssembler/...),
    // ksl.app.config, ksl.app.validation, ksl.app.settings, ksl.app.session.
    implementation(project(":KSLApp"))
    // KSLAppSwingCommon hosts the shared appearance/theming, the launchKslSwingApp
    // bootstrap, and the reusable workspace/validation widgets the Workbench recomposes.
    implementation(project(":KSLAppSwingCommon"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // NOTE: KSLExamples / KSLTestModels are intentionally NOT test dependencies.
    // Their ServiceLoader bundle registrations would leak into BundleLoader's
    // discovery (which scans the parent classloader too), so openJar would find
    // example bundles in addition to the JAR under test. The tests build their own
    // fixture bundle JARs instead (see support/TestJarBuilder).
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // Launches the Bundle Workbench: ./gradlew :KSLAppSwingBundle:run
    // or the green gutter arrow next to main() in Main.kt.
    mainClass.set("ksl.app.swing.bundle.MainKt")
}

kotlin {
    jvmToolchain(21)
}

// ── KSL app installer packaging (standardized template; see KSLAppSwingResults
//    build.gradle.kts for the full rationale) ──────────────────────────────────
// Per-app self-contained. The only per-app value is `appImageName` below; the
// module set, version mapping, and installer-type logic are identical across apps.
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
        val appImageName = "KSL-Bundle"

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

// Doc tooling: capture real screenshots of the Bundle Workbench and its four tabs (Overview ·
// Bundle identity · Models · Catalog) to PNG — the full-window images in
// docs/guides/apps/bundle-workbench.md. Needs a real display, so run it under `xvfb-run`
// (NOT headless). Point -Pbuilders at a plain builders JAR (e.g. KSLExamples' book-builders.jar).
// Usage: xvfb-run -a ./gradlew :KSLAppSwingBundle:screenshotsBundle \
//          -Pbuilders=<book-builders.jar> [-Pout=<dir> -Pw=1040 -Ph=760]
tasks.register<JavaExec>("screenshotsBundle") {
    group = "documentation"
    description = "Capture real Bundle Workbench window screenshots (run under xvfb-run)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ksl.app.swing.bundle.CaptureBundleWindowsKt")
    jvmArgs("-Xmx4g")
    listOf("builders", "out", "w", "h").forEach { p ->
        if (project.hasProperty(p)) systemProperty(p, project.property(p)!!)
    }
}
