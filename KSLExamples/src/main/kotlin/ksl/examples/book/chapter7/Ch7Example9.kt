package ksl.examples.book.chapter7

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    test1()
//    test2()
}

fun test1() {
    val m = Model()
    val reorderPoint = 1
    val reorderQty = 2
    val rqModel = RQInventorySystem(m, reorderPoint, reorderQty, "RQ Inventory Model")
    rqModel.initialOnHand = 0
    rqModel.timeBetweenDemand.initialRandomSource = ExponentialRV(1.0 / 3.6)
    rqModel.leadTime.initialRandomSource = ConstantRV(0.5)

    m.lengthOfReplication = 110000.0
    m.lengthOfReplicationWarmUp = 10000.0
    m.numberOfReplications = 40
    m.simulate()
    m.print()
    val r = m.simulationReporter
    val out = m.outputDirectory.createPrintWriter("R-Q Inventory Results.md")
    r.writeHalfWidthSummaryReportAsMarkDown(out, df = MarkDown.D3FORMAT)
}

fun test2() {
    val m = Model()
    val reorderPoint = 1
    val reorderQty = 1
    val rqModel = RQInventorySystem(m, reorderPoint, reorderQty, "RQ Inventory Model")
    rqModel.initialOnHand = 2
    rqModel.timeBetweenDemand.initialRandomSource = ExponentialRV(1.0 / 3.6)
    rqModel.leadTime.initialRandomSource = ConstantRV(0.5)

    m.lengthOfReplication = 110000.0
    m.lengthOfReplicationWarmUp = 10000.0
    m.numberOfReplications = 30
    m.simulate()
    m.print()
}