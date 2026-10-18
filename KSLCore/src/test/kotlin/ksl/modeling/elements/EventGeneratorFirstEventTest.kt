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

package ksl.modeling.elements

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  The time of a generator's first event, when the generator was built from one random variable for both the
 *  first event and the time between events, and the time between events is changed afterwards.
 *
 *  Before R1.8 the first event kept the original distribution, so a model whose demand process was changed
 *  only through the time between events still drew its first demand from the old one. Since R1.8 the first
 *  event follows the time between events until it is given a process of its own.
 */
class EventGeneratorFirstEventTest {

    private class Arrivals(parent: ModelElement, first: RVariableIfc, btw: RVariableIfc) :
        ModelElement(parent, "Arrivals") {
        var firstEventTime = Double.NaN
        val generator = EventGenerator(
            this,
            { if (firstEventTime.isNaN()) firstEventTime = time },
            first, btw, name = "Gen"
        )

        override fun initialize() {
            firstEventTime = Double.NaN
        }
    }

    private fun model(first: RVariableIfc, btw: RVariableIfc = first): Pair<Model, Arrivals> {
        val m = Model("FirstEvent", autoCSVReports = false)
        val a = Arrivals(m, first, btw)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        return m to a
    }

    @Test
    @DisplayName("Built from one random variable: changing the time between events changes the first event")
    fun firstEventFollowsTheTimeBetweenEvents() {
        val (m, a) = model(ConstantRV(100.0))
        a.generator.initialTimeBtwEvents = ConstantRV(1.0)
        m.simulate()
        assertEquals(1.0, a.firstEventTime, 0.0)
    }

    @Test
    @DisplayName("Built from two random variables: changing the time between events leaves the first event")
    fun separateProcessesStaySeparate() {
        val (m, a) = model(ConstantRV(100.0), ConstantRV(100.0))
        a.generator.initialTimeBtwEvents = ConstantRV(1.0)
        m.simulate()
        assertEquals(100.0, a.firstEventTime, 0.0)
    }

    @Test
    @DisplayName("A first-event process set explicitly ends the link")
    fun explicitFirstEventEndsTheLink() {
        val (m, a) = model(ConstantRV(100.0))
        a.generator.timeUntilFirstEvent = ConstantRV(50.0)
        a.generator.initialTimeBtwEvents = ConstantRV(1.0)
        m.simulate()
        assertEquals(50.0, a.firstEventTime, 0.0)
    }

    @Test
    @DisplayName("Setting both together restores the link")
    fun settingBothRestoresTheLink() {
        val (m, a) = model(ConstantRV(100.0))
        a.generator.timeUntilFirstEvent = ConstantRV(50.0)
        a.generator.setInitialEventTimeProcesses(ConstantRV(7.0))
        a.generator.initialTimeBtwEvents = ConstantRV(3.0)
        m.simulate()
        assertEquals(3.0, a.firstEventTime, 0.0)
    }

    @Test
    @DisplayName("A parameter override of the time between events also moves the first event, in every experiment")
    fun parameterOverrideIsFollowedAcrossExperiments() {
        val (m, a) = model(ConstantRV(100.0))
        m.rvParameterSetter.changeParameter("Gen:TimeBtwEventsRV", "value", 1.0)
        m.simulate()
        assertEquals(1.0, a.firstEventTime, 0.0, "first experiment")
        // the setter reapplies its snapshot, which puts the first-event process back as built
        m.simulate()
        assertEquals(1.0, a.firstEventTime, 0.0, "second experiment")
    }

    @Test
    @DisplayName("A parameter override of the first event itself ends the link")
    fun parameterOverrideOfTheFirstEventEndsTheLink() {
        val (m, a) = model(ConstantRV(100.0))
        m.rvParameterSetter.changeParameter("Gen:TimeUntilFirstEventRV", "value", 50.0)
        m.rvParameterSetter.changeParameter("Gen:TimeBtwEventsRV", "value", 1.0)
        m.simulate()
        assertEquals(50.0, a.firstEventTime, 0.0)
    }
}
