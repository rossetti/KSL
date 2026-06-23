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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Integration test for [ResultsAppController.connectPostgres] against a
 *  **live local Postgres** KSL database.
 *
 *  This requires a Postgres server on `localhost:5432` with a `test`
 *  database (user `test`, password `test`) that holds a `ksl_db` schema —
 *  exactly the setup used by `ksl.examples.general.utilities.PostgresKSLDbExample`.
 *
 *  **Hard-gated on the `KSL_PG_TEST=1` environment variable** (matching
 *  `ksl.app.dist.data.PostgresDatabaseIntegrationTest`). Without it, JUnit
 *  never instantiates the test, so it makes **no** network/connection
 *  attempt — important because the connection probe below would otherwise
 *  reach out to localhost:5432 on every full-suite run even when it ends up
 *  skipping. With the gate on but the server unavailable, the inner
 *  assumption still skips gracefully rather than failing.
 */
@EnabledIfEnvironmentVariable(named = "KSL_PG_TEST", matches = "1")
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
