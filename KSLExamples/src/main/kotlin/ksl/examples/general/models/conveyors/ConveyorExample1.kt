package ksl.examples.general.models.conveyors

import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.ProcessModel
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
 *  Non-accumulating conveyor example. One segment 100 feet.
 *
 */
class ConveyorExample1(
    parent: ModelElement,
    conveyorType: Conveyor.Type = Conveyor.Type.NON_ACCUMULATING,
    name: String? = null
) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(10.0, 1)

    private val myArrivalGenerator: EntityGenerator<PartType> = EntityGenerator(
        ::PartType,
        myTBArrivals, myTBArrivals)

    private val myPackingTimeRV = RandomVariable(this, TriangularRV(0.5, 2.0, 2.5, 2))

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val myCompletedCounter = Counter(this, "CompletedCount")
    private val myNumInSystem: TWResponse = TWResponse(this, "NumInSystem")

    private val conveyor: Conveyor
    private val arrivalArea = "ArrivalArea"
    private val exitArea = "ExitArea"

    init {
        conveyor = Conveyor.builder(this, "Conveyor")
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(1)
            .firstSegment(arrivalArea, exitArea, 100)
            .build()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(conveyor)
        return sb.toString()
    }

    private inner class PartType : Entity() {
        val productionProcess = process(isDefaultProcess = true) {
            myNumInSystem.increment()
            val conveyorRequest = requestConveyor(conveyor, arrivalArea, 1)
            rideConveyor(conveyorRequest, exitArea)
            delay(myPackingTimeRV)
            exitConveyor(conveyorRequest)
            myOverallSystemTime.value = time - createTime
            myCompletedCounter.increment()
            myNumInSystem.decrement()
        }
    }

}

fun main() {

    val m = Model()
    val test = ConveyorExample1(m, Conveyor.Type.NON_ACCUMULATING)
    println(test)
    m.lengthOfReplication = 480.0
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}