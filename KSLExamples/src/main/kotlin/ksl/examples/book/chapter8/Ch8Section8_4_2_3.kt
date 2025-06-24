package ksl.examples.book.chapter8

import ksl.modeling.entity.Conveyor
import ksl.simulation.Model
import ksl.utilities.io.MarkDown

/**
 *  8.4.2.3 Tandem Queue System with Conveyors Work Performed On the Conveyor
 */
fun main() {
    val conveyorType : Conveyor.Type = Conveyor.Type.NON_ACCUMULATING
    val m = Model()
    val tq = TandemQueueWithWorkOnConveyors(m, conveyorType, name = "TandemQueueWithConveyor")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    val out = m.outputDirectory.createPrintWriter("WorkOnConveyor_$conveyorType")
    r.writeHalfWidthSummaryReportAsMarkDown(out, df = MarkDown.D3FORMAT)
}

