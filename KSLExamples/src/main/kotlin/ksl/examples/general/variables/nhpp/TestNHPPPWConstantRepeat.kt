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
class TestNHPPPWConstantRepeat(parent: ModelElement, f: PiecewiseRateFunction, name: String? = null) :
    ModelElement(parent, name) {
    private val myListener: EventListener = EventListener()
    private val myNHPPGenerator: NHPPEventGenerator = NHPPEventGenerator(this, f, myListener, streamNumber = 1)
    private val myCountersFC: MutableList<Counter> = mutableListOf()
    private val myCountersSC: MutableList<Counter> = mutableListOf()
    private val myPWRF: PiecewiseRateFunction = f

    init {
        val n: Int = f.numberSegments()
        for (i in 0 until n) {
            val c = Counter(this, "Interval FC $i")
            myCountersFC.add(c)
        }
        for (i in 0 until n) {
            val c = Counter(this, "Interval SC $i")
            myCountersSC.add(c)
        }
    }

    protected inner class EventListener : GeneratorActionIfc {
        override fun generate(generator: EventGenerator) {
            val t: Double = time
            if (t <= 50.0) {
                //System.out.println("event at time: " + t);				
                val i: Int = myPWRF.findTimeInterval(t)
                //System.out.println("occurs in interval: " + i);				
                myCountersFC[i].increment()
            } else {
                //System.out.println("event at time: " + t);				
                val i: Int = myPWRF.findTimeInterval(t - 50.0)
                //System.out.println("occurs in interval: " + i);				
                myCountersSC[i].increment()
            }
        }
    }
}

fun main() {

    // create the experiment to run the model
    val s = Model("TestNHPPWConstantRepeat")
    val d = doubleArrayOf(15.0, 20.0, 15.0)
    val ar = doubleArrayOf(1.0, 2.0, 1.0)
    val f = PiecewiseConstantRateFunction(d, ar)
    println("-----")
    println("intervals")
    println(f)
    TestNHPPPWConstantRepeat(s, f)

    // set the parameters of the experiment
    s.numberOfReplications = 10000
    s.lengthOfReplication = 100.0

    // tell the simulation to run
    s.simulate()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
}