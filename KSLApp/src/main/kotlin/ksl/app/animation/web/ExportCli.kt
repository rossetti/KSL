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

package ksl.app.animation.web

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.system.exitProcess

/**
 * Command-line entry point for publishing animations to the web, driven by the Gradle tasks
 * `exportAnimationHtml` and `exportAnimationGallery`.
 *
 * Configured with system properties rather than arguments so it matches the existing `renderFrames`
 * convention in the animation app, where `-P` properties on the Gradle command line become `-D`
 * properties here.
 *
 * Two modes:
 *
 *  - `-Dtrace=run.atf [-DlayoutFile=run.lay.json] -Dout=run.html` — one self-contained page. The
 *    property is `layoutFile`, not `layout`, because Gradle's `Project` already owns a `layout`
 *    property and a `-Playout=` value never reaches the task.
 *  - `-Ddir=<directory> -Dout=<directory>` — a gallery of every trace in a directory, each paired with a
 *    layout of the same base name when one is present.
 */
fun main() {
    val explicit = property("player")?.let { Path.of(it) }
    val exporter = (explicit?.let { SelfContainedHtmlExporter.using(it) }
        ?: SelfContainedHtmlExporter.bundledOr(defaultPlayerBundle()))
        ?: fail(
            "no player bundle found. Build it first:\n" +
                "  ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack\n" +
                "or pass -Pplayer=<path to ksl-animation.js>"
        )
    val out = Path.of(property("out") ?: fail("-Pout is required"))

    val dir = property("dir")
    if (dir != null) {
        val runs = discoverRuns(Path.of(dir))
        if (runs.isEmpty()) fail("no .atf traces found in $dir")
        val report = exporter.exportGallery(runs, out)
        println("Wrote gallery of ${runs.size} animation(s) to ${out.toAbsolutePath()}")
        runs.forEach { println("  - ${it.title}") }
        println(report.summary())
    } else {
        val trace = Path.of(property("trace") ?: fail("-Ptrace or -Pdir is required"))
        val layout = property("layoutFile")?.let { Path.of(it) } ?: siblingLayout(trace)
        val report = exporter.export(
            trace = trace,
            layout = layout,
            out = out,
            // Defer to the exporter for both, rather than restating them here. A second set of defaults in
            // the command line is how an exported page came to open playing and at a different speed than
            // one the app produced, from the same exporter.
            autoPlay = property("autoplay")?.toBoolean() ?: false,
            loop = property("loop")?.toBoolean() ?: true,
            fitSeconds = property("fit")?.toDoubleOrNull() ?: SelfContainedHtmlExporter.DEFAULT_FIT_SECONDS
        )
        println("Wrote ${out.toAbsolutePath()}")
        println(report.summary())
        if (layout == null) println("note: no layout file found; a layout was scaffolded from the trace")
    }
}

/** Every `.atf`/`.atf.gz` in [dir], each paired with a layout of the same base name when one exists. */
private fun discoverRuns(dir: Path): List<AnimationRunRef> {
    if (!Files.isDirectory(dir)) fail("not a directory: $dir")
    return Files.list(dir).use { stream ->
        stream.filter { it.fileName.toString().let { n -> n.endsWith(".atf") || n.endsWith(".atf.gz") } }
            .sorted()
            .map { AnimationRunRef(title = it.traceTitle(), trace = it, layout = siblingLayout(it)) }
            .toList()
    }
}

/** The `.lay.json` (or `.lay.toml`) sitting beside [trace] with the same base name, or null. */
private fun siblingLayout(trace: Path): Path? {
    val base = trace.traceTitle()
    val parent = trace.toAbsolutePath().parent ?: return null
    return listOf("$base.lay.json", "$base.lay.toml")
        .map { parent.resolve(it) }
        .firstOrNull { Files.isRegularFile(it) }
}

/** A trace's base name, with `.atf` and any `.gz` removed. */
private fun Path.traceTitle(): String {
    val withoutGz = if (extension == "gz") Path.of(nameWithoutExtension) else this
    return withoutGz.nameWithoutExtension
}

/** The player bundle in its usual build location, if it has been built. */
private fun defaultPlayerBundle(): Path? = listOf(
    "KSLAnimationCore/build/kotlin-webpack/js/productionExecutable/ksl-animation.js",
    "KSLAnimationCore/build/kotlin-webpack/js/developmentExecutable/ksl-animation.js",
).map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }

private fun property(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }

private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    exitProcess(1)
}
