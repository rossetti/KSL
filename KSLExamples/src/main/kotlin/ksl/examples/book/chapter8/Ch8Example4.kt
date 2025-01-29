package ksl.examples.book.chapter8

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.io.dbutil.KSLDatabaseObserver

fun main() {
    val m = Model()
    val tq = TestAndRepairShopResourceConstrained(m, name = "ResourceConstrainedTestAndRepair")
    m.numberOfReplications = 10
    m.lengthOfReplication = 52.0* 5.0*2.0*480.0
    val kslDatabaseObserver = KSLDatabaseObserver(m)
//    m.numberOfReplications = 1
//    m.lengthOfReplication = 2.0*480.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}