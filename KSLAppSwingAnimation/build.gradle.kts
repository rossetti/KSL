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
    implementation(project(":KSLCore"))            // ksl.animation (event model, layout, sinks, emitters)
    implementation(project(":KSLApp"))             // ksl.app.* session/config/editor/bundle substrate
    implementation(project(":KSLAppSwingCommon"))  // theming, BundleModelPickerDialog, workspace, editor panels
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Test fixtures (example models) live in KSLTestModels; the released app ships no
    // baked-in models — it discovers bundles from ~/.ksl/bundles/ at runtime.
    testImplementation(project(":KSLTestModels"))
    testImplementation(project(":KSLTestSupport"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // The animation authoring/playback app in bundle-picker mode (Capture · Run · Layout · Replay),
    // matching the other KSL apps: starts with no baked-in model and discovers bundles the user
    // installed into ~/.ksl/bundles/ (or loaded via Open Model… → Load JAR…).
    mainClass.set("ksl.app.swing.animation.app.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
