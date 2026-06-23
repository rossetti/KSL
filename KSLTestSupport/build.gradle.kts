// Build file for KSLTestSupport: shared JUnit test infrastructure for the per-module test suites.
//
// Distinct from KSLTestModels (which holds simulation-model fixtures): this module holds
// test-framework helpers — custom JUnit conditions/extensions and the like — that more than one
// module's test source set needs. It is consumed via `testImplementation(project(":KSLTestSupport"))`
// and is NOT published. JUnit is `api` here so the annotations/extensions are usable by consumers.

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"   // NOT published to Maven

repositories {

    mavenCentral()
}

dependencies {

    // Exposed so consumers' test code can use the JUnit extension API these helpers build on.
    api("org.junit.jupiter:junit-jupiter:5.11.0")
}

kotlin {
    jvmToolchain(21)
}
