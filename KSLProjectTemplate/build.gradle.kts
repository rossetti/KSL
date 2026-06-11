
// An example gradle build file for a project that depends on the KSL

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
}

repositories {

    mavenCentral()
}

dependencies {

    // next line allows use of KSL libraries within the project
    // update the release number when new releases become available
    api("io.github.rossetti:KSLCore:R1.3")
    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

kotlin {
    jvmToolchain(21)
}
