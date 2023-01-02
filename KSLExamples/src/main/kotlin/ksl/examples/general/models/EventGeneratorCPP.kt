/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.examples.general.models

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Arrivals are governed by a compound Poisson process. An EventGenerator is used
 *
 * @author rossetti
 */
class EventGeneratorCPP(parent: ModelElement, mtba: Double = 1.0, name: String? = null) : ModelElement(parent, name) {
    private val myEventCounter: Counter = Counter(this, "Counts Events")
    private val myArrivalCounter: Counter = Counter(this, "Counts Arrivals")
    private val myTBA: RandomIfc = ExponentialRV(mtba)
//    private val myArrivalGenerator: EventGenerator = EventGenerator(this, Arrivals(), myTBA, myTBA)
    private val myArrivalGenerator: EventGenerator = EventGenerator(this, this::arrivals, myTBA, myTBA)
    private var myNumArrivals: RandomVariable

    init {
        val values = doubleArrayOf(1.0, 2.0, 3.0)
        val cdf = doubleArrayOf(0.2, 0.5, 1.0)
        myNumArrivals = RandomVariable(this, DEmpiricalRV(values, cdf))
//        myEventCounter.initialCounterLimit = 10.0
//        myEventCounter.addCountLimitStoppingAction()
    }

    private fun arrivals(generator: EventGenerator) {
        myEventCounter.increment()
        val n = myNumArrivals.value
        myArrivalCounter.increment(n)
    }

    private inner class Arrivals : GeneratorActionIfc {
        override fun generate(generator: EventGenerator) {
            myEventCounter.increment()
            val n = myNumArrivals.value
            myArrivalCounter.increment(n)
        }
    }
}

fun main() {
    val m = Model("CPP Example")
    val pp = EventGeneratorCPP(m)
    m.lengthOfReplication = 20.0
    m.numberOfReplications = 50
    m.simulate()
    m.print()
}