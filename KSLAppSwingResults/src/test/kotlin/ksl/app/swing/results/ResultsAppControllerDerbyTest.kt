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

import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.SimulationRunTableData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Regression test for opening an **embedded Derby** KSL database through
 *  [ResultsAppController].
 *
 *  KSL stores its tables in the `KSL_DB` schema, but `DerbyDb.openDatabase`
 *  defaults the connection's schema to `"APP"`.  Without re-pointing the
 *  schema, `KSLDatabase`'s table-existence check looks in `APP`, finds
 *  nothing, and wrongly reports the database as "not a KSLDatabase".  This
 *  test creates a Derby KSL database (as the Pallet-Processing example does)
 *  and confirms the controller opens it.
 *
 *  Embedded Derby permits multiple connections from the same JVM, so the
 *  creating connection and the controller's open connection coexist.
 */
class ResultsAppControllerDerbyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `opens an embedded Derby KSL database without reporting not-configured`() {
        val dbName = "PalletDerby"
        // Create a Derby KSL database (schema KSL_DB) and seed one experiment.
        val created = KSLDatabase.createEmbeddedDerbyKSLDatabase(dbName, tempDir)
        val expRecord = ExperimentTableData().apply {
            sim_name = "PalletSim"; model_name = "PalletModel"; exp_name = "E"
        }
        created.insertDbDataIntoTable(expRecord)
        created.insertDbDataIntoTable(SimulationRunTableData().apply {
            exp_id_fk = expRecord.exp_id; run_name = "E_run"; num_reps = 1
            start_rep_id = 1; last_rep_id = 1
        })

        // The Derby database is a directory containing service.properties.
        val controller = ResultsAppController("test")
        controller.openDatabase(File(tempDir.toFile(), dbName))

        assertTrue(controller.isDatabaseOpen, "controller should have opened the Derby database")
        assertEquals(listOf("E"), controller.experiments().map { it.name })
    }
}
