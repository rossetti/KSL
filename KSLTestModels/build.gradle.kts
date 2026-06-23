// Build file for KSLTestModels: shared test-fixture models for KSLCore/KSLApp test suites.
//
// This module exists to hold simulation models and bundle/session fixtures that the test
// suites need, so that KSLExamples can return to its original intent (book examples and
// demonstrations) as a pure sink that nothing depends on. KSLTestModels is consumed by
// other modules via `testImplementation(project(":KSLTestModels"))` and is NOT published.

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"   // NOT published to Maven

repositories {

    mavenCentral()
}

dependencies {

    // Provides KSLCore + KSLApp APIs to test-fixture code. The appsession/appsupport
    // fixtures reference ksl.app, which now lives in KSLApp; api so consumers
    // (KSLTesting) inherit both.
    api(project(":KSLCore"))
    api(project(":KSLApp"))

    // --- test suite (per-module; Phase 7) ---
    // KSLTestModels carries a tiny self-test of its own fixture bundles.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because the user can then provide their own logging specification
tasks.jar {
    exclude("logback.xml")
}

tasks.test {
    useJUnitPlatform()
}
