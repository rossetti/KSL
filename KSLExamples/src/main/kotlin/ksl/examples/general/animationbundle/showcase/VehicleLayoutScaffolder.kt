/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.animationbundle.showcase

import ksl.animation.AnimationCapture
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.io.load
import ksl.app.animation.replay.AutoLayoutSource
import ksl.app.animation.replay.buildAutoLayout
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Writes a starting layout for every model in the vehicle examples bundle, keyed
 * `<bundleId>/<modelId>.lay.toml` under the shipped layouts folder, so the suite has one to offer for each.
 *
 * Each is the layout the Animation app's Auto Layout would make: a short run is captured and the layout is
 * built from the model and that trace. **An existing file is never overwritten.** Layouts are polished by
 * hand once written, and a scaffold is only ever a starting point, so running this again adds layouts for
 * models that have none and leaves every other file exactly as it is.
 *
 * System properties: `bundleJar` (the assembled vehicle-examples jar, whose manifest says which models ship),
 * `out` (the shipped layouts root), and optionally `traceLength` (simulated time captured, default 300).
 */
fun main() {
    val bundleJar = Path.of(System.getProperty("bundleJar") ?: error("-DbundleJar is required"))
    val outRoot = Path.of(System.getProperty("out") ?: error("-Dout is required"))
    val traceLength = System.getProperty("traceLength")?.toDoubleOrNull() ?: 300.0

    val manifest = ZipFile(bundleJar.toFile()).use { zip ->
        zip.getInputStream(zip.getEntry("META-INF/ksl/bundle.toml")).bufferedReader().readText()
    }
    val bundleId = Regex("""^\s*bundleId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(manifest)!!.groupValues[1]
    val models = Regex("""^\s*modelId\s*=\s*"([^"]+)"\s*$\s*builderClass\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .findAll(manifest).map { it.groupValues[1] to it.groupValues[2] }.toList()
    require(models.isNotEmpty()) { "no models found in $bundleJar" }

    val dir = outRoot.resolve(bundleId).also { Files.createDirectories(it) }
    for ((modelId, builderClass) in models) {
        val file = dir.resolve("$modelId.lay.toml")
        if (Files.exists(file)) {
            println("kept     $file")
            continue
        }
        val builder = Class.forName(builderClass).getDeclaredConstructor().newInstance() as ModelBuilderIfc
        fun build() = builder.build(null, null as ExperimentRunParametersIfc?)
        val trace = Files.createTempFile("vehicle-scaffold", ".atf")
        try {
            val running = build().apply {
                numberOfReplications = 1
                lengthOfReplicationWarmUp = 0.0
                lengthOfReplication = minOf(lengthOfReplication, traceLength)
            }
            val capture = AnimationCapture.toFile(running, trace)
            running.simulate()
            capture.close()
            // A fresh model: buildAutoLayout reads the built structure, and the run's instance has been simulated.
            val layout = build().buildAutoLayout(AnimationSource.load(null, trace), AutoLayoutSource.AUTO)
                .copy(title = modelId)
            layout.writeTomlToFile(file)
            println("wrote    $file")
        } finally {
            Files.deleteIfExists(trace)
        }
    }
}
