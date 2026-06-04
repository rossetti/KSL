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

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DatabaseConnectionRef
import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.DbSource
import ksl.app.dist.config.DbType
import ksl.utilities.io.dbutil.SQLiteDb
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseDataReaderTest {

    /** Creates a temp SQLite db with a WIDE table and a LONG table; returns the db file path. */
    private fun createDb(): Path {
        val dir = Files.createTempDirectory("dbreader")
        val db = SQLiteDb("t.db", dir, deleteIfExists = true)
        db.executeCommand("CREATE TABLE wide (a REAL, b INTEGER, label TEXT)")
        db.executeCommands(
            listOf(
                "INSERT INTO wide VALUES (1.5, 10, 'x')",
                "INSERT INTO wide VALUES (2.5, 20, 'y')",
                "INSERT INTO wide VALUES (3.5, 30, 'z')"
            )
        )
        db.executeCommand("CREATE TABLE long_t (grp TEXT, value REAL)")
        db.executeCommands(
            listOf(
                "INSERT INTO long_t VALUES ('red', 1.0)",
                "INSERT INTO long_t VALUES ('blue', 9.0)",
                "INSERT INTO long_t VALUES ('red', 2.0)",
                "INSERT INTO long_t VALUES ('blue', 8.0)",
                "INSERT INTO long_t VALUES ('red', 3.0)"
            )
        )
        return dir.resolve("t.db")
    }

    private fun connection(path: Path) = DatabaseConnectionRef(dbType = DbType.SQLITE, location = path.toString())

    @Test
    fun `wide table yields one dataset per numeric column and skips text`() {
        val ref = DataSourceReference.Database(
            connection = connection(createDb()),
            source = DbSource.Table("wide"),
            layout = DatasetLayout.WIDE
        )
        val result = DatasetImporter.default.import(ref)
        assertEquals(listOf("a", "b"), result.map { it.name }) // 'label' (TEXT) skipped
        assertContentEquals(doubleArrayOf(1.5, 2.5, 3.5), result.first { it.name == "a" }.data)
        // INTEGER column converted to Double
        assertContentEquals(doubleArrayOf(10.0, 20.0, 30.0), result.first { it.name == "b" }.data)
    }

    @Test
    fun `wide datasetColumns filter selects a subset in order`() {
        val ref = DataSourceReference.Database(
            connection = connection(createDb()),
            source = DbSource.Table("wide"),
            layout = DatasetLayout.WIDE,
            datasetColumns = listOf("b", "a")
        )
        val result = DatasetImporter.default.import(ref)
        assertEquals(listOf("b", "a"), result.map { it.name })
    }

    @Test
    fun `wide filter naming a non-numeric column is rejected`() {
        val ref = DataSourceReference.Database(
            connection = connection(createDb()),
            source = DbSource.Table("wide"),
            layout = DatasetLayout.WIDE,
            datasetColumns = listOf("a", "label")
        )
        assertThrows<ImportException> { DatasetImporter.default.import(ref) }
    }

    @Test
    fun `long table groups by id column in first-seen order`() {
        val ref = DataSourceReference.Database(
            connection = connection(createDb()),
            source = DbSource.Table("long_t"),
            layout = DatasetLayout.LONG,
            idColumn = "grp",
            valueColumn = "value"
        )
        val result = DatasetImporter.default.import(ref)
        assertEquals(listOf("red", "blue"), result.map { it.name })
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), result[0].data)
        assertContentEquals(doubleArrayOf(9.0, 8.0), result[1].data)
    }

    @Test
    fun `query source reads selected columns`() {
        val ref = DataSourceReference.Database(
            connection = connection(createDb()),
            source = DbSource.Query("SELECT a FROM wide"),
            layout = DatasetLayout.WIDE
        )
        val result = DatasetImporter.default.import(ref)
        assertEquals(listOf("a"), result.map { it.name })
        assertContentEquals(doubleArrayOf(1.5, 2.5, 3.5), result[0].data)
    }

    @Test
    fun `long layout requires id and value columns`() {
        val ref = DataSourceReference.Database(
            connection = connection(createDb()),
            source = DbSource.Table("long_t"),
            layout = DatasetLayout.LONG,
            valueColumn = "value" // missing idColumn
        )
        assertThrows<ImportException> { DatasetImporter.default.import(ref) }
    }

    @Test
    fun `server database type is rejected in this phase`() {
        val ref = DataSourceReference.Database(
            connection = DatabaseConnectionRef(dbType = DbType.POSTGRES, location = "mydb", serverName = "localhost"),
            source = DbSource.Table("wide")
        )
        val ex = assertThrows<ImportException> { DatasetImporter.default.import(ref) }
        assertTrue(ex.message!!.contains("server"))
    }
}
