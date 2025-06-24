package ksl.examples.book.chapter8

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown

/**
 *  8.4.3 Test and Repair via Conveyors
 */
fun main() {
    val m = Model()
    val tq = TestAndRepairShopWithConveyor(m, name = "TestAndRepairWithConveyor")
    m.numberOfReplications = 10
    m.lengthOfReplication = 52.0 * 5.0 * 2.0 * 480.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}
