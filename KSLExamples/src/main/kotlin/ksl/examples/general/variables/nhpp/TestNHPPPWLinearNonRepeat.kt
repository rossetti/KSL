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
package ksl.examples.general.variables.nhpp

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorIfc
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
    private val myNHPPGenerator: NHPPEventGenerator = NHPPEventGenerator(this, myListener, f, streamNum = 1,  lastRate)
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
        override fun generate(generator: EventGeneratorIfc) {
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

fun main() {
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