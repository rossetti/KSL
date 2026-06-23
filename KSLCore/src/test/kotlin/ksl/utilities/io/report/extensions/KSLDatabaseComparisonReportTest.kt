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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Structural tests for [KSLDatabase.toComparisonReport].
 *
 *  Each test hand-writes a small graph of rows into a fresh on-disk
 *  SQLite KSL database in a JUnit `@TempDir` (no `Model` involved),
 *  calls the entry point, and walks the returned [ReportNode.Document]
 *  AST to assert the expected sections and plots are present.  The
 *  statistical correctness of the multiple-comparison content is
 *  covered by the [MultipleComparisonAnalyzer] and `multipleComparison`
 *  tests; here we only verify the database-to-document composition.
 */
class KSLDatabaseComparisonReportTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `full report contains the MCB sections and all four plots`() {
        val (_, kdb) = threeExperimentDb("full_report")
        val doc = kdb.toComparisonReport("Y", listOf("A", "B", "C"))

        assertEquals("Comparison — Y", doc.title)

        val titles = doc.sectionTitles()
        assertTrue("MCB Max Intervals" in titles, "missing MCB Max section; got $titles")
        assertTrue("MCB Min Intervals" in titles, "missing MCB Min section; got $titles")
        assertTrue("Screening" in titles, "missing Screening section; got $titles")
        assertTrue("Pairwise Differences" in titles, "missing Pairwise section; got $titles")
        assertTrue("Alternative Confidence Intervals" in titles, "missing alt CI section; got $titles")
        assertTrue("Response Distributions" in titles, "missing box-plot section; got $titles")

        // Four plots with both directions and both optional plots on:
        // alt-CI, box plot, MCB max, MCB min.
        assertEquals(4, doc.plotCount(), "unexpected plot count")
    }

    @Test
    fun `direction MAX omits the MCB min section`() {
        val (_, kdb) = threeExperimentDb("max_only")
        val doc = kdb.toComparisonReport(
            "Y", listOf("A", "B", "C"),
            direction = MCBDirection.MAX
        )
        val titles = doc.sectionTitles()
        assertTrue("MCB Max Intervals" in titles)
        assertFalse("MCB Min Intervals" in titles)
    }

    @Test
    fun `plots can be suppressed`() {
        val (_, kdb) = threeExperimentDb("no_plots")
        val doc = kdb.toComparisonReport(
            "Y", listOf("A", "B", "C"),
            direction = MCBDirection.MAX,
            showAltCIPlot = false,
            showBoxPlot = false
        )
        // Only the single MCB Max interval plot remains.
        assertEquals(1, doc.plotCount())
        val titles = doc.sectionTitles()
        assertFalse("Alternative Confidence Intervals" in titles)
        assertFalse("Response Distributions" in titles)
    }

    @Test
    fun `custom title is honored`() {
        val (_, kdb) = threeExperimentDb("custom_title")
        val doc = kdb.toComparisonReport("Y", listOf("A", "B", "C"), title = "Queue Study")
        assertEquals("Queue Study", doc.title)
    }

    @Test
    fun `unknown experiment name throws`() {
        val (_, kdb) = threeExperimentDb("unknown_exp")
        assertFailsWith<IllegalArgumentException> {
            kdb.toComparisonReport("Y", listOf("A", "Nope"))
        }
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

    // ── Test fixtures ─────────────────────────────────────────────────────

    /**
     *  Three experiments A, B, C, each recording the response "Y" over
     *  four replications with distinct, separated means so the MCB
     *  intervals and screening produce non-trivial content.
     */
    private fun threeExperimentDb(name: String): Pair<Database, KSLDatabase> {
        val database = KSLDatabase.createSQLiteKSLDatabase(name, tempDir)
        val kdb = KSLDatabase(database)
        val means = mapOf("A" to 10.0, "B" to 20.0, "C" to 30.0)
        for ((expName, base) in means) {
            val expId = insertExperiment(database, expName, "M")
            val runId = insertSimRun(database, expId, "${expName}_run", numReps = 4)
            insertModelElement(database, expId, elementId = 1, name = "Y", className = "Response")
            for (rep in 1..4) {
                insertWithinRepResponse(database, runId, elementId = 1,
                    statName = "Y", repId = rep, avg = base + rep)
            }
        }
        return database to kdb
    }

    private fun insertExperiment(database: Database, expName: String, modelName: String): Int {
        val record = ExperimentTableData().apply {
            sim_name = "TestSim"
            model_name = modelName
            exp_name = expName
        }
        database.insertDbDataIntoTable(record)
        return record.exp_id
    }

    private fun insertSimRun(database: Database, expId: Int, runName: String, numReps: Int): Int {
        val record = SimulationRunTableData().apply {
            exp_id_fk = expId
            run_name = runName
            num_reps = numReps
            start_rep_id = 1
            last_rep_id = numReps
        }
        database.insertDbDataIntoTable(record)
        return record.run_id
    }

    private fun insertModelElement(
        database: Database, expId: Int, elementId: Int, name: String, className: String
    ) {
        // tblModelElement enforces left_count > 0, right_count > 1,
        // left_count < right_count.  Use the minimal valid pair.
        val record = ModelElementTableData().apply {
            exp_id_fk = expId
            element_id = elementId
            element_name = name
            class_name = className
            left_count = 1
            right_count = 2
        }
        database.insertDbDataIntoTable(record)
    }

    private fun insertWithinRepResponse(
        database: Database, runId: Int, elementId: Int, statName: String, repId: Int, avg: Double
    ) {
        val record = WithinRepStatTableData().apply {
            this.element_id_fk = elementId
            this.sim_run_id_fk = runId
            this.rep_id = repId
            this.stat_name = statName
            this.average = avg
        }
        database.insertDbDataIntoTable(record)
    }
}
