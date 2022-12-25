package ksl.examples.book.chapter7

import ksl.simulation.Model

fun main() {
    val m = Model()
    val tq = TandemQueueWithBlocking(m, name = "TandemQModelWithBlocking")

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}
