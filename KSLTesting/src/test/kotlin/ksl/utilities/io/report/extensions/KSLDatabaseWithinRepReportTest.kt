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
import ksl.utilities.io.dbutil.WithinRepStatTableData
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Structural tests for the within-replication report extensions.  Each test
 *  hand-writes a small graph of rows into a fresh on-disk SQLite KSL database
 *  in a JUnit `@TempDir` (no `Model` involved), invokes an extension, and walks
 *  the returned [ReportNode] AST to assert the expected sections, statistics
 *  tables, and plots are present.
 */
class KSLDatabaseWithinRepReportTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `full diagnostics report contains all sections and four plots`() {
        val (_, kdb) = oneExperimentDb("full_within")
        val doc = kdb.toWithinReplicationReport("E", "Y")

        assertEquals("Within-Replication — Y (E)", doc.title)

        val titles = doc.sectionTitles()
        assertTrue("Within-Replication Diagnostics — Y" in titles, "missing top section; got $titles")
        assertTrue("Histogram" in titles)
        assertTrue("Observations" in titles)
        assertTrue("Normality" in titles)
        assertTrue("Q-Q Plot" in titles)
        assertTrue("P-P Plot" in titles)

        // histogram + observations + Q-Q + P-P
        assertEquals(4, doc.plotCount(), "unexpected plot count")
        // The composite emits one across-replication statistics table plus the
        // histogram's own statistics table.
        assertTrue(doc.statTableCount() >= 1, "expected at least one statistics table")
    }

    @Test
    fun `histogram-only section emits a stat table and a plot`() {
        val (_, kdb) = oneExperimentDb("hist_only")
        val doc = report("t") { dbResponseHistogram(kdb, "E", "Y") }
        assertEquals(1, doc.plotCount())
        assertTrue("Histogram — Y" in doc.sectionTitles())
        assertTrue(doc.statTableCount() >= 1)
    }

    @Test
    fun `histogram plot can be suppressed`() {
        val (_, kdb) = oneExperimentDb("hist_no_plot")
        val doc = report("t") { dbResponseHistogram(kdb, "E", "Y", showPlot = false) }
        assertEquals(0, doc.plotCount())
        // The statistics table still renders.
        assertTrue(doc.statTableCount() >= 1)
    }

    @Test
    fun `normality section produces Q-Q and P-P plots`() {
        val (_, kdb) = oneExperimentDb("normality")
        val doc = report("t") { dbResponseNormality(kdb, "E", "Y") }
        assertEquals(2, doc.plotCount())
        val titles = doc.sectionTitles()
        assertTrue("Q-Q Plot" in titles)
        assertTrue("P-P Plot" in titles)
    }

    @Test
    fun `zero-variance sample suppresses the normality plots`() {
        val (database, kdb) = emptyDb("zero_var")
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 4)
        insertModelElement(database, expId, elementId = 1, name = "Y", className = "Response")
        // All replications identical -> variance 0 -> reference normal undefined.
        for (rep in 1..4) {
            insertWithinRepResponse(database, runId, elementId = 1, statName = "Y", repId = rep, avg = 7.0)
        }
        val doc = report("t") { dbResponseNormality(kdb, "E", "Y") }
        assertEquals(0, doc.plotCount(), "no plots expected for a degenerate sample")
    }

    @Test
    fun `unknown response emits a notice paragraph and no plots`() {
        val (_, kdb) = oneExperimentDb("unknown_resp")
        val doc = report("t") { dbWithinReplicationDiagnostics(kdb, "E", "Nonexistent") }
        assertEquals(0, doc.plotCount())
        assertTrue(
            doc.paragraphTexts().any { it.contains("No within-replication data") },
            "expected a no-data notice"
        )
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

    private fun ReportNode.statTableCount(): Int =
        walk().count { it is ReportNode.StatTable }

    private fun ReportNode.paragraphTexts(): List<String> =
        walk().filterIsInstance<ReportNode.Paragraph>().map { it.text }.toList()

    // ── Test fixtures ─────────────────────────────────────────────────────

    /** One experiment "E" recording response "Y" over six replications with
     *  distinct values (positive variance so the normal reference is defined). */
    private fun oneExperimentDb(name: String): Pair<Database, KSLDatabase> {
        val (database, kdb) = emptyDb(name)
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 6)
        insertModelElement(database, expId, elementId = 1, name = "Y", className = "Response")
        val values = doubleArrayOf(10.0, 12.0, 11.0, 14.0, 9.0, 13.0)
        values.forEachIndexed { i, v ->
            insertWithinRepResponse(database, runId, elementId = 1, statName = "Y", repId = i + 1, avg = v)
        }
        return database to kdb
    }

    private fun emptyDb(name: String): Pair<Database, KSLDatabase> {
        val database = KSLDatabase.createSQLiteKSLDatabase(name, tempDir)
        return database to KSLDatabase(database)
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

    private fun insertWithinRepResponse(
        database: Database, runId: Int, elementId: Int, statName: String, repId: Int, avg: Double
    ) {
        val record = WithinRepStatTableData().apply {
            this.element_id_fk = elementId; this.sim_run_id_fk = runId
            this.rep_id = repId; this.stat_name = statName; this.average = avg
        }
        database.insertDbDataIntoTable(record)
    }
}
