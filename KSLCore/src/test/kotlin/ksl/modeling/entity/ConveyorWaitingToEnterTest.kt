/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.entity

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals

/**
 *  An item waiting to enter a conveyor whose cells are all empty is carried, on a non-accumulating conveyor as on
 *  an accumulating one: the conveyor moves to let it on. Its entry cell may be empty because the conveyor was
 *  stopped when the item reached it, or because the only other item on it has left.
 */
class ConveyorWaitingToEnterTest {

    private class Belt(parent: ModelElement, type: Conveyor.Type, twoSegments: Boolean) : ProcessModel(parent, "Belt") {
        val c: Conveyor = if (twoSegments) {
            Conveyor.builder(this, "C").conveyorType(type).velocity(1.0).cellSize(1)
                .maxCellsAllowed(2).firstSegment("A", "M", 3).nextSegment("B", 3).build()
        } else {
            Conveyor.builder(this, "C").conveyorType(type).velocity(1.0).cellSize(1)
                .maxCellsAllowed(2).firstSegment("A", "B", 4).build()
        }
        val log = mutableListOf<String>()
        val setups = mutableListOf<Belt.() -> Unit>()

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(type: Conveyor.Type, twoSegments: Boolean, setup: Belt.() -> Unit): Belt {
        val model = Model("Belt${System.nanoTime()}", autoCSVReports = false)
        val belt = Belt(model, type, twoSegments)
        belt.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return belt
    }

    @ParameterizedTest
    @EnumSource(Conveyor.Type::class)
    fun `an item that reaches the entry of a stopped conveyor is carried after the restart`(type: Conveyor.Type) {
        val belt = run(type, twoSegments = false) {
            at(0.0) { c.stopConveyor() }
            actor("X") {
                delay(1.0)
                val r = requestConveyor(c, "A")
                rideConveyor(r, "B")
                log.add("X at B@$time")
                exitConveyor(r)
                log.add("X off@$time")
            }
            at(5.0) { c.startConveyor() }
        }
        // the restart at 5 lets it on at 6; it reaches B at 9 and is off at 10, as an unstopped belt carries it 4 later
        assertEquals(listOf("X at B@9.0", "X off@10.0"), belt.log, "$type")
    }

    @ParameterizedTest
    @EnumSource(Conveyor.Type::class)
    fun `an item that rides on from the mid point after an entry there is left is carried to the end`(type: Conveyor.Type) {
        val belt = run(type, twoSegments = true) {
            actor("E1") {
                delay(11.0)
                val r = requestConveyor(c, "A", 1)
                rideConveyor(r, "M")
                log.add("E1 at M@$time")
                delay(2.5)
                rideConveyor(r, "B")
                log.add("E1 at B@$time")
                exitConveyor(r)
                log.add("E1 off@$time")
            }
            // takes the entry at M with two cells, then leaves without riding
            actor("E2") {
                delay(11.0)
                val r = requestConveyor(c, "M", 2)
                delay(0.5)
                exitConveyor(r)
            }
        }
        assertEquals("E1 off@", belt.log.last().substringBefore("@") + "@", "$type: E1 left the conveyor: ${belt.log}")
    }
}
