/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


plugins {
    kotlin("jvm") version "2.2.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Isolated classpath for running the kslpkg bundle assembler from a task, without
// putting KSLBundleTools on KSLExamples' compile/test classpath (KSLExamples stays a sink).
val kslpkgClasspath by configurations.creating

dependencies {
    implementation(project(":KSLCore"))
    // KSLApp hosts ksl.app.* (bundling/run/session), extracted from KSLCore.
    implementation(project(":KSLApp"))

    // KSLExamples carries a small self-test suite that verifies its own
    // content (e.g. BookExamplesBundle ServiceLoader discovery + model
    // build/run). It depends on nothing but KSLCore, so KSLExamples remains
    // a sink that no other module depends on.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation(kotlin("test"))

    add("kslpkgClasspath", project(":KSLBundleTools"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Produces the slim, distributable "KSL Book Examples" bundle JAR — a manifest
// bundle meant to be dropped into the workspace bundles folder (or loaded via
// Bundles -> Load JAR...).  It is assembled by `kslpkg assemble` from a plain
// builders JAR holding only the two class packages that make up the bundle's
// closure:
//   - the curated book models + builders (package ksl.examples.general.bookbundle), and
//   - the reused two-echelon inventory closure (package ksl.examples.general.models.inventory).
// It deliberately does NOT bundle KSLCore: a bundle JAR is loaded under the host
// app's classloader, which already provides KSLCore.
//
//     ./gradlew :KSLExamples:bookExamplesBundleJar
//     -> KSLExamples/build/libs/book-examples.jar

// Step 1: the plain builders JAR (the input to kslpkg assemble).
tasks.register<Jar>("bookBuildersJar") {
    description = "Plain builders JAR for the book-examples bundle (input to kslpkg assemble)."
    archiveBaseName.set("book-builders")
    archiveVersion.set("")
    dependsOn(tasks.named("classes"))
    from(sourceSets["main"].output) {
        include("ksl/examples/general/bookbundle/**")
        include("ksl/examples/general/models/inventory/**")
    }
}

// Step 2: assemble the builders JAR into a manifest bundle (embeds each model's
// descriptor.json by building it once during assembly), via the kslpkg CLI.
tasks.register<JavaExec>("bookExamplesBundleJar") {
    group = "ksl bundle"
    description = "Assemble the slim KSL Book Examples manifest bundle JAR (kslpkg assemble)."
    dependsOn("bookBuildersJar")
    classpath = kslpkgClasspath
    mainClass.set("ksl.bundle.tools.MainKt")
    args(
        "assemble", layout.buildDirectory.file("libs/book-builders.jar").get().asFile.path,
        "--id", "edu.uark.ksl.book-examples",
        "--name", "KSL Book Examples",
        "--version", "1.0.0",
        // BuildTwoEchelonModel is the shared closure that TwoEchelonInventory delegates to;
        // it is embedded for runtime but must not surface as a 17th (uncurated) model.
        "--exclude", "BuildTwoEchelonModel",
        "--description", "Curated, decision-relevant simulation models from the KSL book " +
            "(chapters 4 through 8), each with an authored catalog of headline inputs and outputs, " +
            "ready to run in the KSL apps.",
        "-o", layout.buildDirectory.file("libs/book-examples.jar").get().asFile.path,
        "--force",
    )
}

// ── Animation Examples bundle (the worked animation gallery models) ───────────
// Mirrors bookExamplesBundleJar: a plain builders JAR (the ModelBuilderIfc
// wrappers + the example models' class closure) assembled by `kslpkg assemble`
// into a manifest bundle.  Dropped into the user's KSLWork/bundles folder, it
// makes every animation example pickable in the apps' Open Model… picker.
//
//     ./gradlew :KSLExamples:animationExamplesBundleJar
//     -> KSLExamples/build/libs/animation-examples.jar   (drop into KSLWork/bundles)
tasks.register<Jar>("animationBuildersJar") {
    description = "Plain builders JAR for the animation-examples bundle (input to kslpkg assemble)."
    archiveBaseName.set("animation-builders")
    archiveVersion.set("")
    dependsOn(tasks.named("classes"))
    from(sourceSets["main"].output) {
        // One include per model class the builders reach. A builder whose model is missing here ships as a
        // bundle entry that cannot be built, and nothing at compile time says so -- the jar is assembled from
        // paths, not from the call graph. AnimationBundleClosureTest is what catches it.
        include("ksl/examples/general/animationbundle/**")          // the builders, the example objects, the inline
                                                                    // AnnotatedClinic model, and the animation copies
                                                                    // under animationbundle/models
        include("ksl/examples/general/agent/**")                    // agent models (epidemic, crowd, flocking, AGV, drone)
        include("ksl/examples/book/chapter6/DriveThroughPharmacy*")             // Example 01
        // Named for the CLASS, not the file that declares it: Ch7Example2.kt compiles to
        // TandemQueueWithBlocking.class. An include matching the file name packages nothing.
        include("ksl/examples/book/chapter7/TandemQueueWithBlocking*")           // Example 17
        include("ksl/examples/book/chapter8/TandemQueueWithConveyors*")         // Example 08
        include("ksl/examples/book/chapter8/TandemQueueWithUnconstrainedMovement*") // Example 09
        include("ksl/examples/book/chapter8/TestAndRepairShopWithMovableResources*") // Example 13
        include("ksl/examples/book/chapter8/TestAndRepairShopWithConveyor*")    // Example 18
    }
}

// The bundle jar is assembled from path patterns, so a model class that no pattern matches is packaged
// missing and nothing says so until a user opens it. AnimationBundleClosureTest loads every builder out of
// this jar to prove otherwise, which means the jar has to exist when tests run.
tasks.named<Test>("test") {
    dependsOn("animationBuildersJar")
    val jar = tasks.named<Jar>("animationBuildersJar").flatMap { it.archiveFile }
    inputs.file(jar)
    doFirst { systemProperty("animationBundleJar", jar.get().asFile.absolutePath) }
}

// Convert the polish scripts' output into the .lay.toml layouts that ship with the suite, keyed
// <bundleId>/<modelId> so the animation app can find the one for the model a student has open.
// The bundle manifest is the authority for which models need one, so a model added without a layout
// fails here rather than shipping without.
// Usage: ./gradlew :KSLExamples:publishAnimationLayouts
tasks.register<JavaExec>("publishAnimationLayouts") {
    group = "documentation"
    description = "Publish the polished animation layouts as .lay.toml, keyed by bundle and model id."
    dependsOn("animationExamplesBundleJar", "classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ksl.examples.general.animationbundle.showcase.LayoutPublisherKt")
    workingDir = rootDir
    systemProperty("bundleJar", layout.buildDirectory.file("libs/animation-examples.jar").get().asFile.path)
    systemProperty("polished", rootDir.resolve("build/showcase/polished").path)
    systemProperty("out", rootDir.resolve("docs/animations/layouts").path)
}

// Build the downloadable animation pack: one playable page per bundled model, plus an index. Ships as
// its own release asset, not inside the suite, so the install stays lean and the animations are opt-in.
// Needs the browser player, which lives in the standalone KSLAnimationCore build:
//   ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack
// Traces are captured automatically for any model that has none.
// Usage: ./gradlew :KSLExamples:buildAnimationsPack
tasks.register<JavaExec>("buildAnimationsPack") {
    group = "distribution"
    description = "Build the self-contained animation pages for the ksl-animations release asset."
    // Not publishAnimationLayouts: that regenerates the layouts from the polish scripts' output, which is
    // not in the repository. The pack uses the committed .lay.toml files, so a fresh clone can build it.
    dependsOn("animationExamplesBundleJar", "classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ksl.examples.general.animationbundle.showcase.AnimationsPackageKt")
    workingDir = rootDir
    jvmArgs("-Xmx2g")
    systemProperty("bundleJar", layout.buildDirectory.file("libs/animation-examples.jar").get().asFile.path)
    systemProperty("traces", rootDir.resolve("build/showcase").path)
    systemProperty("layouts", rootDir.resolve("docs/animations/layouts").path)
    systemProperty("out", rootDir.resolve("build/ksl-animations").path)
}

tasks.register<JavaExec>("animationExamplesBundleJar") {
    group = "ksl bundle"
    description = "Assemble the KSL Animation Examples manifest bundle JAR (kslpkg assemble)."
    dependsOn("animationBuildersJar")
    classpath = kslpkgClasspath
    mainClass.set("ksl.bundle.tools.MainKt")
    args(
        "assemble", layout.buildDirectory.file("libs/animation-builders.jar").get().asFile.path,
        "--id", "edu.uark.ksl.animation-examples",
        "--name", "KSL Animation Examples",
        "--version", "1.0.0",
        "--description", "The worked KSL animation examples (process view, stations, conveyors, " +
            "movable/transport resources, and agent-based grid/continuous/network models), each ready " +
            "to capture and replay in the KSL animation app.",
        "-o", layout.buildDirectory.file("libs/animation-examples.jar").get().asFile.path,
        "--force",
    )
}

// Showcase tooling: capture a trace plus the auto-layout generated from it, as the starting point for
// polishing an animation for the README and the animation guide. It lives here because this is the module
// that has both the example models and KSLApp's auto-layout generator; the desktop app deliberately does
// not depend on the examples.
//
// The trace and the layout are separate files bound by element name, so the trace is captured once and the
// layout is then edited freely -- no recompile, no re-run. That is what makes polishing affordable.
// Re-running never overwrites a layout once polishing has begun.
//
// Usage: ./gradlew :KSLExamples:showcaseCapture -PmodelName=Example13MovableResources -Pout=build/showcase
tasks.register<JavaExec>("showcaseCapture") {
    group = "documentation"
    description = "Capture a showcase trace + its auto-layout starting point."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ksl.examples.general.animationbundle.showcase.ShowcaseCaptureKt")
    jvmArgs("-Xmx4g", "-Djava.awt.headless=true")
    workingDir = rootDir
    // NOTE: not -Pmodel. Gradle's Project already has a `model`, so hasProperty("model") is always true
    // and the value that arrives is the Project itself -- the same trap as -Playout (see renderFrames).
    listOf("modelName", "out").forEach { p ->
        if (project.hasProperty(p)) systemProperty(p, project.property(p)!!)
    }
}
