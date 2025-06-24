package ksl.examples.general.models.conveyors

import ksl.examples.book.chapter8.TandemQueueWithConveyors
import ksl.examples.book.chapter8.TandemQueueWithConveyorsViaDelay
import ksl.examples.book.chapter8.TandemQueueWithWorkOnConveyors
import ksl.modeling.entity.Conveyor
import ksl.simulation.Model
import ksl.utilities.io.MarkDown

/**
 *
 */
fun main() {
//    conveyorViaDelays()
    tandemQViaConveyors(Conveyor.Type.NON_ACCUMULATING)
//    tandemQViaConveyors(Conveyor.Type.ACCUMULATING)
//    tandemQWithWorkOnConveyor(Conveyor.Type.NON_ACCUMULATING)
//    tandemQWithWorkOnConveyor(Conveyor.Type.ACCUMULATING)

}

fun conveyorViaDelays() {
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

fun tandemQViaConveyors(conveyorType: Conveyor.Type) {
    val m = Model()
    val tq = TandemQueueWithConveyors(m, conveyorType, name = "TandemQueueWithConveyor")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    val out = m.outputDirectory.createPrintWriter("WorkNotOnConveyor_$conveyorType")
    r.writeHalfWidthSummaryReportAsMarkDown(out, df = MarkDown.D3FORMAT)
}

fun tandemQWithWorkOnConveyor(conveyorType: Conveyor.Type){
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