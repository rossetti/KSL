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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  The first-event trap: a generator built from one random variable, after which only the time
 *  between events is changed.
 *
 *  Recorded on `develop` at `c06710b0c`: the first event still comes from the original random variable.
 *  The R1.8 change keeps the two in step while they came from the same random variable, so this baseline is
 *  expected to change, from 100 to 1, and is rewritten as a behaviour test when that lands.
 */
class EventGeneratorFirstEventTest {

    private class Arrivals(parent: ModelElement) : ModelElement(parent, "Arrivals") {
        var firstEventTime = Double.NaN
        val generator = EventGenerator(
            this,
            { if (firstEventTime.isNaN()) firstEventTime = time },
            ConstantRV(100.0)
        )

        override fun initialize() {
            firstEventTime = Double.NaN
        }
    }

    @Test
    @DisplayName("Baseline: changing only the time between events leaves the first event on the old process")
    fun firstEventKeepsTheOldProcess() {
        val m = Model("FirstEventBaseline", autoCSVReports = false)
        val arrivals = Arrivals(m)
        arrivals.generator.initialTimeBtwEvents = ConstantRV(1.0)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()
        assertEquals(100.0, arrivals.firstEventTime, 0.0, "today the first event comes from the original 100")
    }
}
