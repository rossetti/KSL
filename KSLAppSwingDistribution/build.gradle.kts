plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":KSLCore"))
    implementation(project(":KSLAppSwingCommon"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // Launches the distribution-fitting app: ./gradlew :KSLAppSwingDistribution:run
    // or the green gutter arrow next to main() in KSLDistributionApp.kt.
    mainClass.set("ksl.app.swing.dist.KSLDistributionAppKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Writes a sample SQLite database for manually testing the Database import card:
//   ./gradlew :KSLAppSwingDistribution:generateSampleDb
tasks.register<JavaExec>("generateSampleDb") {
    group = "application"
    description = "Writes a sample SQLite database for testing the Database import card."
    mainClass.set("ksl.app.swing.dist.tools.SampleFitDatabaseKt")
    classpath = sourceSets["main"].runtimeClasspath
}
