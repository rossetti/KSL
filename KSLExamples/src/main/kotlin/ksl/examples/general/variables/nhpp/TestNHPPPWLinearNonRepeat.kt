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
import ksl.modeling.nhpp.PiecewiseLinearRateFunction
import ksl.modeling.nhpp.PiecewiseRateFunction
import ksl.modeling.variable.Counter
import ksl.simulation.Model
import ksl.simulation.ModelElement


/**
 * @author rossetti
 */
class TestNHPPPWLinearNonRepeat(parent: ModelElement, f: PiecewiseRateFunction, lastRate: Double, name: String? = null) :
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

    protected inner class EventListener : GeneratorActionIfc {
        override fun generate(generator: EventGenerator) {
            val t: Double = time
            if (t <= 2000.0) {
                //System.out.println("event at time: " + t);				
                val i: Int = myPWRF.findTimeInterval(t)
                //System.out.println("occurs in interval: " + i);				
                myCountersFC[i].increment()
            } else {
                //System.out.println("event at time: " + t);				
                myCountersSC[0].increment()
            }
        }
    }

}

fun main(args: Array<String>) {
    val ar = doubleArrayOf(0.5, 0.5, 0.9, 0.9, 1.2, 0.9, 0.5)
    val dd = doubleArrayOf(200.0, 400.0, 400.0, 200.0, 300.0, 500.0)

    val f = PiecewiseLinearRateFunction(dd, ar)
    // create the experiment to run the model
    val s = Model()
    println("-----")
    println("intervals")
    System.out.println(f)
    TestNHPPPWLinearNonRepeat(s, f, 2.0)

    // set the parameters of the experiment
    s.numberOfReplications = 1000
    s.lengthOfReplication = 4000.0

    // tell the simulation to run
    s.simulate()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
}