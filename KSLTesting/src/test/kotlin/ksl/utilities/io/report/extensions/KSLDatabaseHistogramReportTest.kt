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

import ksl.utilities.io.dbutil.Database
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.FrequencyTableData
import ksl.utilities.io.dbutil.HistogramTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.report.ast.ReportNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Structural tests for the R3c per-response histogram / frequency entry
 *  points.  Each test hand-writes `HISTOGRAM` / `FREQUENCY` rows into a fresh
 *  on-disk SQLite KSL database (experiment + simulation run + rows; no `Model`)
 *  and walks the returned [ReportNode] AST.  This is the data path that
 *  surfaces `HistogramResponse` / `IntegerFrequencyResponse` output.
 */
class KSLDatabaseHistogramReportTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `single histogram report has a section, table, and plot`() {
        val (_, kdb) = sampleDb("hist_single")
        val doc = kdb.toHistogramReport("E", "SystemTime:Histogram")

        assertEquals("Histogram — SystemTime:Histogram (E)", doc.title)
        assertTrue("SystemTime:Histogram" in doc.sectionTitles())
        assertEquals(1, doc.plotCount())
        assertTrue(doc.dataTableCount() >= 1)
    }

    @Test
    fun `all-histograms report covers every histogram response`() {
        val (_, kdb) = sampleDb("hist_all")
        val doc = kdb.toHistogramReport("E")
        val titles = doc.sectionTitles()
        assertTrue("Histograms" in titles)
        assertTrue("SystemTime:Histogram" in titles)
        assertTrue("Other:Histogram" in titles)
        assertEquals(2, doc.plotCount())
    }

    @Test
    fun `single frequency report has a section, table, and plot`() {
        val (_, kdb) = sampleDb("freq_single")
        val doc = kdb.toFrequencyReport("E", "NQUponArrival")
        assertEquals("Frequency — NQUponArrival (E)", doc.title)
        assertTrue("NQUponArrival" in doc.sectionTitles())
        assertEquals(1, doc.plotCount())
        assertTrue(doc.dataTableCount() >= 1)
    }

    @Test
    fun `all-frequencies report covers every frequency response`() {
        val (_, kdb) = sampleDb("freq_all")
        val doc = kdb.toFrequencyReport("E")
        assertTrue("Frequencies" in doc.sectionTitles())
        assertTrue("NQUponArrival" in doc.sectionTitles())
        assertEquals(1, doc.plotCount())
    }

    @Test
    fun `plot can be suppressed`() {
        val (_, kdb) = sampleDb("hist_no_plot")
        val doc = kdb.toHistogramReport("E", "SystemTime:Histogram", showPlot = false)
        assertEquals(0, doc.plotCount())
        assertTrue(doc.dataTableCount() >= 1)
    }

    @Test
    fun `unknown histogram response emits a notice and no plot`() {
        val (_, kdb) = sampleDb("hist_unknown")
        val doc = kdb.toHistogramReport("E", "Nonexistent:Histogram")
        assertEquals(0, doc.plotCount())
        assertTrue(doc.paragraphTexts().any { it.contains("No histogram data") })
    }

    // ── AST traversal helpers ─────────────────────────────────────────────

    private fun ReportNode.walk(): Sequence<ReportNode> = sequence {
        yield(this@walk)
        val kids = when (this@walk) {
            is ReportNode.Document -> children
            is ReportNode.Section -> children
            else -> emptyList()
        }
        for (k in kids) yieldAll(k.walk())
    }

    private fun ReportNode.sectionTitles(): List<String> =
        walk().filterIsInstance<ReportNode.Section>().mapNotNull { it.title }.toList()

    private fun ReportNode.plotCount(): Int = walk().count { it is ReportNode.PlotNode }

    private fun ReportNode.dataTableCount(): Int = walk().count { it is ReportNode.DataTable }

    private fun ReportNode.paragraphTexts(): List<String> =
        walk().filterIsInstance<ReportNode.Paragraph>().map { it.text }.toList()

    // ── Test fixtures ─────────────────────────────────────────────────────

    /**
     *  Experiment "E" with two histogram responses (3 and 2 bins) and one
     *  integer-frequency response (3 cells).
     */
    private fun sampleDb(name: String): Pair<Database, KSLDatabase> {
        val database = KSLDatabase.createSQLiteKSLDatabase(name, tempDir)
        val kdb = KSLDatabase(database)
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 3)

        insertHistogramBins(database, runId, elementId = 1, responseName = "SystemTime:Histogram", numBins = 3)
        insertHistogramBins(database, runId, elementId = 2, responseName = "Other:Histogram", numBins = 2)
        insertFrequencyCells(database, runId, elementId = 3, name = "NQUponArrival", numCells = 3)

        return database to kdb
    }

    private fun insertExperiment(database: Database, expName: String, modelName: String): Int {
        val record = ExperimentTableData().apply {
            sim_name = "TestSim"; model_name = modelName; exp_name = expName
        }
        database.insertDbDataIntoTable(record)
        return record.exp_id
    }

    private fun insertSimRun(database: Database, expId: Int, runName: String, numReps: Int): Int {
        val record = SimulationRunTableData().apply {
            exp_id_fk = expId; run_name = runName; num_reps = numReps
            start_rep_id = 1; last_rep_id = numReps
        }
        database.insertDbDataIntoTable(record)
        return record.run_id
    }

    private fun insertHistogramBins(
        database: Database, runId: Int, elementId: Int, responseName: String, numBins: Int
    ) {
        var cum = 0.0
        for (bin in 1..numBins) {
            val count = (numBins - bin + 1) * 10.0
            cum += count
            val record = HistogramTableData().apply {
                this.element_id_fk = elementId
                this.sim_run_id_fk = runId
                this.response_id_fk = elementId
                this.response_name = responseName
                this.bin_label = "[${bin - 1}.0, $bin.0)"
                this.bin_num = bin
                this.bin_lower_limit = (bin - 1).toDouble()
                this.bin_upper_limit = bin.toDouble()
                this.bin_count = count
                this.bin_cum_count = cum
                this.bin_proportion = 0.0
                this.bin_cum_proportion = 0.0
            }
            database.insertDbDataIntoTable(record)
        }
    }

    private fun insertFrequencyCells(
        database: Database, runId: Int, elementId: Int, name: String, numCells: Int
    ) {
        var cum = 0.0
        for (i in 0 until numCells) {
            val count = (numCells - i) * 5.0
            cum += count
            val record = FrequencyTableData().apply {
                this.element_id_fk = elementId
                this.sim_run_id_fk = runId
                this.name = name
                this.cell_label = i.toString()
                this.value = i
                this.count = count
                this.cum_count = cum
                this.proportion = 0.0
                this.cum_proportion = 0.0
            }
            database.insertDbDataIntoTable(record)
        }
    }
}
