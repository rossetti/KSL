package ksl.examples.general.models

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.ConveyorRequestIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.*

/**
 *  Accumulating conveyor example. One segment 40 feet.
 *
 */
class ConveyorExample2(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(5.0, 1)
    private val myArrivalGenerator: EntityGenerator<PartType> = EntityGenerator(::PartType, myTBArrivals, myTBArrivals)
    private val conveyor: Conveyor
    private val arrivalArea: IdentityIfc = Identity("ArrivalArea")
    private val station1: IdentityIfc = Identity("Station1")
    private val station2: IdentityIfc = Identity("Station2")
    private val station3: IdentityIfc = Identity("Station3")
    private val station4: IdentityIfc = Identity("Station4")
    private val exitArea: IdentityIfc = Identity("ExitArea")

    init {
        conveyor = Conveyor.builder(this, "Conveyor")
            .conveyorType(Conveyor.Type.ACCUMULATING)
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
    private val stationsRV = REmpiricalList<IdentityIfc>(this, stations, doubleArrayOf(0.2, 0.4, 0.7, 1.0))

    private val myStation1R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station1R")
    private val myStation2R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station2R")
    private val myStation3R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station3R")
    private val myStation4R: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Station4R")

    private inner class StationInfo(
        val resourceWithQ: ResourceWithQ,
        val delayTime: Double,
        val nextStation: IdentityIfc
    )

    private val stationData = mapOf<IdentityIfc, StationInfo>(
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
        val startingStation = stationsRV.element
        val productionProcess = process {
            val itr = stations.listIterator(stations.indexOf(startingStation))
            var cr: ConveyorRequestIfc? = null
            while(itr.hasNext()){
                val station = itr.next()
                if (station == startingStation){
                    cr = requestConveyor(conveyor, startingStation)
                }
                val nsd = stationData[station]!!
                use(nsd.resourceWithQ, delayDuration = nsd.delayTime)
                rideConveyor(cr!!, nsd.nextStation)
            }
            exitConveyor(cr!!)
            myOverallSystemTime.value = time - createTime
            myCompletedCounter.increment()
        }
    }

}

fun main() {
    val m = Model()
    val test = ConveyorExample2(m)
    println(test)
    m.lengthOfReplication = 960.0
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}