package ksl.examples.book.chapter8

import ksl.simulation.Model

fun main() {
    runVersion1()
//    runVersion2()
}

fun runVersion1(){
    val m = Model()
    val tq = TandemQueueWithUnconstrainedMovement(m, name = "TandemQModel")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

fun runVersion2(){
    val m = Model()
    val tq = TandemQueueWithUnconstrainedMovementV2(m, name = "TandemQModelV2")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}