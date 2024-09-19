package ksl.examples.general.models

import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.*

/**
 *  Accumulating conveyor example. Four segments of length 10 feet. Parts
 *  arrive to arrival area and ride to the first station.  After processing at the station, go to next stations, and
 *  then finally goes to the exit. The part exits the conveyor while processing.
 *
 */
class ConveyorExample5(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(12.0, 1)
    private val myArrivalGenerator: EntityGenerator<PartType> = EntityGenerator(::PartType, myTBArrivals, myTBArrivals)
    private val mySTRV = RandomVariable(this, TriangularRV(12.0, 14.0, 16.0, 2))
    private val conveyor: Conveyor
    private val arrivalArea: IdentityIfc = Identity("ArrivalArea")
    private val station1: IdentityIfc = Identity("Station1")
    private val station2: IdentityIfc = Identity("Station2")
    private val station3: IdentityIfc = Identity("Station3")
    private val exitArea: IdentityIfc = Identity("ExitArea")
    private val myNumInSystem: TWResponse = TWResponse(this, "NumInSystem")

    init {
        conveyor = Conveyor.builder(this, "Conveyor")
            .conveyorType(Conveyor.Type.ACCUMULATING)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(1)
            .firstSegment(arrivalArea, station1, 10)
            .nextSegment(station2, 10)
            .nextSegment(station3, 10)
            .nextSegment(exitArea, 10)
            .build()
    }

    private val myStation1R: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "Station1R")
    private val myStation2R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station2R")
    private val myStation3R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station3R")

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val myCompletedCounter = Counter(this, "CompletedCount")

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(conveyor)
        return sb.toString()
    }

    private inner class PartType : Entity() {

        val productionProcess = process(addToSequence = true) {
            myNumInSystem.increment()
            val cr = requestConveyor(conveyor, arrivalArea, numCellsNeeded = 1)
            rideConveyor(station1)
            exitConveyor()
            use(myStation1R, delayDuration = mySTRV)
            requestConveyor(conveyor, station1, numCellsNeeded = 1)
            rideConveyor(station2)
            exitConveyor()
            use(myStation2R, delayDuration = mySTRV)
            requestConveyor(conveyor, station2, numCellsNeeded = 1)
            rideConveyor(station3)
            exitConveyor()
            use(myStation3R, delayDuration = mySTRV)
            requestConveyor(conveyor, station3, numCellsNeeded = 1)
            rideConveyor(exitArea)
            exitConveyor()
            myOverallSystemTime.value = time - createTime
            myCompletedCounter.increment()
            myNumInSystem.decrement()
        }
    }

}

fun main() {
    val m = Model()
    val test = ConveyorExample5(m)
    println(test)
    m.lengthOfReplication = 480.0
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}