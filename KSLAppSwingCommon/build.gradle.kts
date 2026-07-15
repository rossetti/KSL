plugins {
    kotlin("jvm") version "2.2.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Common is upstream of every Phase-6 Swing app.  It depends on the
    // substrate (KSLCore) and on kotlinx-coroutines-swing for EDT-aware
    // coroutine scopes.  It must NOT depend on KSLExamples or on any
    // app-specific module.
    implementation(project(":KSLCore"))
    // KSLApp hosts ksl.app.* (bundling/run/session/config), extracted from KSLCore.
    implementation(project(":KSLApp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // FlatLaf — IntelliJ-flavored look-and-feel.  Used by
    // ksl.app.swing.common.appearance.LookAndFeel to bootstrap the
    // four Phase-6 Swing apps with a consistent modern appearance.
    implementation("com.formdev:flatlaf:3.5.4")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// One shared runtime copy of the desktop icon family. Every Swing app already depends on
// this module, so embedding the PNGs here avoids duplicating them in all eight thin app JARs.
val desktopIconSizes = listOf(16, 24, 32, 48, 64, 128, 256, 512)
tasks.processResources {
    from(rootProject.file("distribution/icons/export")) {
        include(desktopIconSizes.map { size -> "*/*-$size.png" })
        into("ksl/app/icons")
    }
}
