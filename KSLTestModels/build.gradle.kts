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

    // Provides KSLCore (and, once Phase 6 lands, KSLApp) APIs to test-fixture code.
    // Note: the Bucket B appsession/appsupport fixtures reference ksl.app, so until
    // Phase 6 extracts KSLApp those symbols resolve transitively through KSLCore
    // (ksl.app still lives in KSLCore on this branch). After Phase 6, add the explicit
    // KSLApp dependency below.
    api(project(":KSLCore"))
    // api(project(":KSLApp"))   // uncomment after Phase 6 (KSLApp extraction)
}

kotlin {
    jvmToolchain(21)
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because the user can then provide their own logging specification
tasks.jar {
    exclude("logback.xml")
}
