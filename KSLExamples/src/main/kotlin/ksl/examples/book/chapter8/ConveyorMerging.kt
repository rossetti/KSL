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

    private val area1 = "Area1"
    private val area2 = "Area2"
    private val sorting = "Sorting"
    private val processing = "Processing"

    private val conveyor1 = Conveyor.builder(this, "Conveyor1")
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(area1, sorting, 20)
        .build()

    private val conveyor2 = Conveyor.builder(this, "Conveyor2")
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(area2, sorting, 20)
        .build()

    private val conveyor3 = Conveyor.builder(this, "Conveyor3")
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(sorting, processing, 20)
        .build()

    private val tba1 = ExponentialRV(10.0, 1)
    private val generateArea1Parts = EntityGenerator(this::Area1Part, tba1, tba1)

    private val tba2 = TriangularRV(10.0, 16.0, 18.0, 2)
    private val generateArea2Parts = EntityGenerator(this::Area2Part, tba2, tba2)

    private val myArea1SystemTime = Response(this, "Area1SystemTime")
    private val myArea2SystemTime = Response(this, "Area2SystemTime")

    private inner class Area1Part : Entity() {
        val mergeProcess: KSLProcess = process(isDefaultProcess = true) {
            val cr = requestConveyor(conveyor = conveyor1, entryLocation = area1, numCellsNeeded = 1)
            rideConveyor(conveyorRequest = cr, destination = sorting)
            val tr = transferTo(conveyorRequest = cr, nextConveyor = conveyor3, entryLocation = sorting)
            rideConveyor(conveyorRequest = tr, destination = processing)
            exitConveyor(conveyorRequest = tr)
            myArea1SystemTime.value = time - entity.createTime
        }
    }

    private inner class Area2Part : Entity() {
        val mergeProcess: KSLProcess = process(isDefaultProcess = true) {
            val cr = requestConveyor(conveyor = conveyor2, entryLocation = area2, numCellsNeeded = 1)
            rideConveyor(conveyorRequest = cr, destination = sorting)
            val tr = transferTo(conveyorRequest = cr, nextConveyor = conveyor3, entryLocation = sorting)
            rideConveyor(conveyorRequest = tr, destination = processing)
            exitConveyor(conveyorRequest = tr)
            myArea2SystemTime.value = time - entity.createTime
        }
    }
}