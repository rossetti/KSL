package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.*
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

class TandemQueueWithConveyors(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val dm = DistancesModel()
    private val enter = dm.Location("Enter")
    private val station1 = dm.Location("Station1")
    private val station2 = dm.Location("Station2")
    private val exit = dm.Location("Exit")

    init {
        spatialModel = dm
    }
    // velocity is in feet per min
    private val conveyor = Conveyor.builder(this, "Conveyor")
 //       .conveyorType(Conveyor.Type.NON_ACCUMULATING)
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(30.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(enter, station1, 60)
        .nextSegment(station2, 30)
        .nextSegment(exit, 60)
        .build()

    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(1.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomSourceCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomSourceCIfc
        get() = st2
    private val myArrivalGenerator = EntityGenerator(::Customer, tba, tba)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private val myLoadingTime = RandomVariable(this, UniformRV(0.5, 0.8))
    val loadingTimeRV: RandomSourceCIfc
        get() = myLoadingTime
    private val myUnLoadingTime = RandomVariable(this, UniformRV(0.25, 0.5))
    val unloadingTimeRV: RandomSourceCIfc
        get() = myUnLoadingTime

    private inner class Customer : Entity() {
        val tandemQProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            val conveyorRequest = requestConveyor(conveyor, enter, 1)
            rideConveyor(conveyorRequest, station1)
            exitConveyor(conveyorRequest)
            use(worker1, delayDuration = st1)
            val conveyorRequest2 = requestConveyor(conveyor, station1, 1)
            rideConveyor(conveyorRequest2, station2)
            exitConveyor(conveyorRequest2)
            use(worker2, delayDuration = st2)
            val conveyorRequest3 = requestConveyor(conveyor, station2, 1)
            rideConveyor(conveyorRequest3, exit)
            exitConveyor(conveyorRequest3)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}

