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
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.ModelElementTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.TimeSeriesResponseTableData
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Structural tests for the database-backed time-series report extensions.
 *  Each test hand-writes time-series rows into a fresh on-disk SQLite KSL
 *  database in a JUnit `@TempDir` (no `Model` involved) and walks the returned
 *  [ReportNode] AST to assert the expected sections, tables, and plots.
 */
class KSLDatabaseTimeSeriesReportTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `single-response report has overlay and across-rep sections with two plots`() {
        val (_, kdb) = timeSeriesDb("single_ts", responses = listOf("TP"))
        val doc = kdb.toTimeSeriesReport("E", "TP")

        assertEquals("Time Series — TP (E)", doc.title)
        val titles = doc.sectionTitles()
        assertTrue("Time Series by Replication — TP" in titles, "missing per-rep section; got $titles")
        assertTrue("Time Series Across Replications — TP" in titles, "missing across-rep section; got $titles")

        // One overlay plot (all reps) + one mean-trajectory plot.
        assertEquals(2, doc.plotCount())
        // The across-rep section carries the per-period statistics table.
        assertTrue(doc.dataTableCount() >= 1, "expected a per-period data table")
    }

    @Test
    fun `per-replication overlay can be restricted to a subset of reps`() {
        val (_, kdb) = timeSeriesDb("subset_ts", responses = listOf("TP"))
        val doc = report("t") {
            dbTimeSeriesPerReplication(kdb, "E", "TP", repIds = setOf(1, 2))
        }
        assertEquals(1, doc.plotCount())
        val para = doc.paragraphTexts().firstOrNull { it.startsWith("Replications:") }
        assertTrue(para != null && para.contains("Replications: 2"), "expected 2 reps in overlay; got $para")
    }

    @Test
    fun `across-rep plot can be suppressed but table remains`() {
        val (_, kdb) = timeSeriesDb("no_ts_plot", responses = listOf("TP"))
        val doc = report("t") {
            dbTimeSeriesAcrossReplication(kdb, "E", "TP", showPlot = false)
        }
        assertEquals(0, doc.plotCount())
        assertTrue(doc.dataTableCount() >= 1)
    }

    @Test
    fun `all-responses report emits one across-rep section per response`() {
        val (_, kdb) = timeSeriesDb("multi_ts", responses = listOf("TP", "WIP"))
        val doc = kdb.toTimeSeriesReport("E")
        assertEquals("Time Series — E", doc.title)
        val titles = doc.sectionTitles()
        assertTrue("Time Series Across Replications — TP" in titles)
        assertTrue("Time Series Across Replications — WIP" in titles)
        // One mean-trajectory plot per response.
        assertEquals(2, doc.plotCount())
    }

    @Test
    fun `unknown response emits a notice paragraph and no plots`() {
        val (_, kdb) = timeSeriesDb("unknown_ts", responses = listOf("TP"))
        val doc = report("t") { dbTimeSeriesAcrossReplication(kdb, "E", "Nope") }
        assertEquals(0, doc.plotCount())
        assertTrue(doc.paragraphTexts().any { it.contains("No time-series data") })
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

    private fun ReportNode.plotCount(): Int =
        walk().count { it is ReportNode.PlotNode }

    private fun ReportNode.dataTableCount(): Int =
        walk().count { it is ReportNode.DataTable }

    private fun ReportNode.paragraphTexts(): List<String> =
        walk().filterIsInstance<ReportNode.Paragraph>().map { it.text }.toList()

    // ── Test fixtures ─────────────────────────────────────────────────────

    /**
     *  One experiment "E", one simulation run, three replications, three
     *  periods each, for every response name in [responses].  Values vary by
     *  rep and period so the per-period statistics and the overlay are
     *  non-trivial.
     */
    private fun timeSeriesDb(name: String, responses: List<String>): Pair<Database, KSLDatabase> {
        val database = KSLDatabase.createSQLiteKSLDatabase(name, tempDir)
        val kdb = KSLDatabase(database)
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 3)
        responses.forEachIndexed { rIdx, responseName ->
            val elementId = rIdx + 1
            insertModelElement(database, expId, elementId, responseName, "TimeSeriesResponse")
            for (rep in 1..3) {
                for (period in 1..3) {
                    insertTimeSeries(
                        database, runId, elementId, responseName, rep, period,
                        startTime = (period - 1) * 10.0,
                        endTime = period * 10.0,
                        value = rIdx * 100.0 + rep * 10.0 + period
                    )
                }
            }
        }
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

    private fun insertModelElement(
        database: Database, expId: Int, elementId: Int, name: String, className: String
    ) {
        val record = ModelElementTableData().apply {
            exp_id_fk = expId; element_id = elementId; element_name = name
            class_name = className; left_count = 1; right_count = 2
        }
        database.insertDbDataIntoTable(record)
    }

    private fun insertTimeSeries(
        database: Database, runId: Int, elementId: Int, statName: String,
        repId: Int, period: Int, startTime: Double, endTime: Double, value: Double
    ) {
        val record = TimeSeriesResponseTableData().apply {
            this.element_id_fk = elementId
            this.sim_run_id_fk = runId
            this.rep_id = repId
            this.stat_name = statName
            this.period = period
            this.start_time = startTime
            this.end_time = endTime
            this.length = endTime - startTime
            this.value = value
        }
        database.insertDbDataIntoTable(record)
    }
}
