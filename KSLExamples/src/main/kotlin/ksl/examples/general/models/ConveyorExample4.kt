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
 *  then finally goes to the exit. The part stays on the conveyor while processing.
 *
 */
class ConveyorExample4(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(12.0, 1)
    private val myArrivalGenerator: EntityGenerator<PartType> = EntityGenerator(::PartType, myTBArrivals, myTBArrivals)
    init {
     //   myArrivalGenerator.initialMaximumNumberOfEvents = 1
    }
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
        conveyor.accessQueueAt(station1).defaultReportingOption = false
        conveyor.accessQueueAt(station2).defaultReportingOption = false
        conveyor.accessQueueAt(station3).defaultReportingOption = false
    }

    private val myStation1R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station1R")
    private val myStation2R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station2R")
    private val myStation3R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station3R")

    val myS1TraversalTime = Response(this,"Arrival->Station1:TraversalTime")
    val myS2TraversalTime = Response(this,"Station1->Station2:TraversalTime")
    val myS3TraversalTime = Response(this,"Station2->Station3:TraversalTime")
    val myS4TraversalTime = Response(this,"Station3->Exit:TraversalTime")

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val myCompletedCounter = Counter(this, "CompletedCount")

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(conveyor)
        return sb.toString()
    }

    private inner class PartType : Entity() {

        val productionProcess = process {
            myNumInSystem.increment()
            val cr = requestConveyor(conveyor, arrivalArea, numCellsNeeded = 1)
            entity.timeStamp = time
            rideConveyor(station1)
            myS1TraversalTime.value = time - entity.timeStamp
            use(myStation1R, delayDuration = mySTRV)
            entity.timeStamp = time
            rideConveyor(station2)
            myS2TraversalTime.value = time - entity.timeStamp
            use(myStation2R, delayDuration = mySTRV)
            entity.timeStamp = time
            rideConveyor(station3)
            myS3TraversalTime.value = time - entity.timeStamp
            use(myStation3R, delayDuration = mySTRV)
            entity.timeStamp = time
            rideConveyor(exitArea)
            myS4TraversalTime.value = time - entity.timeStamp
            exitConveyor()

            myOverallSystemTime.value = time - createTime
            myCompletedCounter.increment()
            myNumInSystem.decrement()
        }
    }

}

fun main() {
    val m = Model()
    val test = ConveyorExample4(m)
    println(test)
    m.lengthOfReplication = 480.0
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}