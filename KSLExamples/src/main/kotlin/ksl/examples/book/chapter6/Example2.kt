package ksl.examples.book.chapter6

import ksl.simulation.Model

/**
 * This example illustrates the simulation of a Poisson process
 * using the KSL Model class and a constructed ModelElement
 * (SimplePoissonProcess).
 */
fun main() {
    example2V1()

//    example2V2()
}

fun example2V1(){
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

fun example2V2(){
    val s = Model("Simple PP V2")
    SimplePoissonProcessV2(s.model)
    s.lengthOfReplication = 20.0
    s.numberOfReplications = 50
    s.simulate()
    println()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
    println("Done!")
}