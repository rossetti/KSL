package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV

class TandemQueueWithMovement(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {
    // velocity is in feet/min
    private val myWalkingSpeedRV = RandomVariable(this, TriangularRV(88.0, 176.0, 264.0))
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

    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(2.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomSourceCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomSourceCIfc
        get() = st2
    private val myArrivalGenerator = EntityGenerator(::Customer, processName = "TandemQ Process", tba, tba)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private inner class Customer : Entity() {
        val tandemQProcess: KSLProcess = process("TandemQ Process") {
            currentLocation = enter
            wip.increment()
            timeStamp = time
            moveTo(station1, velocity = myWalkingSpeedRV)
            seize(worker1)
            delay(st1)
            release(worker1)
            moveTo(station2, velocity = myWalkingSpeedRV)
            seize(worker2)
            delay(st2)
            release(worker2)
            moveTo(exit, velocity = myWalkingSpeedRV)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }

        init {
            initialProcess = tandemQProcess
        }
    }
}

fun main() {
    val m = Model()
    val tq = TandemQueueWithMovement(m, name = "TandemQModel")

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}
