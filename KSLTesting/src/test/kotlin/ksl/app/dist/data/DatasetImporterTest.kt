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
import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.Delimiter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DatasetImporterTest {

    private val importer = DatasetImporter.default

    private fun resource(name: String): Path {
        val url = checkNotNull(javaClass.classLoader.getResource("ksl/app/dist/$name")) {
            "missing test resource ksl/app/dist/$name"
        }
        return Paths.get(url.toURI())
    }

    // --- Inline ---------------------------------------------------------

    @Test
    fun `inline reference preserves map insertion order`() {
        val ref = DataSourceReference.Inline(
            linkedMapOf(
                "b" to doubleArrayOf(1.0, 2.0),
                "a" to doubleArrayOf(3.0, 4.0, 5.0),
            )
        )
        val result = importer.import(ref)
        assertEquals(listOf("b", "a"), result.map { it.name })
        assertContentEquals(doubleArrayOf(1.0, 2.0), result[0].data)
        assertContentEquals(doubleArrayOf(3.0, 4.0, 5.0), result[1].data)
    }

    @Test
    fun `inline reference rejects an empty dataset map`() {
        assertThrows<ImportException> {
            importer.import(DataSourceReference.Inline(emptyMap()))
        }
    }

    @Test
    fun `inline reference rejects any empty dataset`() {
        val ref = DataSourceReference.Inline(mapOf("empty" to DoubleArray(0)))
        assertThrows<ImportException> { importer.import(ref) }
    }

    // --- DelimitedFile WIDE ---------------------------------------------

    @Test
    fun `wide CSV imports every column when no filter is given`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("wide.csv").toString(),
            delimiter = Delimiter.COMMA,
            layout = DatasetLayout.WIDE,
        )
        val result = importer.import(ref)
        assertEquals(listOf("a", "b", "c"), result.map { it.name })
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), result[0].data)
        assertContentEquals(doubleArrayOf(10.0, 20.0, 30.0), result[1].data)
        assertContentEquals(doubleArrayOf(100.0, 200.0, 300.0), result[2].data)
    }

    @Test
    fun `wide CSV honors filter order and subset`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("wide.csv").toString(),
            layout = DatasetLayout.WIDE,
            datasetColumns = listOf("c", "a"),
        )
        val result = importer.import(ref)
        assertEquals(listOf("c", "a"), result.map { it.name })
        assertContentEquals(doubleArrayOf(100.0, 200.0, 300.0), result[0].data)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), result[1].data)
    }

    @Test
    fun `wide CSV reports an unknown filter column`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("wide.csv").toString(),
            layout = DatasetLayout.WIDE,
            datasetColumns = listOf("a", "nope"),
        )
        val ex = assertThrows<ImportException> { importer.import(ref) }
        assertEquals(true, ex.message!!.contains("nope"))
    }

    // --- DelimitedFile SINGLE -------------------------------------------

    @Test
    fun `single whitespace text imports a flat numeric stream and names by file stem`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("single.txt").toString(),
            delimiter = Delimiter.WHITESPACE,
            layout = DatasetLayout.SINGLE,
            hasHeader = false,
        )
        val result = importer.import(ref)
        assertEquals(1, result.size)
        assertEquals("single", result[0].name)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), result[0].data)
    }

    // --- DelimitedFile LONG ---------------------------------------------

    @Test
    fun `long CSV groups by id column in first-encountered order`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("long.csv").toString(),
            layout = DatasetLayout.LONG,
            idColumn = "id",
            valueColumn = "value",
        )
        val result = importer.import(ref)
        assertEquals(listOf("red", "blue", "green"), result.map { it.name })
        assertContentEquals(doubleArrayOf(1.5, 2.5, 3.5), result[0].data)
        assertContentEquals(doubleArrayOf(9.0, 8.0), result[1].data)
        assertContentEquals(doubleArrayOf(7.0), result[2].data)
    }

    @Test
    fun `long layout requires idColumn`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("long.csv").toString(),
            layout = DatasetLayout.LONG,
            valueColumn = "value",
        )
        assertThrows<ImportException> { importer.import(ref) }
    }

    @Test
    fun `long layout requires valueColumn`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("long.csv").toString(),
            layout = DatasetLayout.LONG,
            idColumn = "id",
        )
        assertThrows<ImportException> { importer.import(ref) }
    }

    // --- layout / delimiter validation ----------------------------------

    @Test
    fun `wide layout rejects whitespace delimiter`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("wide.csv").toString(),
            delimiter = Delimiter.WHITESPACE,
            layout = DatasetLayout.WIDE,
        )
        assertThrows<ImportException> { importer.import(ref) }
    }

    @Test
    fun `single layout rejects comma delimiter`() {
        val ref = DataSourceReference.DelimitedFile(
            path = resource("single.txt").toString(),
            delimiter = Delimiter.COMMA,
            layout = DatasetLayout.SINGLE,
        )
        assertThrows<ImportException> { importer.import(ref) }
    }

    @Test
    fun `missing file surfaces as ImportException`() {
        val ref = DataSourceReference.DelimitedFile(
            path = "/definitely/does/not/exist.csv",
            layout = DatasetLayout.WIDE,
        )
        val ex = assertThrows<ImportException> { importer.import(ref) }
        assertEquals(true, ex.message!!.contains("not found"))
    }

    // --- Generated ------------------------------------------------------

    @Test
    fun `generated source samples a random variable into one named dataset`() {
        val ref = DataSourceReference.Generated(
            rvType = "Exponential",
            parameters = mapOf("mean" to 2.5),
            sampleSize = 100,
            streamNumber = 1,
            name = "synthetic"
        )
        val result = importer.import(ref)
        assertEquals(1, result.size)
        assertEquals("synthetic", result[0].name)
        assertEquals(100, result[0].size)
    }

    @Test
    fun `generated source is reproducible for a fixed stream number`() {
        val ref = DataSourceReference.Generated(
            rvType = "Exponential", parameters = mapOf("mean" to 1.0), sampleSize = 50, streamNumber = 7
        )
        val a = importer.import(ref).single().data
        val b = importer.import(ref).single().data
        assertContentEquals(a, b)
    }

    @Test
    fun `generated discrete source yields integer-valued samples`() {
        val ref = DataSourceReference.Generated(
            rvType = "Poisson", parameters = mapOf("mean" to 5.0), sampleSize = 200, streamNumber = 3
        )
        val data = importer.import(ref).single().data
        assertEquals(200, data.size)
        assertEquals(true, data.all { it == Math.rint(it) }, "Poisson samples should be integer-valued")
    }

    @Test
    fun `generated source rejects an unknown rv type`() {
        val ref = DataSourceReference.Generated(rvType = "NotARealRV", sampleSize = 10)
        val ex = assertThrows<ImportException> { importer.import(ref) }
        assertEquals(true, ex.message!!.contains("unknown rv type"))
    }

    @Test
    fun `generated source rejects an unknown parameter name`() {
        val ref = DataSourceReference.Generated(
            rvType = "Exponential", parameters = mapOf("notAParam" to 1.0), sampleSize = 10
        )
        val ex = assertThrows<ImportException> { importer.import(ref) }
        assertEquals(true, ex.message!!.contains("unknown parameter"))
    }

    @Test
    fun `generated source rejects a non-positive sample size`() {
        val ref = DataSourceReference.Generated(rvType = "Exponential", sampleSize = 0)
        assertThrows<ImportException> { importer.import(ref) }
    }
}
