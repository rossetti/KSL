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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
