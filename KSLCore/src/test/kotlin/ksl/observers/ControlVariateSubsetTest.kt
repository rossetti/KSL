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
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ksl.observers

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The response is the sum of draws from two random variables, so both work as controls. */
private class TwoDraws(parent: ModelElement) : ModelElement(parent, "TwoDraws") {
    val first = RandomVariable(this, ExponentialRV(2.0, 1), "First")
    val second = RandomVariable(this, UniformRV(0.0, 1.0, 2), "Second")
    val total = Response(this, "Total")

    override fun initialize() {
        schedule(this::draw, 1.0)
    }

    private fun draw(event: KSLEvent<Nothing>) {
        total.value = first.value + second.value
        schedule(this::draw, 1.0)
    }
}

@DisplayName("Control variate data for a subset of the controls")
class ControlVariateSubsetTest {

    private fun collector(): ControlVariateDataCollector {
        val model = Model("ControlSubset${System.nanoTime()}", autoCSVReports = false)
        val element = TwoDraws(model)
        val cv = ControlVariateDataCollector(model)
        cv.addResponse(element.total, "Total")
        cv.addControlVariate(element.first, 2.0, "First")
        cv.addControlVariate(element.second, 0.5, "Second")
        model.numberOfReplications = 20
        model.lengthOfReplication = 50.0
        model.simulate()
        return cv
    }

    private fun column(rows: Array<DoubleArray>, j: Int) = DoubleArray(rows.size) { rows[it][j] }

    @Test
    @DisplayName("a subset gives the same column as the full set")
    fun subsetMatchesFullSet() {
        val cv = collector()
        val full = cv.collectedData("Total")
        val subset = cv.collectedData("Total", controlNames = listOf("Second"))
        assertEquals(listOf("Second"), subset.predictorNames)
        assertEquals(1, subset.data[0].size)
        val secondColumn = full.predictorNames.indexOf("Second")
        assertContentEquals(column(full.data, secondColumn), column(subset.data, 0))
    }

    @Test
    @DisplayName("a subset can be batched")
    fun subsetCanBeBatched() {
        val cv = collector()
        val subset = cv.collectedData("Total", numBatches = 5, controlNames = listOf("First"))
        assertEquals(5, subset.data.size)
        assertEquals(1, subset.data[0].size)
    }

    @Test
    @DisplayName("a subset can be used in a regression")
    fun subsetRegresses() {
        val cv = collector()
        val results = cv.regressionResults(cv.collectedData("Total", controlNames = listOf("First")))
        assertTrue(results.parameters.all { it.isFinite() })
    }

    @Test
    @DisplayName("the controls may be listed in any order, and the columns follow that order")
    fun columnsFollowTheListedOrder() {
        val cv = collector()
        val data = cv.collectedData("Total", controlNames = listOf("Second", "First"))
        assertEquals(listOf("Second", "First"), data.predictorNames)
        assertContentEquals(cv.controlReplicationData("Second"), column(data.data, 0))
        assertContentEquals(cv.controlReplicationData("First"), column(data.data, 1))
    }

    @Test
    @DisplayName("a name that isn't a control is an error")
    fun unknownNameThrows() {
        val cv = collector()
        val thrown = assertFailsWith<IllegalArgumentException> {
            cv.collectedData("Total", controlNames = listOf("First", "Third"))
        }
        assertTrue(thrown.message!!.contains("Third"))
    }
}
