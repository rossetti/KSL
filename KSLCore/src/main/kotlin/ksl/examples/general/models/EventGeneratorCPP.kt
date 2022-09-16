/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.examples.general.models

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
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
    private val myTBA: RandomVariable = RandomVariable(this, ExponentialRV(mtba))
//    private val myArrivalGenerator: EventGenerator = EventGenerator(this, Arrivals(), myTBA, myTBA)
    private val myArrivalGenerator: EventGenerator = EventGenerator(this, this::arrivals, myTBA, myTBA)
    private var myNumArrivals: RandomVariable

    init {
        val values = doubleArrayOf(1.0, 2.0, 3.0)
        val cdf = doubleArrayOf(0.2, 0.5, 1.0)
        myNumArrivals = RandomVariable(this, DEmpiricalRV(values, cdf))
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
}