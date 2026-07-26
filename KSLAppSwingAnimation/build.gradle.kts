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
    // baked-in models — it discovers bundles from the workspace bundles dir (KSLWork/bundles) at runtime.
    testImplementation(project(":KSLTestModels"))
    testImplementation(project(":KSLTestSupport"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    // The animation authoring/playback app in bundle-picker mode (Capture · Run · Layout · Replay),
    // matching the other KSL apps: starts with no baked-in model and discovers bundles the user
    // installed into their KSLWork/bundles folder (or loaded via Open Model… → Load JAR…).
    mainClass.set("ksl.app.swing.animation.app.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Doc tooling: render real animation frames from a captured `.atf` trace through the app's own
// SimulationCanvas, headless — used to produce the canvas images in docs/guides/apps/animation.md.
// Usage: ./gradlew :KSLAppSwingAnimation:renderFrames -Ptrace=<run.atf> [-PlayoutFile=<run.lay.json>
//          -Pframes=N -Pout=<dir> -Pw=1200 -Ph=820]
tasks.register<JavaExec>("renderFrames") {
    group = "documentation"
    description = "Render animation frames from a captured .atf trace (docs/guides/apps/animation.md visuals)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ksl.app.swing.animation.examples.RenderFramesKt")
    jvmArgs("-Xmx4g", "-Djava.awt.headless=true")
    listOf("trace", "frames", "out", "w", "h").forEach { p ->
        if (project.hasProperty(p)) systemProperty(p, project.property(p)!!)
    }
    // The renderer accepts an authored layout, but it cannot be passed as -Playout: Gradle's Project
    // already owns a `layout` property, so hasProperty("layout") is always true and the value that
    // arrives is a ProjectLayout. Hence -PlayoutFile, mapped to the system property the tool reads.
    // Without a layout the tool falls back to an auto-generated one, which places elements differently
    // and assigns its own colours -- fine for a quick look, misleading for comparing renderers.
    if (project.hasProperty("layoutFile")) systemProperty("layout", project.property("layoutFile")!!)
}

// Doc tooling: capture real screenshots of the app window and its four tabs (Capture · Run · Layout ·
// Replay) to PNG — the full-window images in docs/guides/apps/animation.md. Needs a real display, so
// run it under `xvfb-run` (NOT headless). Pass an optional -Ptrace=<run.atf> to also grab the Replay
// tab with a trace loaded and advanced to a mid-playback frame (entities in motion).
// Usage: xvfb-run -a ./gradlew :KSLAppSwingAnimation:screenshotsAnimation \
//          -Pbundle=<book-examples.jar> [-PmodelId=… -Ptrace=<run.atf> -Pout=<dir> -Pw=1280 -Ph=860]
tasks.register<JavaExec>("screenshotsAnimation") {
    group = "documentation"
    description = "Capture real app-window screenshots of the four tabs (run under xvfb-run)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ksl.app.swing.animation.examples.CaptureWindowsKt")
    jvmArgs("-Xmx4g")
    listOf("bundle", "bundleId", "modelId", "trace", "out", "w", "h").forEach { p ->
        if (project.hasProperty(p)) systemProperty(p, project.property(p)!!)
    }
}

