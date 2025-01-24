package ksl.examples.book.chapter8

import ksl.simulation.Model

fun main() {
   // cmVersion1()
//    cmVersion2()
    cmVersion3()
}

fun cmVersion1(){
    val m = Model()
    val tq = TandemQueueWithConstrainedMovement(m, name = "TandemQueueWithConstrainedMovement")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

fun cmVersion2(){
    val m = Model()
    val tq = TandemQueueWithConstrainedMovementV2(m, name = "TandemQueueWithConstrainedMovementV2")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

fun cmVersion3(){
    val m = Model()
    val tq = TandemQueueWithConstrainedMovementV3(m, name = "TandemQueueWithConstrainedMovementV3")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}