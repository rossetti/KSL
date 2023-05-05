package ksl.examples.book.chapter7

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown

fun main(){

//    version1()
    version2()
}

fun version1(){
    val m = Model()
    val tq = TandemQueue(m, name = "TandemQModel")

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

fun version2(){
    val m = Model()
    val tq = TandemQueueV2(m, name = "TandemQModel")

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}