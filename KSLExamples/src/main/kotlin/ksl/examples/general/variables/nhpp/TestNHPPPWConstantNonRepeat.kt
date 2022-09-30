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
package ksl.examples.general.variables.nhpp

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.nhpp.NHPPEventGenerator
import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.modeling.nhpp.PiecewiseRateFunction
import ksl.modeling.variable.Counter
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * @author rossetti
 */
class TestNHPPPWConstantNonRepeat(
    parent: ModelElement,
    f: PiecewiseRateFunction,
    lastRate: Double,
    name: String? = null
) :
    ModelElement(parent, name) {
    private val myListener: EventListener = EventListener()
    private val myNHPPGenerator: NHPPEventGenerator = NHPPEventGenerator(this, f, myListener, lastRate, streamNum = 1)
    private val myCountersFC: MutableList<Counter> = mutableListOf()
    private val myCountersSC: MutableList<Counter> = mutableListOf()
    private val myPWRF: PiecewiseRateFunction = f

    init {
        val n: Int = f.numberSegments()
        for (i in 0 until n) {
            val c = Counter(this, "Interval FC $i")
            myCountersFC.add(c)
        }
        for (i in 0..0) {
            val c = Counter(this, "Interval SC $i")
            myCountersSC.add(c)
        }
    }

    private inner class EventListener : GeneratorActionIfc {
        override fun generate(generator: EventGenerator) {
            val t: Double = time
            if (t <= 50.0) {
                val i: Int = myPWRF.findTimeInterval(t)
                myCountersFC[i].increment()
            } else {
                myCountersSC[0].increment()
            }
        }
    }
}

fun main() {

    // create the experiment to run the model
    val s = Model("TestNHPPWConstantNonRepeat")
    val d = doubleArrayOf(15.0, 20.0, 15.0)
    val ar = doubleArrayOf(1.0, 2.0, 1.0)
    val f = PiecewiseConstantRateFunction(d, ar)
    println("-----")
    println("intervals")
    println(f)
    TestNHPPPWConstantNonRepeat(s, f, 1.0)

    // set the parameters of the experiment
    s.numberOfReplications = 10
    s.lengthOfReplication = 100.0

    // tell the simulation to run
    s.simulate()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
}