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

dependencies {
    implementation(project(":KSLCore"))
}

kotlin {
    jvmToolchain(21)
}

// Opt-in task: produces a copy of the KSLExamples JAR with embedded
// ModelDescriptor entries for every bundled model (MM1Bundle,
// LKInventoryBundle). Invokes the kslpkg fat JAR from :KSLBundleTools.
//
// Not wired into `assemble`: KSLExamples is a library, not a bundle
// distribution, so paying the enrich cost on every build is unwarranted.
// Authors who want an enriched JAR run this task explicitly:
//     ./gradlew :KSLExamples:enrichExampleBundle
//
// The output lands beside the input as
//     KSLExamples/build/libs/<jar-stem>-enriched.jar
tasks.register<JavaExec>("enrichExampleBundle") {
    group = "ksl bundle"
    description = "Embed ModelDescriptor JSON into a copy of the KSLExamples JAR."

    val examplesJarTask = tasks.named<Jar>("jar")
    val kslpkgJarTask = project(":KSLBundleTools").tasks.named<Jar>("shadowJar")
    dependsOn(examplesJarTask, kslpkgJarTask)

    // The fat JAR carries its own Main-Class manifest entry, but JavaExec
    // wants a classpath + mainClass; both forms run the same code.
    classpath(kslpkgJarTask.flatMap { it.archiveFile })
    mainClass.set("ksl.bundle.tools.MainKt")

    // Declare I/O for incremental-build correctness.
    inputs.file(examplesJarTask.flatMap { it.archiveFile })
    inputs.file(kslpkgJarTask.flatMap { it.archiveFile })

    doFirst {
        val inputJar = examplesJarTask.get().archiveFile.get().asFile.absolutePath
        args = listOf("enrich", inputJar, "--force")
    }
}

// Produces the slim, distributable "KSL Book Examples" bundle JAR meant to be
// dropped into ~/.ksl/bundles/ (or loaded via Bundles -> Load JAR...).  It
// carries ONLY:
//   - the curated book models + their copied framework
//     (package ksl.examples.general.bookbundle), and
//   - the reused two-echelon inventory closure
//     (package ksl.examples.general.models.inventory, which BookExamplesBundle's
//      Two-Echelon entry delegates to via BuildTwoEchelonModel).
// plus a BOOK-ONLY META-INF/services registration, so loading it surfaces only
// the 16 book models — not the three dogfood bundles (MM1 / LKInventory /
// SimoptTestModels) that the full KSLExamples jar also registers.
//
// It deliberately does NOT bundle KSLCore: a bundle JAR is loaded under the
// host app's classloader, which already provides KSLCore.
//
//     ./gradlew :KSLExamples:bookExamplesBundleJar
//     -> KSLExamples/build/libs/book-examples.jar
tasks.register<Jar>("bookExamplesBundleJar") {
    group = "ksl bundle"
    description = "Slim KSL Book Examples bundle JAR for ~/.ksl/bundles/."
    archiveBaseName.set("book-examples")
    archiveVersion.set("")   // clean drop-in name: book-examples.jar
    dependsOn(tasks.named("classes"))

    // Only the two class packages that make up the bundle's closure.  The
    // include filter also excludes the full jar's 4-bundle META-INF/services.
    from(sourceSets["main"].output) {
        include("ksl/examples/general/bookbundle/**")
        include("ksl/examples/general/models/inventory/**")
    }
    // Book-only ServiceLoader registration (single bundle).
    from("bundle-meta/book-examples")
}
