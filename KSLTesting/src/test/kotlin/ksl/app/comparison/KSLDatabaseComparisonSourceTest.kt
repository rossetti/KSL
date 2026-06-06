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

package ksl.app.comparison

import ksl.utilities.io.dbutil.Database
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.ModelElementTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.WithinRepCounterStatTableData
import ksl.utilities.io.dbutil.WithinRepStatTableData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Adapter tests for [KSLDatabaseComparisonSource].
 *
 *  Each test creates a fresh on-disk SQLite KSL database in a JUnit
 *  `@TempDir`, hand-writes a small graph of rows through the standard
 *  [Database.insertDbDataIntoTable] path (no `Model` involved), and
 *  asserts the adapter's [ComparisonDataSourceIfc] surface matches.
 *
 *  Test names follow the same back-tick convention as
 *  [BatchCompletedComparisonSourceTest].
 */
class KSLDatabaseComparisonSourceTest {

    @TempDir
    lateinit var tempDir: Path

    // ── Test cases ────────────────────────────────────────────────────────

    @Test
    fun `empty database yields empty experiments`() {
        val (_, kdb) = makeDb("empty_db")
        val src = KSLDatabaseComparisonSource(kdb)
        assertTrue(src.availableExperiments().isEmpty())
    }

    @Test
    fun `each experiment surfaces as one ExperimentRow with classified responses`() {
        val (database, kdb) = makeDb("mixed_db")

        // S1 — Response + TWResponse + Counter mixed in one experiment.
        val s1ExpId = insertExperiment(database, "S1", "MM1")
        val s1RunId = insertSimRun(database, s1ExpId, "S1_run", numReps = 3)
        insertModelElement(database, s1ExpId, elementId = 1, name = "SystemTime", className = "Response")
        insertModelElement(database, s1ExpId, elementId = 2, name = "NumBusy", className = "TWResponse")
        insertModelElement(database, s1ExpId, elementId = 3, name = "NumServed", className = "Counter")
        for (rep in 1..3) {
            insertWithinRepResponse(database, s1RunId, elementId = 1,
                statName = "SystemTime", repId = rep, avg = rep.toDouble())
            insertWithinRepResponse(database, s1RunId, elementId = 2,
                statName = "NumBusy", repId = rep, avg = 0.4 + 0.1 * rep)
            insertWithinRepCounter(database, s1RunId, elementId = 3,
                statName = "NumServed", repId = rep, lastValue = 95.0 + 5.0 * rep)
        }

        // S2 — only a single response, exercises the partial-overlap case.
        val s2ExpId = insertExperiment(database, "S2", "MM1")
        val s2RunId = insertSimRun(database, s2ExpId, "S2_run", numReps = 3)
        insertModelElement(database, s2ExpId, elementId = 1, name = "SystemTime", className = "Response")
        for (rep in 1..3) {
            insertWithinRepResponse(database, s2RunId, elementId = 1,
                statName = "SystemTime", repId = rep, avg = 5.0 * rep)
        }

        val src = KSLDatabaseComparisonSource(kdb)
        val exps = src.availableExperiments()
        assertEquals(listOf("S1", "S2"), exps.map { it.name })

        val s1Row = exps.first { it.name == "S1" }
        assertEquals("MM1", s1Row.modelIdentifier)
        assertEquals(3, s1Row.numReplications)

        val s1Categories = s1Row.responses.associate { it.name to it.category }
        assertEquals(ResponseCategory.OBSERVATION, s1Categories["SystemTime"])
        assertEquals(ResponseCategory.TIME_WEIGHTED, s1Categories["NumBusy"])
        assertEquals(ResponseCategory.COUNTER, s1Categories["NumServed"])
        assertEquals(setOf("SystemTime", "NumBusy", "NumServed"), s1Categories.keys)

        val s2Row = exps.first { it.name == "S2" }
        assertEquals(1, s2Row.responses.size)
        assertEquals("SystemTime", s2Row.responses.single().name)
        assertEquals(ResponseCategory.OBSERVATION, s2Row.responses.single().category)
    }

    @Test
    fun `observations pull per-rep averages for a Response`() {
        val (database, kdb) = makeDb("obs_response")
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 3)
        insertModelElement(database, expId, elementId = 1, name = "X", className = "Response")
        // Insert in reverse rep order to confirm the underlying view
        // sorts by rep_id before returning.
        insertWithinRepResponse(database, runId, elementId = 1, statName = "X", repId = 2, avg = 20.0)
        insertWithinRepResponse(database, runId, elementId = 1, statName = "X", repId = 1, avg = 10.0)
        insertWithinRepResponse(database, runId, elementId = 1, statName = "X", repId = 3, avg = 30.0)

        val src = KSLDatabaseComparisonSource(kdb)
        assertContentEquals(doubleArrayOf(10.0, 20.0, 30.0), src.observations("E", "X"))
    }

    @Test
    fun `observations pull last_value for a Counter`() {
        val (database, kdb) = makeDb("obs_counter")
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 3)
        insertModelElement(database, expId, elementId = 1, name = "NumServed", className = "Counter")
        for (rep in 1..3) {
            insertWithinRepCounter(database, runId, elementId = 1,
                statName = "NumServed", repId = rep, lastValue = 100.0 + rep)
        }

        val src = KSLDatabaseComparisonSource(kdb)
        assertContentEquals(doubleArrayOf(101.0, 102.0, 103.0), src.observations("E", "NumServed"))
    }

    @Test
    fun `observations returns null when the response was not recorded`() {
        val (database, kdb) = makeDb("unknown_resp")
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 1)
        insertModelElement(database, expId, elementId = 1, name = "X", className = "Response")
        insertWithinRepResponse(database, runId, elementId = 1, statName = "X", repId = 1, avg = 1.0)

        val src = KSLDatabaseComparisonSource(kdb)
        assertNull(src.observations("E", "Nonexistent"))
    }

    @Test
    fun `observations returns null when the experiment is unknown`() {
        val (database, kdb) = makeDb("unknown_exp")
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 1)
        insertModelElement(database, expId, elementId = 1, name = "X", className = "Response")
        insertWithinRepResponse(database, runId, elementId = 1, statName = "X", repId = 1, avg = 1.0)

        val src = KSLDatabaseComparisonSource(kdb)
        assertNull(src.observations("DoesNotExist", "X"))
    }

    @Test
    fun `default sourceLabel includes the database label`() {
        val (_, kdb) = makeDb("labeled_db")
        val src = KSLDatabaseComparisonSource(kdb)
        assertTrue(
            src.sourceLabel.contains(kdb.label),
            "expected sourceLabel to contain DB label '${kdb.label}', got: '${src.sourceLabel}'"
        )
        assertTrue(
            src.sourceLabel.startsWith("KSL database"),
            "unexpected sourceLabel prefix: '${src.sourceLabel}'"
        )
    }

    @Test
    fun `selection model gather and validate round-trip for an MCA-eligible response`() {
        val (database, kdb) = makeDb("round_trip")
        val aExpId = insertExperiment(database, "A", "M")
        val bExpId = insertExperiment(database, "B", "M")
        val aRunId = insertSimRun(database, aExpId, "A_run", numReps = 3)
        val bRunId = insertSimRun(database, bExpId, "B_run", numReps = 3)
        insertModelElement(database, aExpId, elementId = 1, name = "Y", className = "Response")
        insertModelElement(database, bExpId, elementId = 1, name = "Y", className = "Response")
        for (rep in 1..3) {
            insertWithinRepResponse(database, aRunId, elementId = 1,
                statName = "Y", repId = rep, avg = rep.toDouble())
            insertWithinRepResponse(database, bRunId, elementId = 1,
                statName = "Y", repId = rep, avg = rep + 10.0)
        }

        val src = KSLDatabaseComparisonSource(kdb)
        val model = ComparisonSelectionModel(listOf(src))
        model.selectAll()

        val gathered = model.gatherObservationsFor("Y")
        assertEquals(setOf("A", "B"), gathered.keys)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), gathered["A"])
        assertContentEquals(doubleArrayOf(11.0, 12.0, 13.0), gathered["B"])

        val v = model.validateForResponse("Y", AnalysisType.MULTIPLE_COMPARISON)
        assertTrue(v.ok, "validation should pass: ${v.reason}")
    }

    @Test
    fun `experiment with no per-rep data still surfaces with zero replications`() {
        val (database, kdb) = makeDb("empty_exp")
        insertExperiment(database, "EmptyExp", "M")
        val src = KSLDatabaseComparisonSource(kdb)
        val row = src.availableExperiments().single()
        assertEquals("EmptyExp", row.name)
        assertEquals(0, row.numReplications)
        assertTrue(row.responses.isEmpty())
    }

    // ── Test fixtures ─────────────────────────────────────────────────────

    /**
     *  Create a fresh on-disk SQLite KSL database in [tempDir] and
     *  return both the underlying [Database] (for direct row inserts)
     *  and the [KSLDatabase] facade (for the adapter under test).
     */
    private fun makeDb(name: String): Pair<Database, KSLDatabase> {
        val database = KSLDatabase.createSQLiteKSLDatabase(name, tempDir)
        val kdb = KSLDatabase(database)
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

    private fun insertSimRun(
        database: Database,
        expId: Int,
        runName: String,
        numReps: Int
    ): Int {
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
        database: Database,
        expId: Int,
        elementId: Int,
        name: String,
        className: String
    ) {
        // tblModelElement enforces CHECK (left_count > 0) and
        // CHECK (right_count > 1) plus left_count < right_count for
        // Joe-Celko nested-set traversal.  These tests do not exercise
        // model hierarchy, so use the minimal valid pair for every row.
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
        database: Database,
        runId: Int,
        elementId: Int,
        statName: String,
        repId: Int,
        avg: Double
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

    private fun insertWithinRepCounter(
        database: Database,
        runId: Int,
        elementId: Int,
        statName: String,
        repId: Int,
        lastValue: Double
    ) {
        val record = WithinRepCounterStatTableData().apply {
            this.element_id_fk = elementId
            this.sim_run_id_fk = runId
            this.rep_id = repId
            this.stat_name = statName
            this.last_value = lastValue
        }
        database.insertDbDataIntoTable(record)
    }
}
