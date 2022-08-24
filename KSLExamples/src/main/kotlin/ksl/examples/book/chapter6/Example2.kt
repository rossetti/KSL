package ksl.examples.book.chapter6

import ksl.simulation.Model

/**
 * This example illustrates the simulation of a Poisson process
 * using the KSL Model class and a constructed ModelElement
 * (SimplePoissonProcess).
 */
fun main() {
    val s = Model("Simple PP")
    SimplePoissonProcess(s.model)
    s.lengthOfReplication = 20.0
    s.numberOfReplications = 50
    s.simulate()
    println()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
    println("Done!")
}