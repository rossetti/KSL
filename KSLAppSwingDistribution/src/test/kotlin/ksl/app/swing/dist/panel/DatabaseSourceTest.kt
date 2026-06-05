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

package ksl.app.swing.dist.panel

import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.DbType
import ksl.app.dist.data.DatasetImporter
import ksl.utilities.io.dbutil.SQLiteDb
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the GUI database path: tables are enumerated from a real embedded
 * SQLite database (so the user selects rather than types), and the reference
 * built from the selections imports correctly through the substrate.
 */
class DatabaseSourceTest {

    private fun createDb(): Path {
        val dir = Files.createTempDirectory("dbsource")
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
                "INSERT INTO long_t VALUES ('red', 2.0)"
            )
        )
        return dir.resolve("t.db")
    }

    @Test
    fun `listTablesWithColumns enumerates tables, columns, and numeric flags`() {
        val tables = DatabaseSource.listTablesWithColumns(DbType.SQLITE, createDb())
        assertTrue("wide" in tables.keys, "expected a 'wide' table; got ${tables.keys}")
        assertTrue("long_t" in tables.keys, "expected a 'long_t' table; got ${tables.keys}")
        val wide = tables["wide"]!!.associate { it.name to it.numeric }
        assertEquals(setOf("a", "b", "label"), wide.keys)
        assertTrue(wide["a"] == true, "a (REAL) should be numeric")
        assertTrue(wide["b"] == true, "b (INTEGER) should be numeric")
        assertTrue(wide["label"] == false, "label (TEXT) should not be numeric")
    }

    @Test
    fun `previewTable returns the header and first rows`() {
        val text = DatabaseSource.previewTable(DbType.SQLITE, createDb(), "wide", maxRows = 5)
        assertTrue(
            text.contains("a") && text.contains("b") && text.contains("label"),
            "preview should include the column headers; got:\n$text"
        )
        assertTrue(text.contains("1.5"), "preview should include row data; got:\n$text")
    }

    @Test
    fun `buildRef WIDE imports one dataset per numeric column`() {
        val ref = DatabaseSource.buildRef(
            DbType.SQLITE, createDb().toString(), "wide", DatasetLayout.WIDE,
            datasetColumns = null, idColumn = null, valueColumn = null
        )
        val result = DatasetImporter.default.import(ref)
        assertEquals(listOf("a", "b"), result.map { it.name }) // text column skipped
        assertContentEquals(doubleArrayOf(1.5, 2.5, 3.5), result.first { it.name == "a" }.data)
    }

    @Test
    fun `buildRef LONG groups by id column`() {
        val ref = DatabaseSource.buildRef(
            DbType.SQLITE, createDb().toString(), "long_t", DatasetLayout.LONG,
            datasetColumns = null, idColumn = "grp", valueColumn = "value"
        )
        val result = DatasetImporter.default.import(ref)
        assertEquals(listOf("red", "blue"), result.map { it.name })
        assertContentEquals(doubleArrayOf(1.0, 2.0), result[0].data)
    }
}
