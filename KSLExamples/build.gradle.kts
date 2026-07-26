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
        include("ksl/examples/general/animationbundle/**")          // the builders + example objects (incl. inline AnnotatedClinic)
        include("ksl/examples/general/agent/**")                    // duplicated agent models (epidemic, flocking, AGV, drone, …)
        include("ksl/examples/book/chapter6/DriveThroughPharmacy*")
        include("ksl/examples/book/chapter7/StemFairMixerEnhanced*")
        include("ksl/examples/book/chapter8/TandemQueueWithConveyors*")
        include("ksl/examples/book/chapter8/TandemQueueWithUnconstrainedMovement*")
        include("ksl/examples/book/chapter8/TestAndRepairShopWithMovableResources*")
        include("ksl/examples/general/models/station/**")           // StationNetworkTandemQueue / MultiClass + their closure
    }
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
