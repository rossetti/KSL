/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.dbutil

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 *  Selecting from a table in a schema other than the default one. Before R1.8 the existence check looked in
 *  the default schema whatever schema was asked for, so a table that existed only in the named schema was
 *  reported missing: `selectAll` returned null, a CSV export wrote nothing, and `isTableEmpty` said true.
 */
class SchemaQualifiedSelectTest {

    private lateinit var dir: Path
    private lateinit var db: Database

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ksl_schema_select")
        db = DerbyDb.createDatabase("schemas", dir)
        db.executeCommand("CREATE SCHEMA PLANT")
        db.executeCommand("CREATE TABLE PLANT.MACHINE (ID INT, NAME VARCHAR(20))")
        db.executeCommand("INSERT INTO PLANT.MACHINE VALUES (1, 'lathe')")
        db.executeCommand("CREATE TABLE APP.TOOL (ID INT)")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aTableInTheNamedSchemaIsFound() {
        assertEquals("APP", db.defaultSchemaName)
        assertNotNull(db.selectAll("MACHINE", "PLANT"), "selectAll finds the table in PLANT")
        db.selectAllIntoOpenResultSet("MACHINE", "PLANT").use { assertNotNull(it, "the open result set too") }
        assertFalse(db.isTableEmpty("MACHINE", "PLANT"), "the table has a row")
        val text = StringWriter()
        PrintWriter(text).use { db.exportTableAsCSV("MACHINE", "PLANT", it, true) }
        assertEquals(listOf("ID,NAME", "1,lathe"), text.toString().trim().lines().map { it.trim().replace("\"", "") })
    }

    @Test
    fun theDefaultSchemaIsUnchangedWhenNoneIsGiven() {
        assertNotNull(db.selectAll("TOOL"), "a table in the default schema is found as before")
        assertNull(db.selectAll("MACHINE"), "a table only in another schema is not in the default schema")
    }
}
