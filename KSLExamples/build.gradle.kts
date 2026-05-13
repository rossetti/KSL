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
