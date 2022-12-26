package ksl.examples.book.chapter7

import ksl.simulation.Model

fun main() {
    val m = Model()
    val tq = TestAndRepairShop(m, name = "TestAndRepair")
    m.numberOfReplications = 10
    m.lengthOfReplication = 52.0* 5.0*2.0*480.0
    m.simulate()
    m.print()
}