package ksl.examples.book.chapter6

import ksl.simulation.Model


/**
 * This example illustrates the simulation of the up/down component model
 * and the turning on of event tracing and the log report.
 */
fun main() {
    // create the simulation model
    val m = Model("UpDownComponent")
    // create the model element and attach it to the model
    val tv = UpDownComponent(m)
    // set the running parameters of the simulation
    m.numberOfReplications = 5
    m.lengthOfReplication = 5000.0
    // tell the simulation model to run
    m.simulate()

    m.simulationReporter.printAcrossReplicationSummaryStatistics()
}
