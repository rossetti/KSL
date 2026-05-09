// Build file for KSLTesting: integration test suite for KSLCore.

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("org.jetbrains.dokka") version "2.1.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {

    mavenCentral()
}

dependencies {

    // depends on KSLCore as an internal project in multi-project build
    // this permits changes to the KSLCore to be immediately reflected in KSLExtensions
    api(project(":KSLCore"))
    api(project(":KSLExamples"))

    implementation("org.junit.jupiter:junit-jupiter:5.9.0")

    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

tasks.test {
    useJUnitPlatform()
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because the user can then provide their own logging specification
tasks.jar {
    exclude("logback.xml")
}

kotlin {
    jvmToolchain(21)
}