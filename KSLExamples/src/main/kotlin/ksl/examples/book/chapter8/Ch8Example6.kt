package ksl.examples.book.chapter8

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown

/**
 *  The test and repair shop with its transport workers on a guide path.
 *
 *  Everything about the work is the same as [Ch8Example5]: the same test plans, processing times,
 *  repair times, arrivals, stations and three transporters. Only the space changes, from
 *  point-to-point distances to a one-way aisle a worker must follow and can be held up on. Run
 *  both and compare the system time.
 */
fun main() {
    val m = Model()
    val tq = TestAndRepairShopWithGuidedTransporters(m, name = "TestAndRepairWithGuidedTransporters")
    m.numberOfReplications = 10
    m.lengthOfReplication = 52.0 * 5.0 * 2.0 * 480.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}
