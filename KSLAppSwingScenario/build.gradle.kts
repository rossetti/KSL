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
    // KSLAppSwingCommon supplies the shared widgets the Scenario app
    // reuses verbatim: ConfigurationEditorState + the per-scenario
    // editor panels, ConsoleLogPanel + drawer, workspace status bar,
    // notifications, validation banner, etc.
    implementation(project(":KSLAppSwingCommon"))
    // KSLExamples is a TEST-ONLY dependency: the released app ships no
    // baked-in bundles (it discovers them from ~/.ksl/bundles/), but the
    // tests load the example bundles off the test classpath.  Keeping it
    // out of `implementation` keeps KSLExamples out of the distribution.
    testImplementation(project(":KSLExamples"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass.set("ksl.app.swing.scenario.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
