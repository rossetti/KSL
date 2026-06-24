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
