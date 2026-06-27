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

package ksl.utilities.io.report.extensions

import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.ResponseTrace
import ksl.observers.ResponseTraceData
import ksl.simulation.Model
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 *  Tests for the disk-backed trace reader [ResponseTraceData] and the
 *  `responseTrace` DSL now driving off [ksl.observers.ResponseTraceDataIfc].
 *
 *  A short sim writes real trace files; the reader reopens them and the DSL
 *  is exercised by inspecting the report AST (no rendering), so the tests are
 *  headless-safe — building plot nodes does not rasterize them.
 */
class ResponseTraceReportExtensionsTest {

    private companion object {
        const val SYSTEM_TIME = "System Time"     // tally (observation)
        const val NUM_IN_SYSTEM = "Num in System" // time-weighted
    }

    /** Run a short sim tracing one tally and one time-weighted response. */
    private fun runTraces(outDir: Path) {
        val m = Model("TraceExtModel", autoCSVReports = false)
        m.outputDirectory = OutputDirectory(outDir, "kslOutput.txt")
        m.numberOfReplications = 2
        m.lengthOfReplication = 200.0
        GIGcQueue(m, numServers = 1, name = "Q")
        ResponseTrace(m.response(SYSTEM_TIME)!!)
        ResponseTrace(m.response(NUM_IN_SYSTEM)!!)
        m.simulate()
    }

    private fun traceFile(outDir: Path, responseName: String): Path =
        outDir.resolve(responseName.replace(':', '_') + "_Trace")

    /** All DataTable captions in the document, walking sections recursively. */
    private fun captions(node: ReportNode): List<String> = when (node) {
        is ReportNode.Document -> node.children.flatMap { captions(it) }
        is ReportNode.Section -> node.children.flatMap { captions(it) }
        is ReportNode.DataTable -> listOfNotNull(node.caption)
        else -> emptyList()
    }

    @Test
    @DisplayName("ResponseTraceData reopens a trace and exposes replications and windowed data")
    fun readerReopensTraceAndQueriesData(@TempDir tempDir: Path) {
        runTraces(tempDir)
        val data = ResponseTraceData(traceFile(tempDir, SYSTEM_TIME), isTimeWeighted = false)

        assertTrue(data.replicationNumbers.isNotEmpty(), "reader should see recorded replications")
        val rep1 = data.replicationNumbers.first()
        val map = data.traceDataMap(rep1)
        val times = map["times"] ?: doubleArrayOf()
        val values = map["values"] ?: doubleArrayOf()
        assertTrue(times.isNotEmpty() && values.size == times.size,
            "rep $rep1 should have parallel times/values; got ${times.size}/${values.size}")
    }

    @Test
    @DisplayName("responseTrace renders a scatter + descriptive stats for an observation response")
    fun observationTraceSectionShape(@TempDir tempDir: Path) {
        runTraces(tempDir)
        val data = ResponseTraceData(traceFile(tempDir, SYSTEM_TIME), isTimeWeighted = false)
        val doc = report("Trace") { responseTrace(data) }
        assertTrue(captions(doc).any { it.contains("Descriptive Statistics") },
            "observation trace should emit a Descriptive Statistics table; got: ${captions(doc)}")
    }

    @Test
    @DisplayName("responseTrace renders a state-variable plot + TW stats for a time-weighted response")
    fun stateVariableTraceSectionShape(@TempDir tempDir: Path) {
        runTraces(tempDir)
        val data = ResponseTraceData(traceFile(tempDir, NUM_IN_SYSTEM), isTimeWeighted = true)
        val doc = report("Trace") { responseTrace(data) }
        assertTrue(captions(doc).any { it.contains("Time-Weighted Statistics") },
            "time-weighted trace should emit a Time-Weighted Statistics table; got: ${captions(doc)}")
    }
}
