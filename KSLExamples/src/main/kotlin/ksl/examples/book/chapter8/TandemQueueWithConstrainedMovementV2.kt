package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV

class TandemQueueWithConstrainedMovementV2(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {
    // velocity is in feet/min
    private val myWalkingSpeedRV = TriangularRV(88.0, 176.0, 264.0)
    private val dm = DistancesModel()
    private val enter = dm.Location("Enter")
    private val station1 = dm.Location("Station1")
    private val station2 = dm.Location("Station2")
    private val exit = dm.Location("Exit")

    init {
        // distance is in feet
        dm.addDistance(enter, station1, 60.0, symmetric = true)
        dm.addDistance(station1, station2, 30.0, symmetric = true)
        dm.addDistance(station2, exit, 60.0, symmetric = true)
        dm.addDistance(station2, enter, 90.0, symmetric = true)
        dm.addDistance(exit, station1, 90.0, symmetric = true)
        dm.addDistance(exit, enter, 150.0, symmetric = true)
        dm.defaultVelocity = myWalkingSpeedRV
        spatialModel = dm
    }

    private val mover1: MovableResourceWithQ = MovableResourceWithQ(this, enter, myWalkingSpeedRV, name = "Mover1")
    private val mover2: MovableResourceWithQ = MovableResourceWithQ(this, enter, myWalkingSpeedRV, name = "Mover2")
    private val mover3: MovableResourceWithQ = MovableResourceWithQ(this, enter, myWalkingSpeedRV, name = "Mover3")
    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(1.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomVariableCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomVariableCIfc
        get() = st2
    private val myArrivalGenerator = EntityGenerator(::Customer, tba, tba)
    val generator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private inner class Customer : Entity() {
        val tandemQProcess: KSLProcess = process(isDefaultProcess = true) {
            currentLocation = enter
            wip.increment()
            timeStamp = time
            transportWith(mover1, station1)
            use(worker1, delayDuration = st1)
            transportWith(mover2, station2)
            use(worker2, delayDuration = st2)
            transportWith(mover3, exit)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}

