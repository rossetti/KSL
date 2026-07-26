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

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the promise the export makes: that the page it writes is genuinely self-contained.
 *
 * "Self-contained" is the whole value — a file that plays with no server, no install and no network is a
 * different artifact from a hosted page, and it is what makes an animation something a student can be
 * handed. So the assertions are about *containment*: the trace is inside the file, the player is inside
 * the file, and nothing in it reaches outward for a script or a stylesheet.
 *
 * A stand-in player is used rather than the real 485 KB bundle, since what is being tested is the
 * assembly, not the bundle's contents.
 */
class SelfContainedHtmlExporterTest {

    private val traceLines = listOf(
        """{"formatVersion":1,"baseTimeUnit":"MINUTE","kslVersion":null,"description":"Clinic"}""",
        """{"event":"ExperimentStarted","simTime":0.0,"experimentName":"E","numberOfReplications":1}""",
        """{"event":"EntityCreated","simTime":1.0,"entityId":1,"entityType":"Patient"}""",
        """{"event":"SeizeQueued","simTime":1.0,"entityId":1,"resourceName":"Nurse","queueName":"Nurse:Q","amountRequested":1}""",
        """{"event":"SeizeAllocated","simTime":1.0,"entityId":1,"resourceName":"Nurse","amountAllocated":1}""",
        """{"event":"ResourceStateChanged","simTime":1.0,"resourceName":"Nurse","state":"Busy","busyUnits":1,"capacity":1}""",
        """{"event":"Released","simTime":5.0,"entityId":1,"resourceName":"Nurse","amountReleased":1}""",
        """{"event":"EntityDisposed","simTime":5.0,"entityId":1}""",
    )

    private fun trace(dir: Path, name: String = "run.atf"): Path =
        dir.resolve(name).also { Files.writeString(it, traceLines.joinToString("\n") + "\n") }

    private fun player(dir: Path): Path =
        dir.resolve("ksl-animation.js").also { Files.writeString(it, "/* stand-in player */\nconsole.log('ksl');\n") }

    @Test
    @DisplayName("the exported page carries the trace and the player inside it")
    fun exportIsSelfContained(@TempDir dir: Path) {
        val out = dir.resolve("out/clinic.html")
        val report = SelfContainedHtmlExporter.using(player(dir)).export(trace = trace(dir), out = out)

        val html = Files.readString(out)
        assertTrue("EntityCreated" in html, "the trace itself must be in the page")
        assertTrue("stand-in player" in html, "the player must be in the page")
        assertTrue(report.totalBytes > 0)

        // Nothing may reach outward: no external script or stylesheet.
        assertTrue(
            Regex("""<script[^>]*\ssrc\s*=""").findAll(html).none(),
            "a self-contained page must not load an external script"
        )
        assertTrue(
            Regex("""<link[^>]*rel\s*=\s*["']stylesheet""").findAll(html).none(),
            "a self-contained page must not load an external stylesheet"
        )
    }

    @Test
    @DisplayName("a trace with no layout is scaffolded rather than exported blank")
    fun exportScaffoldsWhenNoLayoutIsGiven(@TempDir dir: Path) {
        val out = dir.resolve("clinic.html")
        SelfContainedHtmlExporter.using(player(dir)).export(trace = trace(dir), layout = null, out = out)
        val html = Files.readString(out)
        // The scaffold places the queue and the resource the trace observed; without it the page would
        // carry no layout at all and the player would draw nothing for a process-view model.
        assertTrue("ksl-layout" in html, "a layout must be embedded")
        assertTrue("Nurse:Q" in html, "the scaffold must place the observed queue")
        assertTrue("Nurse" in html, "and the observed resource")
    }

    @Test
    @DisplayName("a gzipped trace is decompressed on the way in")
    fun exportAcceptsAGzippedTrace(@TempDir dir: Path) {
        val plain = trace(dir)
        val gz = dir.resolve("run.atf.gz")
        java.util.zip.GZIPOutputStream(Files.newOutputStream(gz)).bufferedWriter().use {
            it.write(Files.readString(plain))
        }
        val out = dir.resolve("gz.html")
        SelfContainedHtmlExporter.using(player(dir)).export(trace = gz, out = out)
        // A file:// page cannot fetch and decompress its own embedded bytes, so the text must be plain.
        assertTrue("EntityCreated" in Files.readString(out))
    }

    @Test
    @DisplayName("a model name that could close the script element cannot break the page")
    fun scriptTerminatorsInTraceDataAreNeutralised(@TempDir dir: Path) {
        val hostile = dir.resolve("hostile.atf")
        Files.writeString(
            hostile,
            """{"formatVersion":1,"baseTimeUnit":"MINUTE","kslVersion":null,"description":"a</script><b>"}""" + "\n" +
                """{"event":"EntityCreated","simTime":1.0,"entityId":1,"entityType":"P"}""" + "\n"
        )
        val out = dir.resolve("hostile.html")
        SelfContainedHtmlExporter.using(player(dir)).export(trace = hostile, out = out)
        val html = Files.readString(out)
        assertTrue("</script><b>" !in html, "an embedded </script> must not survive verbatim")
        assertTrue("<\\/script>" in html, "it must be escaped instead")
    }

    @Test
    @DisplayName("a gallery shares one player across its animations")
    fun galleryWritesOnePlayerForManyRuns(@TempDir dir: Path) {
        val outDir = dir.resolve("gallery")
        val runs = listOf(
            AnimationRunRef("First", trace(dir, "a.atf")),
            AnimationRunRef("Second", trace(dir, "b.atf")),
        )
        SelfContainedHtmlExporter.using(player(dir)).exportGallery(runs, outDir)

        assertTrue(Files.isRegularFile(outDir.resolve("ksl-animation.js")), "one shared player")
        assertTrue(Files.isRegularFile(outDir.resolve("traces/a.atf")))
        assertTrue(Files.isRegularFile(outDir.resolve("traces/b.atf")))
        val index = Files.readString(outDir.resolve("index.html"))
        assertTrue("First" in index)
        assertTrue("Second" in index)
        assertEquals(1, Regex("""<script src=""").findAll(index).count(), "the player is linked once")
    }
}

/**
 * Covers how a *running* app gets hold of the player: off the classpath, not from a path it has to guess.
 *
 * These skip when the build carries no player, which is the normal state of a plain `./gradlew build` —
 * the bundle comes from the standalone web project, kept outside the root build so that an ordinary build
 * needs no Node.js. Skipping rather than failing is the point: a developer who never builds the web module
 * should not see red, while a release (which does build it) gets the coverage.
 */
class PackagedPlayerTest {

    @Test
    @DisplayName("when packaged, the player is found on the classpath and produces a page")
    fun packagedPlayerExports(@TempDir dir: Path) {
        val exporter = SelfContainedHtmlExporter.bundled()
        org.junit.jupiter.api.Assumptions.assumeTrue(
            exporter != null,
            "no packaged player in this build; run: ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack"
        )
        val trace = dir.resolve("run.atf")
        Files.writeString(
            trace,
            """{"formatVersion":1,"baseTimeUnit":"MINUTE","kslVersion":null,"description":"Smoke"}""" + "\n" +
                """{"event":"EntityCreated","simTime":1.0,"entityId":1,"entityType":"P"}""" + "\n"
        )
        val out = dir.resolve("smoke.html")
        val report = exporter!!.export(trace = trace, out = out)
        assertTrue(report.playerBytes > 100_000, "the real bundle, not a stand-in; got ${report.playerBytes}")
        assertTrue("ksl-trace" in Files.readString(out))
    }

    @Test
    @DisplayName("availability and retrieval agree with each other")
    fun availabilityMatchesRetrieval() {
        assertEquals(
            SelfContainedHtmlExporter.isAvailable(),
            SelfContainedHtmlExporter.bundled() != null,
            "isAvailable() is what a UI asks before offering the action, so it must not disagree"
        )
    }
}
