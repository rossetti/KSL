package ksl.examples.book.chapter8

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown

fun main() {
    val m = Model()
    StemFairMixerEnhancedWithMovement(m, "Stem Fair Base Case")
    m.numberOfReplications = 400
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}





