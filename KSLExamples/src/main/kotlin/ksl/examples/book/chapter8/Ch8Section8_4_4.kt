package ksl.examples.book.chapter8

import ksl.simulation.Model

/**
 *  8.4.4 Miscellaneous Concepts in Conveyor Modeling
 */
fun main(){
    val m = Model()
    val tq = ConveyorMerging(m, name = "Merge Conveyor")
    m.numberOfReplications = 1
    m.lengthOfReplication = 120.0*60.0
    m.simulate()
    m.print()
}
