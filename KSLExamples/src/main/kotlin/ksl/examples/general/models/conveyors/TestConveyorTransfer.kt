package ksl.examples.general.models.conveyors

import ksl.examples.book.chapter8.TandemQueueWithConveyors
import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.io.MarkDown
import ksl.utilities.random.rvariable.ExponentialRV

class TestConveyorTransfer (
    parent: ModelElement,
    conveyorType: Conveyor.Type = Conveyor.Type.ACCUMULATING,
    name: String? = null
) : ProcessModel(parent, name) {

    private val enter = Identity("Enter")
    private val station1 = Identity("Station1")
    private val station2 = Identity("Station2")
    private val exit = Identity("Exit")

    // velocity is in feet per min
    private val conveyor1 = Conveyor.builder(this, "Conveyor1")
        .conveyorType(conveyorType)
        .velocity(30.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(enter, station1, 70)
        .build()

    // velocity is in feet per min
    private val conveyor2 = Conveyor.builder(this, "Conveyor2")
        .conveyorType(conveyorType)
        .velocity(30.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(station1, station2, 40)
        .nextSegment(exit, 60)
        .build()

    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(1.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomVariableCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomVariableCIfc
        get() = st2
    private val myArrivalGenerator = EntityGenerator(::Part, tba, tba)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private inner class Part : Entity() {
        val tandemQProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            val cr = requestConveyor(conveyor1, enter, 1)
            rideConveyor(cr, station1)
            use(worker1, delayDuration = st1)
            val tr = transferTo(cr, conveyor2, station1)
            rideConveyor(tr, station2)
            use(worker2, delayDuration = st2)
            rideConveyor(tr, exit)
            exitConveyor(tr)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}

fun main(){
    val m = Model()
    val tq = TestConveyorTransfer(m, name = "TandemQueueWithTransferConveyor")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    val out = m.outputDirectory.createPrintWriter("WorkOnConveyor_TandemQueueWithTransferConveyor")
    r.writeHalfWidthSummaryReportAsMarkDown(out, df = MarkDown.D3FORMAT)
}