package ksl.examples.book.chapter8

import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.Response
import ksl.simulation.ModelElement

class ConveyorMerging(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val areaA = "AreaA"
    private val areaB = "AreaB"
    private val sorting = "Sorting"
    private val areaC = "AreaC"

    private val conveyor1 = Conveyor.builder(this, "Conveyor1")
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(areaA, sorting, 20)
        .build()

    private val conveyor2 = Conveyor.builder(this, "Conveyor2")
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(areaB, sorting, 20)
        .build()

    private val conveyor3 = Conveyor.builder(this, "Conveyor3")
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(sorting, areaC, 20)
        .build()

    private val tba1 = ExponentialRV(10.0, 1)
    private val generateAreaAParts = EntityGenerator(this::AreaAPart, tba1, tba1)

    private val tba2 = TriangularRV(10.0, 16.0, 18.0, 2)
    private val generateAreaBParts = EntityGenerator(this::AreaBPart, tba2, tba2)

    private val myAreaASystemTime = Response(this, "AreaASystemTime")
    private val myAreaBSystemTime = Response(this, "AreaBSystemTime")

    private inner class AreaAPart : Entity() {
        val mergeProcess: KSLProcess = process(isDefaultProcess = true) {
            val cr = requestConveyor(conveyor = conveyor1, entryLocation = areaA, numCellsNeeded = 1)
            rideConveyor(conveyorRequest = cr, destination = sorting)
            val tr = transferTo(conveyorRequest = cr, nextConveyor = conveyor3, entryLocation = sorting)
            rideConveyor(conveyorRequest = tr, destination = areaC)
            exitConveyor(conveyorRequest = tr)
            myAreaASystemTime.value = time - entity.createTime
        }
    }

    private inner class AreaBPart : Entity() {
        val mergeProcess: KSLProcess = process(isDefaultProcess = true) {
            val cr = requestConveyor(conveyor = conveyor2, entryLocation = areaB, numCellsNeeded = 1)
            rideConveyor(conveyorRequest = cr, destination = sorting)
            val tr = transferTo(conveyorRequest = cr, nextConveyor = conveyor3, entryLocation = sorting)
            rideConveyor(conveyorRequest = tr, destination = areaC)
            exitConveyor(conveyorRequest = tr)
            myAreaBSystemTime.value = time - entity.createTime
        }
    }
}