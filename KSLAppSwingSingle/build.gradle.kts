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
    // KSLExamples hosts the KSLModelBundle implementations for the
    // bundled example models (MM1Bundle, LKInventoryBundle wrapping
    // GIGcQueue and LKInventoryModel) — these are reference models,
    // not engine internals.
    implementation(project(":KSLExamples"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // Points at the example app's main() so :KSLAppSwingSingle:run launches
    // the M/M/1 demo against the new kslSingleApp framework.  Real developers
    // ship their own main() that calls kslSingleApp(appName) { modelBuilder(...) };
    // this entry exists only as a smoke-test for Phase 6D.
    mainClass.set("ksl.app.swing.single.example.MM1SingleAppKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
