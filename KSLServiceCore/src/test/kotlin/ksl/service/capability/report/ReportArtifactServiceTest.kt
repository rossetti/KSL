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

package ksl.service.capability.report

import ksl.app.single.results.TraceManifest
import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.ResponseTrace
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.utilities.io.OutputDirectory
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Tests for [ReportArtifactService]: it materializes Welch / trace reports from a
 * finished run's on-disk capture output into an artifact directory.
 *
 * Rendering embeds lets-plot figures, which needs a display on this module's test
 * classpath (the lets-plot Swing frontend is only excluded on the server
 * runtime, not here), so the render tests skip when headless via [assumeFalse].
 * The no-data path is headless-safe and always runs.
 */
class ReportArtifactServiceTest {

    private companion object {
        const val SYSTEM_TIME = "System Time"     // tally (observation)
        const val NUM_IN_SYSTEM = "Num in System" // time-weighted
    }

    /** Streams real Welch + trace capture into [outDir] and writes the trace manifest. */
    private fun runCapture(outDir: Path) {
        val m = Model("ReportArtifactModel", autoCSVReports = false)
        m.outputDirectory = OutputDirectory(outDir, "kslOutput.txt")
        m.numberOfReplications = 3
        m.lengthOfReplication = 2000.0
        GIGcQueue(m, numServers = 1, name = "Q")
        WelchFileObserver(m.response(SYSTEM_TIME)!!, 1.0)
        ResponseTrace(m.response(SYSTEM_TIME)!!)
        ResponseTrace(m.response(NUM_IN_SYSTEM)!!)
        m.simulate()
        // The orchestrator writes this in production; do it here for the raw sim.
        TraceManifest.write(outDir, mapOf(SYSTEM_TIME to false, NUM_IN_SYSTEM to true))
    }

    @Test
    @DisplayName("materialize writes Welch and trace HTML reports from captured data")
    fun materializesWelchAndTraceHtml(@TempDir tempDir: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "embedded plot rendering needs a display on this classpath")
        val outDir = tempDir.resolve("output").also { Files.createDirectories(it) }
        val reportsDir = tempDir.resolve("artifacts")
        runCapture(outDir)

        val written = ReportArtifactService().materialize(
            reportsDir, outDir,
            ReportRequest(welch = WelchReport(), trace = TraceReport()),
        )
        val names = written.map { it.fileName.toString() }
        assertTrue(names.contains("welch.html"), "expected welch.html; wrote $names")
        assertTrue(names.contains("trace.html"), "expected trace.html; wrote $names")
        assertTrue(written.all { Files.size(it) > 0 }, "rendered reports must be non-empty")
    }

    @Test
    @DisplayName("materialize writes nothing when no capture data is present (headless-safe)")
    fun noDataYieldsNoArtifacts(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("empty").also { Files.createDirectories(it) }
        val written = ReportArtifactService().materialize(
            tempDir.resolve("artifacts"), outDir,
            ReportRequest(welch = WelchReport(), trace = TraceReport()),
        )
        assertTrue(written.isEmpty(), "no Welch/trace data -> no reports; got $written")
    }
}
