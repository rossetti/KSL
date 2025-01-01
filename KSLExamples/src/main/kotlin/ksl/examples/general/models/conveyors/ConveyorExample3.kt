package ksl.examples.general.models.conveyors

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
 *  then finally goes back to the arrival area and exits. The part stays on the conveyor while processing.
 *
 *  Conveyor : Conveyor
 *  type = ACCUMULATING
 *  is circular = true
 *  velocity = 1.0
 *  cellSize = 1
 *  max number cells allowed to occupy = 1
 *  cell Travel Time = 1.0
 *  Segments:
 *  first location = ArrivalArea
 *  last location = ArrivalArea
 *  Segment: 1 = (start = ArrivalArea --> end = Station1 : length = 10)
 *  Segment: 2 = (start = Station1 --> end = Station2 : length = 10)
 *  Segment: 3 = (start = Station2 --> end = Station3 : length = 10)
 *  Segment: 4 = (start = Station3 --> end = ArrivalArea : length = 10)
 *  total length = 40
 *  Downstream locations:
 *  ArrivalArea : [Station1 -> Station2 -> Station3 -> ArrivalArea]
 *  Station1 : [Station2 -> Station3 -> ArrivalArea]
 *  Station2 : [Station3 -> ArrivalArea]
 *  Station3 : [ArrivalArea]
 */
class ConveyorExample3(
    parent: ModelElement,
    conveyorType: Conveyor.Type = Conveyor.Type.ACCUMULATING, name: String? = null
) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(2.0, 1)
    private val myArrivalGenerator: EntityGenerator<PartType> = EntityGenerator(
        ::PartType,
        myTBArrivals, myTBArrivals)
    private val mySTRV = RandomVariable(this, TriangularRV(12.0, 14.0, 16.0, 2))
    private val conveyor: Conveyor
    private val arrivalArea: IdentityIfc = Identity("ArrivalArea")
    private val station1: IdentityIfc = Identity("Station1")
    private val station2: IdentityIfc = Identity("Station2")
    private val station3: IdentityIfc = Identity("Station3")
    private val myNumInSystem: TWResponse = TWResponse(this, "NumInSystem")

    init {
        conveyor = Conveyor.builder(this, "Conveyor")
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(1)
            .firstSegment(arrivalArea, station1, 10)
            .nextSegment(station2, 10)
            .nextSegment(station3, 10)
            .nextSegment(arrivalArea, 10)
            .build()
        conveyor.accessQueueAt(station1).defaultReportingOption = false
        conveyor.accessQueueAt(station2).defaultReportingOption = false
        conveyor.accessQueueAt(station3).defaultReportingOption = false
    }

    private val myStation1R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station1R")
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

        val productionProcess = process(isDefaultProcess = true) {
            myNumInSystem.increment()
            val cr = requestConveyor(conveyor, arrivalArea, numCellsNeeded = 1)
            rideConveyor(station1)
            use(myStation1R, delayDuration = mySTRV)
            rideConveyor(station2)
            use(myStation2R, delayDuration = mySTRV)
            rideConveyor(station3)
            use(myStation3R, delayDuration = mySTRV)
            rideConveyor(arrivalArea)
            exitConveyor()
            myOverallSystemTime.value = time - createTime
            myCompletedCounter.increment()
            myNumInSystem.decrement()
        }
    }

}

fun main() {
    val m = Model()
    val test = ConveyorExample3(m, Conveyor.Type.ACCUMULATING)
    println(test)
    m.lengthOfReplication = 480.0
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}