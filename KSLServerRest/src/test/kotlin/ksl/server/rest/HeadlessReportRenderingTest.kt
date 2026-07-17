/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.server.rest

import ksl.app.single.results.TraceManifest
import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.ResponseTrace
import ksl.observers.welch.WelchFileObserver
import ksl.service.capability.report.ReportArtifactService
import ksl.service.capability.report.ReportRequest
import ksl.service.capability.report.TraceReport
import ksl.service.capability.report.WelchReport
import ksl.simulation.Model
import ksl.utilities.io.OutputDirectory
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * The Phase B headless guarantee for the server: with the lets-plot Swing
 * display frontend excluded from this module's runtime (see build.gradle.kts),
 * Welch and trace reports render on a HEADLESS JVM — no display, no Xvfb.
 *
 * `java.awt.headless=true` is forced before any lets-plot class loads (the
 * server's CI/production target is headless), and the test asserts the Swing
 * frontend probe class really is absent, so this fails loudly if a future
 * dependency change drags it back onto the classpath.
 */
class HeadlessReportRenderingTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun forceHeadless() {
            System.setProperty("java.awt.headless", "true")
            check(GraphicsEnvironment.isHeadless()) { "could not force headless mode for the test fork" }
        }
    }

    private fun runCapture(outDir: Path) {
        val m = Model("HeadlessServerReportModel", autoCSVReports = false)
        m.outputDirectory = OutputDirectory(outDir, "kslOutput.txt")
        m.numberOfReplications = 3
        m.lengthOfReplication = 2000.0
        GIGcQueue(m, numServers = 1, name = "Q")
        val observers = listOf<AutoCloseable>(
            WelchFileObserver(m.response("System Time")!!, 1.0),
            ResponseTrace(m.response("System Time")!!),
            ResponseTrace(m.response("Num in System")!!)
        )
        m.simulate()
        // Close the capture observers so their trace/Welch handles are released and @TempDir can be
        // deleted (on Windows an open handle blocks temp-dir cleanup; invisible on Unix).
        observers.forEach { it.close() }
        TraceManifest.write(outDir, mapOf("System Time" to false, "Num in System" to true))
    }

    @Test
    @DisplayName("the lets-plot Swing display frontend is excluded from the server runtime")
    fun swingFrontendIsExcluded() {
        val present = runCatching {
            Class.forName("org.jetbrains.letsPlot.batik.plot.component.PlotViewerWindowBatik")
        }.isSuccess
        assertTrue(!present, "lets-plot Swing frontend must NOT be on the server classpath (it breaks headless)")
    }

    @Test
    @DisplayName("Welch and trace reports render headless with the frontend excluded")
    fun reportsRenderHeadless(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output").also { Files.createDirectories(it) }
        runCapture(outDir)

        val written = ReportArtifactService().materialize(
            tempDir.resolve("artifacts"), outDir,
            ReportRequest(welch = WelchReport(), trace = TraceReport()),
        )
        val names = written.map { it.fileName.toString() }
        assertTrue(names.contains("welch.html"), "expected welch.html headless; wrote $names")
        assertTrue(names.contains("trace.html"), "expected trace.html headless; wrote $names")
        assertTrue(written.all { Files.size(it) > 0 }, "headless-rendered reports must be non-empty")
    }
}
