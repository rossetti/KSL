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

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Integration test for [ResultsAppController.connectPostgres] against a
 *  **live local Postgres** KSL database.
 *
 *  This requires a Postgres server on `localhost:5432` with a `test`
 *  database (user `test`, password `test`) that holds a `ksl_db` schema —
 *  exactly the setup used by `ksl.examples.general.utilities.PostgresKSLDbExample`.
 *  When that is unavailable (e.g. CI, or a machine without the server),
 *  the test **skips** itself via a JUnit assumption rather than failing,
 *  so it is safe to keep in the suite.
 */
class ResultsAppControllerPostgresTest {

    @Test
    fun `connects to a local Postgres KSL database when available`() {
        val controller = ResultsAppController("test")
        val connected = try {
            controller.connectPostgres(
                PostgresConnectionSpec(
                    server = "localhost",
                    port = 5432,
                    databaseName = "test",
                    user = "test",
                    password = "test"
                )
            )
            true
        } catch (t: Throwable) {
            println("Skipping Postgres integration test — not available: ${t.message}")
            false
        }
        Assumptions.assumeTrue(connected, "Local Postgres 'test' KSL database not available")

        assertTrue(controller.isDatabaseOpen)
        assertEquals("Postgres", controller.databaseKind)
        assertEquals("test@localhost", controller.databaseDisplayName)
    }
}
