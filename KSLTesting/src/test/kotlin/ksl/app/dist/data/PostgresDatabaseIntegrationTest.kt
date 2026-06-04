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

package ksl.app.dist.data

import ksl.app.dist.config.CredentialSource
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DatabaseConnectionRef
import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.DbSource
import ksl.app.dist.config.DbType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Live Postgres integration test, disabled by default. It is not part of the
 * normal (CI) suite because it requires a running Postgres server.
 *
 * To run it locally, set:
 *   KSL_PG_TEST=1
 *   KSL_PG_DB        (database name; default "postgres")
 *   KSL_PG_HOST      (default "localhost")
 *   KSL_PG_PORT      (default 5432)
 *   KSL_PG_USER      (default "postgres")
 *   KSL_PG_PASSWORD  (default "")
 * The test creates a temporary table, reads it back through the Database
 * source, and drops it. Credentials are supplied via the Environment
 * credential source (KSL_PG_USER / KSL_PG_PASSWORD).
 */
@EnabledIfEnvironmentVariable(named = "KSL_PG_TEST", matches = "1")
class PostgresDatabaseIntegrationTest {

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private fun connection() = DatabaseConnectionRef(
        dbType = DbType.POSTGRES,
        location = env("KSL_PG_DB", "postgres"),
        serverName = env("KSL_PG_HOST", "localhost"),
        portNumber = env("KSL_PG_PORT", "5432").toInt(),
        // KSL_PG_USER / KSL_PG_PASSWORD resolved by DefaultCredentialResolver.
        credentials = CredentialSource.Environment("KSL_PG_USER", "KSL_PG_PASSWORD")
    )

    private fun openRawDb(): ksl.utilities.io.dbutil.Database {
        val ds = ksl.utilities.io.dbutil.PostgresDb.createDataSource(
            dbServerName = env("KSL_PG_HOST", "localhost"),
            dbName = env("KSL_PG_DB", "postgres"),
            user = env("KSL_PG_USER", "postgres"),
            pWord = env("KSL_PG_PASSWORD", ""),
            portNumber = env("KSL_PG_PORT", "5432").toInt()
        )
        return ksl.utilities.io.dbutil.Database(ds, "ksl_pg_it")
    }

    @Test
    fun `reads a wide table from a live postgres server`() {
        val table = "ksl_dist_it_wide"
        val db = openRawDb()
        db.executeCommand("DROP TABLE IF EXISTS $table")
        db.executeCommand("CREATE TABLE $table (a double precision, b integer)")
        db.executeCommands(
            listOf(
                "INSERT INTO $table VALUES (1.5, 10)",
                "INSERT INTO $table VALUES (2.5, 20)",
                "INSERT INTO $table VALUES (3.5, 30)"
            )
        )
        try {
            val ref = DataSourceReference.Database(
                connection = connection(),
                source = DbSource.Table(table),
                layout = DatasetLayout.WIDE
            )
            val result = DatasetImporter.default.import(ref)
            assertEquals(setOf("a", "b"), result.map { it.name }.toSet())
            assertContentEquals(doubleArrayOf(1.5, 2.5, 3.5), result.first { it.name == "a" }.data)
            assertContentEquals(doubleArrayOf(10.0, 20.0, 30.0), result.first { it.name == "b" }.data)
        } finally {
            db.executeCommand("DROP TABLE IF EXISTS $table")
        }
    }
}
