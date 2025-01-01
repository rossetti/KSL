/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.models.conveyors

import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity

/**
 *  This test has Part1-3 start at A and travel to B.  Part 4 starts at B and goes to A.
 *
 * ```
 * Conveyor : Conveyor_4
 * type = ACCUMULATING
 * is circular = true
 * velocity = 1.0
 * cellSize = 1
 * max number cells allowed to occupy = 2
 * cell Travel Time = 1.0
 * Segments:
 * first location = A
 * last location = A
 * Segment: 1 = (start = A --> end = B : length = 10)
 * Segment: 2 = (start = B --> end = C : length = 20)
 * Segment: 3 = (start = C --> end = A : length = 5)
 * total length = 35
 * Downstream locations:
 * A : [B -> C -> A]
 * B : [C -> A]
 * C : [A]
 * ```
 */
class ConveyorTest2(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val stationA = Identity(name = "A")
    val stationB = Identity(name = "B")
    val stationC = Identity(name = "C")

    init {
        conveyor = Conveyor.builder(this)
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(2)
            .firstSegment(stationA, stationB, 10)
            .nextSegment(stationC, 20)
            .nextSegment(stationA, 5)
            .build()
        println(conveyor)
        println()
    }

    override fun initialize() {
        val p1 = PartType(2, "Part1")
        activate(p1.conveyingProcess)
        val p2 = PartType(1, "Part2")
        activate(p2.conveyingProcess, timeUntilActivation = 0.1)
        val p3 = PartType(1, "Part3")
        activate(p3.conveyingProcess, timeUntilActivation = 0.1)
        val p4 = PartType4(1, "Part4")
        activate(p4.conveyingProcess, timeUntilActivation = 10.0)
    }

    private inner class PartType(val amt: Int = 1, name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("PartType") {
            println("${entity.name}: time = $time before access at ${stationA.name}")
            val a = requestConveyor(conveyor, stationA, amt)
            println("${entity.name}: time = $time after access")
            println("${entity.name}: time = $time before ride to ${stationB.name}")
            rideConveyor(a, stationB)
            println("${entity.name}: time = $time after ride to ${stationB.name}")
            println("${entity.name}: The riding time was ${time - timeStamp}")
            delay(2.5)
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }
    }

    private inner class PartType4(val amt: Int = 1, name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("PartType4") {
            println("${entity.name}: time = $time before access at ${stationB.name}")
            val a = requestConveyor(conveyor, stationB, amt)
            println("${entity.name}: time = $time after access")
            println("${entity.name}: time = $time before ride to ${stationA.name}")
            rideConveyor(a, stationA)
            println("${entity.name}: time = $time after ride to ${stationA.name}")
            println("${entity.name}: The riding time was ${time - timeStamp}")
            delay(2.5)
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }
    }

}

fun main() {
    val m = Model()
    val test = TestConveyor2(m, Conveyor.Type.ACCUMULATING)
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}