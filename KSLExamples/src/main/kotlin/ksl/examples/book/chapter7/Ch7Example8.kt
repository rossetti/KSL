package ksl.examples.book.chapter7

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown

/**
 * Example 7.8 — runs the [WalkInHealthClinic] process model (triage, a prioritized doctor queue with
 * balking and reneging) for 30 replications and writes the half-width summary report as Markdown.
 */
fun main(){
    val m = Model()
    WalkInHealthClinic(m, "Walk-In Clinic")
    m.lengthOfReplication = 10.0 * 60.0
    m.numberOfReplications = 30
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)

}