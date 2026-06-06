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

package ksl.app.swing.results

import ksl.utilities.io.dbutil.Database
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.FrequencyTableData
import ksl.utilities.io.dbutil.HistogramTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.ModelElementTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.WithinRepStatTableData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Headless tests for [ResultsAppController] — the app's state holder.
 *  These exercise the database-open path end-to-end against a real
 *  on-disk SQLite KSL database (built by hand-writing rows, no `Model`),
 *  without constructing any Swing component, so they run in a headless
 *  CI environment.
 */
class ResultsAppControllerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `controller starts with no database open`() {
        val controller = ResultsAppController("test")
        assertFalse(controller.isDatabaseOpen)
        assertTrue(controller.experiments().isEmpty())
        assertEquals("No database open", controller.databaseSummary())
    }

    @Test
    fun `opening a KSL database populates experiments and the comparison source`() {
        val dbName = "ctrl_db"
        buildTwoExperimentDb(dbName)

        var notified = 0
        val controller = ResultsAppController("test")
        controller.addListener { notified++ }

        controller.openDatabase(File(tempDir.toFile(), dbName))

        assertTrue(controller.isDatabaseOpen)
        assertEquals(1, notified, "listeners should fire once on open")
        assertEquals(listOf("A", "B"), controller.experiments().map { it.name })
        assertNotNull(controller.comparisonSource)
        assertEquals(
            listOf("Y"),
            controller.experiments().first { it.name == "A" }.responses.map { it.name }
        )
        assertTrue(
            controller.databaseSummary().contains(dbName),
            "summary should name the DB: ${controller.databaseSummary()}"
        )
    }

    @Test
    fun `output directory is workspace-derived per database`() {
        val dbName = "ctrl_db"
        buildTwoExperimentDb(dbName)
        val controller = ResultsAppController("My App")
        controller.openDatabase(File(tempDir.toFile(), dbName))

        // <workspace>/My_App/output/ctrl_db/reports — assert the suffix
        // regardless of the user's workspace root, and create nothing.
        val out = controller.outputDir.toString().replace('\\', '/')
        assertTrue(
            out.contains("/My_App/output/$dbName/reports"),
            "unexpected outputDir: $out"
        )
    }

    @Test
    fun `histogram and frequency response names are listed`() {
        val dbName = "hist_freq_db"
        val database = KSLDatabase.createSQLiteKSLDatabase(dbName, tempDir)
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 3)
        insertHistogramBin(database, runId, elementId = 1, responseName = "SystemTime:Histogram", binNum = 1)
        insertHistogramBin(database, runId, elementId = 1, responseName = "SystemTime:Histogram", binNum = 2)
        insertFrequencyCell(database, runId, elementId = 2, name = "NQUponArrival", value = 0)
        insertFrequencyCell(database, runId, elementId = 2, name = "NQUponArrival", value = 1)

        val controller = ResultsAppController("test")
        controller.openDatabase(File(tempDir.toFile(), dbName))

        assertEquals(listOf("SystemTime:Histogram"), controller.histogramResponseNames("E"))
        assertEquals(listOf("NQUponArrival"), controller.frequencyResponseNames("E"))
    }

    // ── Fixture: a real on-disk SQLite KSL database ───────────────────────

    private fun buildTwoExperimentDb(dbName: String) {
        val database = KSLDatabase.createSQLiteKSLDatabase(dbName, tempDir)
        for (expName in listOf("A", "B")) {
            val expId = insertExperiment(database, expName, "M")
            val runId = insertSimRun(database, expId, "${expName}_run", numReps = 3)
            insertModelElement(database, expId, elementId = 1, name = "Y", className = "Response")
            for (rep in 1..3) {
                insertWithinRepResponse(database, runId, elementId = 1, statName = "Y", repId = rep, avg = rep.toDouble())
            }
        }
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

    private fun insertHistogramBin(
        database: Database, runId: Int, elementId: Int, responseName: String, binNum: Int
    ) {
        val record = HistogramTableData().apply {
            this.element_id_fk = elementId; this.sim_run_id_fk = runId
            this.response_id_fk = elementId; this.response_name = responseName
            this.bin_label = "bin$binNum"; this.bin_num = binNum
            this.bin_lower_limit = (binNum - 1).toDouble(); this.bin_upper_limit = binNum.toDouble()
            this.bin_count = 10.0; this.bin_cum_count = 10.0 * binNum
        }
        database.insertDbDataIntoTable(record)
    }

    private fun insertFrequencyCell(
        database: Database, runId: Int, elementId: Int, name: String, value: Int
    ) {
        val record = FrequencyTableData().apply {
            this.element_id_fk = elementId; this.sim_run_id_fk = runId
            this.name = name; this.cell_label = value.toString(); this.value = value
            this.count = 5.0; this.cum_count = 5.0 * (value + 1)
        }
        database.insertDbDataIntoTable(record)
    }
}
