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
package ksl.examples.general.variables

import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * @author rossetti
 */
class TestTimeWeighted(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {
    var myX: TWResponse = TWResponse(this)

    override fun initialize() {
        schedule(this::handleEvent, 20.0)
    }

    protected fun handleEvent(e: KSLEvent<Nothing>) {
        println("$time >")
        myX.value = 2.0
        System.out.println(myX.withinReplicationStatistic)
    }

    override fun replicationEnded() {
        println("replicationEnded()")
        System.out.println(myX.withinReplicationStatistic)
    }

}

fun main() {
    testExperiment()

    //	testReplication();

    // testBatchReplication();
}

fun testExperiment() {
    val sim = Model("TestTimeWeighted")
    TestTimeWeighted(sim)

    // set the running parameters of the experiment
    sim.numberOfReplications = 2
    sim.lengthOfReplication = 50.0

    // tell the experiment to run
    sim.simulate()
    System.out.println(sim)
    val r = sim.simulationReporter
    r.printFullAcrossReplicationStatistics()
}

fun testBatchReplication() {
    val sim = Model("TestTimeWeighted")
    TestTimeWeighted(sim)
//    sim.turnOnStatisticalBatching()
//    val be: StatisticalBatchingElement = sim.getStatisticalBatchingElement().get()
//
//    // set the running parameters of the replication
//    sim.setLengthOfReplication(50.0)
//
//    // tell the experiment to run
//    sim.run()
//    System.out.println(sim)
//    System.out.println(be)
//    sim.getOutputDirectory().makePrintWriter("BatchStatistics.csv")
//    val w: PrintWriter = sim.getOutputDirectory().makePrintWriter("BatchStatistics.csv")
//    val statisticReporter: StatisticReporter = be.getStatisticReporter()
//    val csvStatistics: StringBuilder = statisticReporter.getCSVStatistics(true)
//    w.print(csvStatistics)
//    println("Done!")
}

fun testReplication() {
    val sim = Model("TestTimeWeighted")
    TestTimeWeighted(sim)

    // set the running parameters of the replication
    sim.lengthOfReplication = 50.0

    // tell the experiment to run
    sim.simulate()
    val r = sim.simulationReporter
    r.printFullAcrossReplicationStatistics()
    println("Done!")
}
