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

import java.time.Instant

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
    // KSLApp hosts ksl.app.* (bundling/run/session), extracted from KSLCore.
    implementation(project(":KSLApp"))

    // KSLExamples carries a small self-test suite that verifies its own
    // content (e.g. BookExamplesBundle ServiceLoader discovery + model
    // build/run). It depends on nothing but KSLCore, so KSLExamples remains
    // a sink that no other module depends on.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// NOTE: an opt-in `enrichExampleBundle` task previously lived here. It ran
// `kslpkg enrich` to embed ModelDescriptor JSON into a copy of the KSLExamples
// JAR. `kslpkg enrich` was retired in favour of `kslpkg assemble` (which builds
// a manifest bundle from a plain builders JAR); a replacement example-bundle
// build step is introduced when the example bundles are converted to the
// manifest mechanism.

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

    // Stamp the build time so newest-wins dedup can resolve same-(bundleId,
    // version) duplicates in ~/.ksl/bundles/ to the most recently packaged
    // copy.  The loader falls back to the JAR file's mtime when this is absent.
    manifest {
        attributes("Build-Time" to Instant.now().toString())
    }

    // Only the two class packages that make up the bundle's closure.  The
    // include filter also excludes the full jar's 4-bundle META-INF/services.
    from(sourceSets["main"].output) {
        include("ksl/examples/general/bookbundle/**")
        include("ksl/examples/general/models/inventory/**")
    }
    // Book-only ServiceLoader registration (single bundle).
    from("bundle-meta/book-examples")
}
