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
