plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":KSLCore"))
    implementation(project(":KSLExamples"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")

    // JSL dependencies for migration/comparison testing
    testImplementation("io.github.rossetti:JSLCore:R1.0.12")
    testImplementation("io.github.rossetti:JSLExtensions:R1.0.12")
}

tasks.test {
    useJUnitPlatform {
        if (project.hasProperty("includeSlow")) {
            includeTags("slow")
        } else {
            excludeTags("slow")
        }
    }
}

kotlin {
    jvmToolchain(21)
}
