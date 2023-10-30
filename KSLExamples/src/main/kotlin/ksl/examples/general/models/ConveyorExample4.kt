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
 *  Results include conveyor segment traversal time.
 *
 *  Conveyor : Conveyor
 *  type = ACCUMULATING
 *  is circular = false
 *  velocity = 1.0
 *  cellSize = 1
 *  max number cells allowed to occupy = 1
 *  cell Travel Time = 1.0
 *  Segments:
 *  first location = ArrivalArea
 *  last location = ExitArea
 *  Segment: 1 = (start = ArrivalArea --> end = Station1 : length = 10)
 *  Segment: 2 = (start = Station1 --> end = Station2 : length = 10)
 *  Segment: 3 = (start = Station2 --> end = Station3 : length = 10)
 *  Segment: 4 = (start = Station3 --> end = ExitArea : length = 10)
 *  total length = 40
 *  Downstream locations:
 *  ArrivalArea : [Station1 -> Station2 -> Station3 -> ExitArea]
 *  Station1 : [Station2 -> Station3 -> ExitArea]
 *  Station2 : [Station3 -> ExitArea]
 *  Station3 : [ExitArea]
 *
 */
class ConveyorExample4(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(12.0, 1)
    private val myArrivalGenerator: EntityGenerator<PartType> = EntityGenerator(::PartType, myTBArrivals, myTBArrivals)
    init {
//        myArrivalGenerator.initialMaximumNumberOfEvents = 2
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
 //           println("$time > entity ${entity.id} arrival")
            val cr = requestConveyor(conveyor, arrivalArea, numCellsNeeded = 1)
            entity.timeStamp = time
 //           println("$time > entity ${entity.id} before ride to station 1")
            rideConveyor(station1)
            var tt = time - entity.timeStamp
 //           println("$time > entity ${entity.id}, after ride from arrival to station 1, traversal time = $tt")
            myS1TraversalTime.value = tt
            use(myStation1R, delayDuration = mySTRV)
  //          println("$time > entity ${entity.id} end use resource 1, before ride to station 2")
            entity.timeStamp = time
            rideConveyor(station2)
            tt = time - entity.timeStamp
  //          println("$time > entity ${entity.id}, after ride from station 1 to station 2, traversal time = $tt")
            myS2TraversalTime.value = tt
            use(myStation2R, delayDuration = mySTRV)
  //          println("$time > entity ${entity.id} end use resource 2, before ride to station 3")
            entity.timeStamp = time
            rideConveyor(station3)
            tt = time - entity.timeStamp
  //          println("$time > entity ${entity.id},after ride from station 2 to station 3, traversal time = $tt")
            myS3TraversalTime.value = tt
            use(myStation3R, delayDuration = mySTRV)
 //           println("$time > entity ${entity.id} end use resource 3, before ride to exit")
            entity.timeStamp = time
            rideConveyor(exitArea)
            tt = time - entity.timeStamp
 //           println("$time > entity ${entity.id}, after ride from station 3 to exit, traversal time = $tt")
            myS4TraversalTime.value = tt
            exitConveyor()
//            println("$time > entity ${entity.id}, exited conveyor")
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
//    m.numberOfReplications = 1
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}