package ksl.examples.general.models.conveyors

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.ConveyorRequestIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.*

/**
 *  Accumulating conveyor example. Four segments of length 10 feet. Parts
 *  arrive to any of the 4 stations. After processing at the station, the part
 *  goes to the exit area.
 *  ```
 *  Conveyor : Conveyor
 * type = ACCUMULATING
 * is circular = false
 * velocity = 1.0
 * cellSize = 1
 * max number cells allowed to occupy = 1
 * cell Travel Time = 1.0
 * Segments:
 * first location = Station1
 * last location = ExitArea
 * Segment: 1 = (start = Station1 --> end = Station2 : length = 10)
 * Segment: 2 = (start = Station2 --> end = Station3 : length = 10)
 * Segment: 3 = (start = Station3 --> end = Station4 : length = 10)
 * Segment: 4 = (start = Station4 --> end = ExitArea : length = 10)
 * total length = 40
 * Downstream locations:
 * Station1 : [Station2 -> Station3 -> Station4 -> ExitArea]
 * Station2 : [Station3 -> Station4 -> ExitArea]
 * Station3 : [Station4 -> ExitArea]
 * Station4 : [ExitArea]
 * ```
 */
class ConveyorExample2(
    parent: ModelElement,
    conveyorType: Conveyor.Type = Conveyor.Type.ACCUMULATING,
    name: String? = null
) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(5.0, 1)
    private val myArrivalGenerator: EntityGenerator<PartType> = EntityGenerator(
        ::PartType,
        myTBArrivals, myTBArrivals)
    private val conveyor: Conveyor
    private val station1  = "Station1"
    private val station2 = "Station2"
    private val station3 = "Station3"
    private val station4 = "Station4"
    private val exitArea = "ExitArea"
    private val myNumInSystem: TWResponse = TWResponse(this, "NumInSystem")

    init {
        conveyor = Conveyor.builder(this, "Conveyor")
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(1)
            .firstSegment(station1, station2, 10)
            .nextSegment(station3, 10)
            .nextSegment(station4, 10)
            .nextSegment(exitArea, 10)
            .build()
    }

    private val stations = listOf(station1, station2, station3, station4)
    private val stationsRV = REmpiricalList<String>(this, stations,
        doubleArrayOf(0.2, 0.4, 0.7, 1.0), streamNum = 2)

    private val myStation1R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station1R")
    private val myStation2R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station2R")
    private val myStation3R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station3R")
    private val myStation4R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station4R")

    private inner class StationInfo(
        val resourceWithQ: ResourceWithQ,
        val delayTime: Double,
        val nextStation: String
    )

    private val stationData = mapOf<String, StationInfo>(
        station1 to StationInfo(myStation1R, 4.0, station2),
        station2 to StationInfo(myStation2R, 5.0, station3),
        station3 to StationInfo(myStation3R, 4.0, station4),
        station4 to StationInfo(myStation4R, 2.0, exitArea),
    )

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val myCompletedCounter = Counter(this, "CompletedCount")

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(conveyor)
        return sb.toString()
    }

    private inner class PartType : Entity() {
        val startingStation = stationsRV.randomElement
        val productionProcess = process(isDefaultProcess = true) {
            myNumInSystem.increment()
            val itr = stations.listIterator(stations.indexOf(startingStation))
            var cr: ConveyorRequestIfc? = null
            while (itr.hasNext()) {
                val station = itr.next()
                if (station == startingStation) {
                    cr = requestConveyor(conveyor, startingStation)
                }
                val nsd = stationData[station]!!
                use(nsd.resourceWithQ, delayDuration = nsd.delayTime)
                rideConveyor(cr!!, nsd.nextStation)
            }
            exitConveyor(cr!!)
            myOverallSystemTime.value = time - createTime
            myCompletedCounter.increment()
            myNumInSystem.decrement()
        }
    }

}

fun main() {
    val m = Model()
    val test = ConveyorExample2(m, Conveyor.Type.ACCUMULATING)
    println(test)
    m.lengthOfReplication = 960.0
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}