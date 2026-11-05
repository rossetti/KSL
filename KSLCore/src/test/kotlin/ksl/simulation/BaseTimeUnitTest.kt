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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private class TimedEvents(parent: ModelElement) : ModelElement(parent, "TimedEvents") {
    val seen = mutableListOf<Double>()

    override fun initialize() {
        schedule(this::fire, 1.0)
        schedule(this::fire, 2.5)
    }

    private fun fire(event: KSLEvent<Nothing>) {
        assertEquals(event.time, time)
        seen += time
    }
}

private class TimedProcess(parent: ModelElement, private val delayed: () -> Double) : ProcessModel(parent, "TimedProcess") {
    val seen = mutableListOf<Double>()

    private inner class Job : Entity() {
        val p: KSLProcess = process {
            seen += time
            delay(delayed())
            seen += time
        }
    }

    override fun initialize() {
        activate(Job().p, timeUntilActivation = 1.0)
    }
}

@DisplayName("The clock under a base time unit")
class BaseTimeUnitTest {

    private fun model(unit: ModelElement.TimeUnit): Model {
        val model = Model("BaseTime${System.nanoTime()}", autoCSVReports = false)
        model.baseTimeUnit = unit
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        return model
    }

    @Test
    @DisplayName("an element's time is the event's time, in hours")
    fun eventViewInHours() {
        val model = model(ModelElement.TimeUnit.HOUR)
        val element = TimedEvents(model)
        model.simulate()
        assertEquals(listOf(1.0, 2.5), element.seen)
    }

    @Test
    @DisplayName("a process can delay when the base unit is hours")
    fun processViewInHours() {
        val model = model(ModelElement.TimeUnit.HOUR)
        val element = TimedProcess(model) { 2.0 }
        model.simulate()
        assertEquals(listOf(1.0, 3.0), element.seen)
    }

    @Test
    @DisplayName("minute() converts a delay when the base unit is hours")
    fun conversionHelperInHours() {
        val model = model(ModelElement.TimeUnit.HOUR)
        val element = TimedProcess(model) { 30.0 * model.minute() }
        model.simulate()
        assertEquals(1.5, element.seen[1], 1.0e-10)
    }

    @Test
    @DisplayName("the default base unit behaves as it always did")
    fun defaultUnitIsUnchanged() {
        val model = Model("BaseTimeDefault${System.nanoTime()}", autoCSVReports = false)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        val element = TimedProcess(model) { 2.0 }
        model.simulate()
        assertEquals(listOf(1.0, 3.0), element.seen)
    }
}
