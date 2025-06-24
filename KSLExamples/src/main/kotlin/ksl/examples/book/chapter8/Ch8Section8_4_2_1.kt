package ksl.examples.book.chapter8

import ksl.simulation.Model
import ksl.utilities.io.MarkDown

/**
 *  Section 8.4.2.1 Tandem Queue System with Conveyors and Deterministic Delays
 */
fun main() {
    val m = Model()
    val tq = TandemQueueWithConveyorsViaDelay(m, name = "TandemQueueWithConveyor")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    val out = m.outputDirectory.createPrintWriter("Conveyor via Delay")
    r.writeHalfWidthSummaryReportAsMarkDown(out, df = MarkDown.D3FORMAT)
}
