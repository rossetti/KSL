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

package ksl.app.single.results

import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.ResponseTrace
import ksl.observers.ResponseTraceData
import ksl.simulation.Model
import ksl.testutils.DisabledIfHeadless
import ksl.utilities.io.OutputDirectory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Phase-3 tests for [TraceReportMaterializer].
 *
 *  A helper runs a short simulation that streams real trace files to a temp
 *  directory (no plot rendering, so it is headless-safe).  The discovery,
 *  clear, and "no data" tests then run unguarded; the HTML-render test carries
 *  [DisabledIfHeadless] because the trace report embeds lets-plot figures that
 *  throw `java.awt.HeadlessException` in a headless JVM.
 */
class TraceReportMaterializerTest {

    private companion object {
        const val SYSTEM_TIME = "System Time"     // tally (observation)
        const val NUM_IN_SYSTEM = "Num in System" // time-weighted
    }

    /** Run a short sim tracing one tally and one time-weighted response. */
    private fun writeTraces(outputDir: Path) {
        val m = Model("TraceMatModel", autoCSVReports = false)
        m.outputDirectory = OutputDirectory(outputDir, "kslOutput.txt")
        m.numberOfReplications = 2
        m.lengthOfReplication = 200.0
        GIGcQueue(m, numServers = 1, name = "Q")
        val traces = listOf(
            ResponseTrace(m.response(SYSTEM_TIME)!!),
            ResponseTrace(m.response(NUM_IN_SYSTEM)!!)
        )
        m.simulate()
        // Close the trace observers once their files are written so later steps (clearTraceData,
        // ResponseTraceData reads, @TempDir cleanup) act on unlocked files — Windows blocks deleting
        // a file that still has an open handle.
        traces.forEach { it.close() }
    }

    private fun traceFile(outputDir: Path, responseName: String): Path =
        outputDir.resolve(responseName.replace(':', '_') + "_Trace")

    @Test
    @DisplayName("discoverTraceFiles finds a file per traced response")
    fun discoverTraceFilesFindsTraceFiles(@TempDir tempDir: Path) {
        writeTraces(tempDir)
        val files = TraceReportMaterializer.discoverTraceFiles(tempDir)
        assertEquals(2, files.size, "expected one trace file per traced response; got $files")
        assertTrue(files.any { it.fileName.toString() == "System Time_Trace" })
        assertTrue(files.any { it.fileName.toString() == "Num in System_Trace" })
    }

    @Test
    @DisplayName("clearTraceData removes only the *_Trace files, leaving other output")
    fun clearTraceDataRemovesOnlyTraceFiles(@TempDir tempDir: Path) {
        writeTraces(tempDir)
        val keep = tempDir.resolve("keepme.txt")
        Files.writeString(keep, "not a trace")

        val removed = TraceReportMaterializer.clearTraceData(tempDir)

        assertEquals(2, removed, "both *_Trace files should be removed")
        assertTrue(TraceReportMaterializer.discoverTraceFiles(tempDir).isEmpty())
        assertFalse(Files.exists(traceFile(tempDir, SYSTEM_TIME)))
        assertTrue(Files.exists(keep), "non-trace output must survive")
    }

    @Test
    @DisplayName("materialize returns Failed when no trace data is present")
    fun materializeReturnsFailedWhenNoTraces(@TempDir tempDir: Path) {
        val outcome = TraceReportMaterializer.materialize(
            traces = emptyList(),
            format = StandardReportFormat.HTML,
            reportsDir = tempDir.resolve("reports")
        )
        assertIs<StandardReportOutcome.Failed>(outcome)
    }

    @Test
    @DisplayName("materialize HTML writes a non-empty file with a section per response")
    @DisabledIfHeadless
    fun materializeHtmlWritesSectionPerResponse(@TempDir tempDir: Path) {
        writeTraces(tempDir)
        val traces = listOf(
            ResponseTraceData(traceFile(tempDir, SYSTEM_TIME), isTimeWeighted = false),
            ResponseTraceData(traceFile(tempDir, NUM_IN_SYSTEM), isTimeWeighted = true)
        )
        val reportsDir = tempDir.resolve("reports")

        val outcome = TraceReportMaterializer.materialize(
            traces = traces,
            format = StandardReportFormat.HTML,
            reportsDir = reportsDir,
            fileStem = "trace",
            title = "Response Trace — TraceMatModel"
        )

        val ok = assertIs<StandardReportOutcome.Ok>(outcome)
        assertTrue(ok.file.exists(), "Report file must exist: ${ok.file}")
        val html = ok.file.readText()
        assertTrue(html.isNotBlank(), "Report file must be non-empty")
        assertTrue(html.contains(SYSTEM_TIME), "HTML must contain a section for '$SYSTEM_TIME'")
        assertTrue(html.contains(NUM_IN_SYSTEM), "HTML must contain a section for '$NUM_IN_SYSTEM'")
        assertTrue(html.contains("plot-container"),
            "HTML must embed at least one plot (scatter / state-variable)")
    }
}
