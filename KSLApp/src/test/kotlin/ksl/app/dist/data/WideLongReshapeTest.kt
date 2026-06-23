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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WideLongReshapeTest {

    private val headers = listOf("id", "value", "note")

    private fun row(vararg cells: String): Array<String> = arrayOf(*cells)

    @Test
    fun `groups values by id preserving first-encountered order`() {
        val rows = listOf(
            row("red", "1.5", "x"),
            row("blue", "9.0", "x"),
            row("red", "2.5", "x"),
            row("green", "7.0", "x"),
            row("blue", "8.0", "x"),
            row("red", "3.5", "x"),
        )
        val result = WideLongReshape.splitLong(rows, headers, "id", "value")
        assertEquals(listOf("red", "blue", "green"), result.map { it.name })
        assertContentEquals(doubleArrayOf(1.5, 2.5, 3.5), result[0].data)
        assertContentEquals(doubleArrayOf(9.0, 8.0), result[1].data)
        assertContentEquals(doubleArrayOf(7.0), result[2].data)
    }

    @Test
    fun `throws when id column is missing from headers`() {
        val rows = listOf(row("red", "1.0", "x"))
        val ex = assertThrows<ImportException> {
            WideLongReshape.splitLong(rows, headers, idColumn = "missing", valueColumn = "value")
        }
        assertEquals(true, ex.message!!.contains("missing"))
    }

    @Test
    fun `throws when value column is missing from headers`() {
        val rows = listOf(row("red", "1.0", "x"))
        assertThrows<ImportException> {
            WideLongReshape.splitLong(rows, headers, idColumn = "id", valueColumn = "missing")
        }
    }

    @Test
    fun `throws when a value cell is non-numeric`() {
        val rows = listOf(
            row("red", "1.0", "x"),
            row("red", "not-a-number", "x"),
        )
        val ex = assertThrows<ImportException> {
            WideLongReshape.splitLong(rows, headers, "id", "value")
        }
        assertEquals(true, ex.message!!.contains("row 2"))
    }

    @Test
    fun `throws when a row is shorter than required column indices`() {
        val rows = listOf(row("red"))
        assertThrows<ImportException> {
            WideLongReshape.splitLong(rows, headers, "id", "value")
        }
    }

    @Test
    fun `trims surrounding whitespace before parsing values`() {
        val rows = listOf(row("red", "  1.25  ", "x"))
        val result = WideLongReshape.splitLong(rows, headers, "id", "value")
        assertContentEquals(doubleArrayOf(1.25), result[0].data)
    }
}
