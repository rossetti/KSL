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
    // KSLExamples is a TEST-ONLY dependency: the released app ships no
    // baked-in bundles (it discovers them from ~/.ksl/bundles/), but the
    // tests load the example bundles off the test classpath.  Keeping it
    // out of `implementation` keeps KSLExamples (and its dogfood bundles)
    // out of the distribution's lib/.
    testImplementation(project(":KSLExamples"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // Bundle-mode entry point for the released app: starts with no baked-in
    // model and discovers bundles the user installed into ~/.ksl/bundles/
    // (or loaded via Bundles → Load JAR…).  The M/M/1 demo (MM1SingleApp,
    // which uses KSLExamples) now lives in the test source set.
    mainClass.set("ksl.app.swing.single.example.BundleLaunchedSingleAppKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
