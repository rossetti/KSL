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

import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.*
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A data frame read from a result set declares each column with the type its values have. Before R1.8 a
 *  DECIMAL column was declared Double but held BigDecimal (so `column[0] as Double` threw), REAL held Float,
 *  SMALLINT held Integer, the date and time columns held java.sql values under kotlinx.datetime types, and a
 *  column of any other SQL type came back empty.
 */
class DataFrameColumnTypesTest {

    private fun readTable(): AnyFrame {
        val dir = Files.createTempDirectory("ksl_frame_types")
        try {
            val db = DerbyDb.createDatabase("frames", dir)
            assertTrue(db.executeCommand(
                "CREATE TABLE T (CDEC DECIMAL(10,4), CDBL DOUBLE, CRL REAL, CINT INTEGER, CBIG BIGINT, " +
                        "CSML SMALLINT, CTS TIMESTAMP, CDT DATE, CTM TIME, CSTR VARCHAR(10), CBIN VARCHAR(4) FOR BIT DATA, " +
                        "CBOOL BOOLEAN, CCLB CLOB)"
            ), "create the table")
            assertTrue(db.executeCommand(
                "INSERT INTO T VALUES (0.6241, 1.5, 2.5, 3, 4, 5, TIMESTAMP('2026-10-14 12:00:00'), " +
                        "DATE('2026-10-14'), TIME('12:00:00'), 'a', X'CAFE', TRUE, 'text')"
            ), "insert the values")
            assertTrue(db.executeCommand(
                "INSERT INTO T VALUES (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
            ), "insert the nulls")
            val rs = assertNotNull(db.fetchOpenResultSet("SELECT * FROM T ORDER BY CINT"))
            return rs.use { DatabaseIfc.toDataFrame(it) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private val expected: Map<String, KClass<*>> = mapOf(
        "CDEC" to BigDecimal::class, "CDBL" to Double::class, "CRL" to Double::class, "CINT" to Int::class,
        "CBIG" to Long::class, "CSML" to Short::class, "CTS" to java.sql.Timestamp::class,
        "CDT" to java.sql.Date::class, "CTM" to java.sql.Time::class, "CSTR" to String::class,
        "CBIN" to ByteArray::class, "CBOOL" to Boolean::class
    )

    @Test
    fun everyColumnDeclaresTheTypeOfItsValues() {
        val frame = readTable()
        assertEquals(2, frame.rowsCount())
        for ((name, type) in expected) {
            val column = frame.getColumn(name)
            assertEquals(type, column.type().classifier, "$name is declared ${column.type()}")
            assertTrue(column.type().isMarkedNullable, "$name is nullable")
            val values = column.toList()
            assertEquals(2, values.size, "$name keeps every row")
            assertTrue(type.isInstance(values.first { it != null }), "$name holds a ${type.simpleName}")
            assertNull(values.last(), "$name holds the SQL NULL as null")
        }
        val dec = frame.getColumn("CDEC")[0] as BigDecimal
        assertEquals(BigDecimal("0.6241"), dec, "DECIMAL keeps the exact value")
    }

    @Test
    fun aColumnOfAnotherTypeKeepsItsValues() {
        val clob = readTable().getColumn("CCLB")
        assertEquals(2, clob.toList().size, "the column is not dropped")
        assertNotNull(clob[0], "its value is kept")
    }
}
