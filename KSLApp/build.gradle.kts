// Build file for KSLApp: the application-services layer (ksl.app.*).
//
// Extracted from KSLCore so that the published KSLCore library carries only the
// simulation engine, while the bundling / run / orchestration / session / config
// and distribution-app infrastructure lives here. KSLApp is an INTERNAL module
// (not published to Maven, per decision D1): it is consumed by the KSLAppSwing*
// desktop apps, KSLBundleTools, and the test/fixture modules.
//
// Dependencies: everything ksl.app needs (kotlin-logging, kotlinx-datetime/
// -serialization/-coroutines, lets-plot, dataframe, hipparchus, tomlkt) is a
// KSLCore `api` dependency, inherited transitively through api(project(":KSLCore")).

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"   // NOT published to Maven (D1)

repositories {

    mavenCentral()
}

dependencies {

    // api so that modules depending on KSLApp also see KSLCore (and its api deps:
    // kotlin-logging, kotlinx-datetime/-serialization/-coroutines, lets-plot,
    // dataframe, hipparchus, tomlkt, ...).
    api(project(":KSLCore"))

    // --- test suite (per-module; Phase 7) ---
    testImplementation(project(":KSLTestModels"))
    testImplementation(project(":KSLTestSupport"))
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
