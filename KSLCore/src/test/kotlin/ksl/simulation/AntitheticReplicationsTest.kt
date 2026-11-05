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

package ksl.simulation

import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.observers.ReplicationDataCollector
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A model whose response is one U(0,1) value per replication, so the response is the stream's position. */
private class OneDraw(parent: ModelElement) : ModelElement(parent, "OneDraw") {
    private val u = RandomVariable(this, UniformRV(0.0, 1.0, streamNum = 1))
    val response = Response(this, "U")

    override fun initialize() {
        schedule(this::sample, 1.0)
    }

    private fun sample(event: KSLEvent<Nothing>) {
        response.value = u.value
    }
}

/** A single-server queue, to check that pairs behave in a model that draws a varying number of values. */
private class SingleServer(parent: ModelElement) : ModelElement(parent, "SingleServer") {
    private val arrival = RandomVariable(this, ExponentialRV(1.0, 1))
    private val service = RandomVariable(this, ExponentialRV(0.8, 2))
    private val queue = Queue<QObject>(this, "Line")
    private var busy = false
    val timeInSystem = Response(this, "TimeInSystem")

    override fun initialize() {
        busy = false
        schedule(this::arrive, arrival)
    }

    private fun arrive(event: KSLEvent<Nothing>) {
        queue.enqueue(QObject())
        if (!busy) startService()
        schedule(this::arrive, arrival)
    }

    private fun startService() {
        busy = true
        schedule(this::endService, service, queue.removeNext())
    }

    private fun endService(event: KSLEvent<QObject>) {
        timeInSystem.value = time - event.message!!.createTime
        busy = false
        if (queue.isNotEmpty) startService()
    }
}

@DisplayName("Antithetic replications")
class AntitheticReplicationsTest {

    private fun oneDrawRun(numReps: Int = 8): Pair<Model, () -> DoubleArray> {
        val model = Model("Antithetic${System.nanoTime()}", autoCSVReports = false)
        val element = OneDraw(model)
        val collector = ReplicationDataCollector(model)
        collector.addResponse(element.response)
        model.numberOfReplications(numReps, true)
        model.lengthOfReplication = 10.0
        return model to { collector.replicationData("U") }
    }

    private fun assertProperPairs(values: DoubleArray) {
        for (i in values.indices step 2) {
            assertEquals(1.0, values[i] + values[i + 1], 1.0e-12, "replications ${i + 1} and ${i + 2} are not antithetic")
        }
    }

    @Test
    @DisplayName("each pair is antithetic and each pair has its own sub-stream")
    fun pairsUseNewSubStreams() {
        val (model, data) = oneDrawRun()
        model.simulate()
        val values = data()
        assertProperPairs(values)
        val firstOfEachPair = values.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(firstOfEachPair.size, firstOfEachPair.toSet().size, "a sub-stream was used by more than one pair")
    }

    @Test
    @DisplayName("the first of each pair matches the independent run's replications, by sub-stream")
    fun firstOfPairsFollowTheSubStreams() {
        val independentModel = Model("Independent${System.nanoTime()}", autoCSVReports = false)
        val element = OneDraw(independentModel)
        val independentCollector = ReplicationDataCollector(independentModel)
        independentCollector.addResponse(element.response)
        independentModel.numberOfReplications = 4
        independentModel.lengthOfReplication = 10.0
        independentModel.simulate()
        val independent = independentCollector.replicationData("U")

        val (model, data) = oneDrawRun()
        model.simulate()
        val firstOfEachPair = data().filterIndexed { i, _ -> i % 2 == 0 }
        for (i in independent.indices) {
            assertEquals(independent[i], firstOfEachPair[i], 1.0e-12, "pair ${i + 1} is not on sub-stream ${i + 1}")
        }
    }

    @Test
    @DisplayName("a second run of the same model starts with a proper pair")
    fun secondRunStartsWithAProperPair() {
        val (model, data) = oneDrawRun()
        model.simulate()
        val first = data()
        model.simulate()
        val second = data()
        assertProperPairs(second)
        assertTrue(second.toSet().intersect(first.toSet()).isEmpty(), "the second run repeated values of the first")
    }

    @Test
    @DisplayName("a run without antithetic replications after one with them is not antithetic")
    fun laterRunIsNotAntithetic() {
        val (model, data) = oneDrawRun()
        model.simulate()
        model.changeRunParameters(model.extractRunParameters().copy(antitheticOption = false))
        for (stream in model.myRNStreamProvider.streams) {
            assertFalse(stream.antithetic, "a stream stayed antithetic after the antithetic run ended")
        }
        model.simulate()
        val values = data()
        assertEquals(values.size, values.toSet().size, "the later run repeated a value")
    }

    @Test
    @DisplayName("in a queueing model, pairs differ and replications don't cycle")
    fun queueingModelDoesNotCycle() {
        val model = Model("AntitheticQueue${System.nanoTime()}", autoCSVReports = false)
        val element = SingleServer(model)
        val collector = ReplicationDataCollector(model)
        collector.addResponse(element.timeInSystem)
        model.numberOfReplications(30, true)
        model.lengthOfReplication = 600.0
        model.simulate()
        val values = collector.replicationData("TimeInSystem")
        assertEquals(values.size, values.toSet().size, "replications repeated a result")
    }

    @Test
    @DisplayName("numberOfReplications with false turns antithetic replications off")
    fun falseTurnsAntitheticOff() {
        val (model, _) = oneDrawRun()
        assertTrue(model.antitheticOption)
        model.numberOfReplications(5, false)
        assertFalse(model.antitheticOption)
        assertEquals(5, model.numberOfReplications)
    }

    @Test
    @DisplayName("the property setter keeps the antithetic option and applies its even-number rule")
    fun setterKeepsTheOption() {
        val (model, _) = oneDrawRun()
        model.numberOfReplications = 6
        assertTrue(model.antitheticOption)
        assertEquals(6, model.numberOfReplications)
        assertFailsWith<IllegalArgumentException> { model.numberOfReplications = 3 }
        assertEquals(6, model.numberOfReplications)
    }

    @Test
    @DisplayName("run parameters can change both the option and the number together")
    fun runParametersChangeBoth() {
        val (model, _) = oneDrawRun()
        model.changeRunParameters(model.extractRunParameters().copy(antitheticOption = false, numberOfReplications = 3))
        assertFalse(model.antitheticOption)
        assertEquals(3, model.numberOfReplications)
        model.changeRunParameters(model.extractRunParameters().copy(antitheticOption = true, numberOfReplications = 4))
        assertTrue(model.antitheticOption)
        assertEquals(4, model.numberOfReplications)
    }

    @Test
    @DisplayName("with the reset option on, a second antithetic run repeats the first")
    fun resetOptionRepeatsTheRun() {
        val (model, data) = oneDrawRun()
        model.resetStartStreamOption = true
        model.simulate()
        val first = data()
        model.simulate()
        assertContentEquals(first, data())
    }
}
