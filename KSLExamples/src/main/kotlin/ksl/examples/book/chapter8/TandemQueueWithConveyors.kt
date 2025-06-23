package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.*
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

class TandemQueueWithConveyors(
    parent: ModelElement,
    conveyorType: Conveyor.Type = Conveyor.Type.NON_ACCUMULATING,
    name: String? = null
) : ProcessModel(parent, name) {

    private val enter = "Enter"
    private val station1 = "Station1"
    private val station2 = "Station2"
    private val exit = "Exit"

    // velocity is in feet per min
    private val conveyor = Conveyor.builder(this, "Conveyor")
        .conveyorType(conveyorType)
        .velocity(30.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(enter, station1, 70)
        .nextSegment(station2, 40)
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
    val generator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private val myLoadingTime = RandomVariable(this, UniformRV(0.5, 0.8))
    val loadingTimeRV: RandomVariableCIfc
        get() = myLoadingTime
    private val myUnLoadingTime = RandomVariable(this, UniformRV(0.25, 0.5))
    val unloadingTimeRV: RandomVariableCIfc
        get() = myUnLoadingTime

    private inner class Part : Entity() {
        val tandemQProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            val conveyorRequest = requestConveyor(conveyor = conveyor, entryLocation = enter, numCellsNeeded = 1)
            rideConveyor(conveyorRequest = conveyorRequest, destination = station1)
            exitConveyor(conveyorRequest)
            use(resource = worker1, delayDuration = st1)
            val conveyorRequest2 = requestConveyor(conveyor = conveyor, entryLocation = station1, numCellsNeeded = 1)
            rideConveyor(conveyorRequest = conveyorRequest2, destination = station2)
            exitConveyor(conveyorRequest = conveyorRequest2)
            use(resource = worker2, delayDuration = st2)
            val conveyorRequest3 = requestConveyor(conveyor = conveyor, entryLocation = station2, numCellsNeeded = 1)
            rideConveyor(conveyorRequest = conveyorRequest3, destination = exit)
            exitConveyor(conveyorRequest = conveyorRequest3)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }

    private inner class Part2 : Entity() {
        val tandemQProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            convey(conveyor = conveyor, entryLocation = enter, destination = station1)
            use(resource = worker1, delayDuration = st1)
            convey(conveyor = conveyor, entryLocation = station1, destination = station2)
            use(resource = worker2, delayDuration = st2)
            convey(conveyor = conveyor, entryLocation = station2, destination = exit)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}

