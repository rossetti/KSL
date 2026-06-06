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
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.ModelElementTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.WithinRepStatTableData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Headless tests for [ResultsExportController].  Each opens a real
 *  on-disk SQLite KSL database through the app controller and exports it
 *  to a temp directory, asserting the expected files appear.  No Swing
 *  component is constructed.
 */
class ResultsExportControllerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reports cannot-export before a database is open`() {
        val export = ResultsExportController(ResultsAppController("test"))
        assertFalse(export.canExport)
        val outcome = export.csvAllTables(tempDir.resolve("out"))
        assertFalse(outcome.ok)
    }

    @Test
    fun `excel export writes a workbook`() {
        val export = openExport("xlsx_db")
        val dir = tempDir.resolve("excel")
        val outcome = export.excelAllTables(dir)
        assertTrue(outcome.ok, outcome.message)
        assertTrue(listFiles(dir).any { it.endsWith(".xlsx") }, "no .xlsx written: ${listFiles(dir)}")
    }

    @Test
    fun `csv all-tables export writes csv files`() {
        val export = openExport("csv_db")
        val dir = tempDir.resolve("csv")
        val outcome = export.csvAllTables(dir)
        assertTrue(outcome.ok, outcome.message)
        assertTrue(listFiles(dir).any { it.endsWith(".csv") }, "no .csv written: ${listFiles(dir)}")
    }

    @Test
    fun `csv all-views export writes csv files`() {
        val export = openExport("views_db")
        val dir = tempDir.resolve("views")
        val outcome = export.csvAllViews(dir)
        assertTrue(outcome.ok, outcome.message)
        assertTrue(listFiles(dir).any { it.endsWith(".csv") }, "no .csv written: ${listFiles(dir)}")
    }

    @Test
    fun `sql dump writes an inserts file`() {
        val export = openExport("sql_db")
        val dir = tempDir.resolve("sql")
        val outcome = export.sqlDumpAllTables(dir)
        assertTrue(outcome.ok, outcome.message)
        assertTrue(listFiles(dir).any { it.endsWith(".sql") }, "no .sql written: ${listFiles(dir)}")
    }

    @Test
    fun `selected-tables markdown export writes one file per table`() {
        val export = openExport("md_db")
        val dir = tempDir.resolve("md")
        val outcome = export.export(
            ExportFormat.MARKDOWN, ExportScope.SELECTED_TABLES,
            selected = listOf("experiment", "within_rep_stat"), dir = dir
        )
        assertTrue(outcome.ok, outcome.message)
        val files = listFiles(dir)
        assertTrue(files.contains("experiment.md"), "got $files")
        assertTrue(files.contains("within_rep_stat.md"), "got $files")
    }

    @Test
    fun `selected scope with empty selection fails`() {
        val export = openExport("empty_sel_db")
        val outcome = export.export(
            ExportFormat.CSV, ExportScope.SELECTED_TABLES,
            selected = emptyList(), dir = tempDir.resolve("nope")
        )
        assertFalse(outcome.ok)
    }

    @Test
    fun `excel rejects a view scope`() {
        val export = openExport("excel_views_db")
        val outcome = export.export(
            ExportFormat.EXCEL, ExportScope.ALL_VIEWS,
            selected = emptyList(), dir = tempDir.resolve("nope2")
        )
        assertFalse(outcome.ok)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun openExport(dbName: String): ResultsExportController {
        buildDb(dbName)
        val controller = ResultsAppController("test")
        controller.openDatabase(File(tempDir.toFile(), dbName))
        return ResultsExportController(controller)
    }

    private fun listFiles(dir: Path): List<String> =
        if (Files.isDirectory(dir)) dir.toFile().list()?.toList() ?: emptyList() else emptyList()

    private fun buildDb(dbName: String) {
        val database = KSLDatabase.createSQLiteKSLDatabase(dbName, tempDir)
        val expId = insertExperiment(database, "E", "M")
        val runId = insertSimRun(database, expId, "E_run", numReps = 3)
        insertModelElement(database, expId, elementId = 1, name = "Y", className = "Response")
        for (rep in 1..3) {
            insertWithinRepResponse(database, runId, elementId = 1, statName = "Y", repId = rep, avg = rep.toDouble())
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
}
