package ksl.examples.book.chapter8

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown

fun main() {
    val m = Model()
    val tq = TandemQueueWithConveyors(m, name = "TandemQueueWithConveyor")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}