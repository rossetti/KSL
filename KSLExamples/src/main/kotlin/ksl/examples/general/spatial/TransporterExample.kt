package ksl.examples.general.spatial

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.MovableResourcePoolWithQ
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

class SmallTransporterModel(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {
    // velocity is in feet/min
    private val truckSpeed = TriangularRV(88.0, 176.0, 264.0)
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
         dm.defaultVelocity = truckSpeed
        spatialModel = dm
    }

    private val truck1 = MovableResource(this, initLocation = enter, truckSpeed, "Truck1")
    private val truck2 = MovableResource(this, initLocation = enter, truckSpeed, "Truck2")
    private val truck3 = MovableResource(this, initLocation = enter, truckSpeed, "Truck3")
    private val truckList = listOf(truck1, truck2, truck3)
    private val forkTrucks = MovableResourcePoolWithQ(this, truckList, truckSpeed, name = "ForkTrucks")

    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(2.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomSourceCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomSourceCIfc
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

    private val myLoadingTime = RandomVariable(this, UniformRV(0.5, 0.8))
    val loadingTimeRV: RandomSourceCIfc
        get() = myLoadingTime
    private val myUnLoadingTime = RandomVariable(this, UniformRV(0.25, 0.5))
    val unloadingTimeRV: RandomSourceCIfc
        get() = myUnLoadingTime
    private inner class Part : Entity() {
        val mfgProcess: KSLProcess = process(addToSequence = true) {
            currentLocation = enter
            wip.increment()
            timeStamp = time
            transportWith(forkTrucks, toLoc = station1, loadingDelay = myLoadingTime, unLoadingDelay = myUnLoadingTime)
            use(worker1, delayDuration = st1)
            transportWith(forkTrucks, toLoc = station2, loadingDelay = myLoadingTime, unLoadingDelay = myUnLoadingTime)
            use(worker2, delayDuration = st2)
            transportWith(forkTrucks, toLoc = exit, loadingDelay = myLoadingTime, unLoadingDelay = myUnLoadingTime)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}

fun main() {
    val m = Model( "TransporterExample")
    val tq = SmallTransporterModel(m)

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}
