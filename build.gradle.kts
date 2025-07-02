
plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("org.jetbrains.dokka") version "2.0.0"
}

group = "io.github.rossetti"
version = "R1.2.1"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":KSLCore"))
    api(project(":KSLExamples"))
}

kotlin {
    jvmToolchain(21)
}