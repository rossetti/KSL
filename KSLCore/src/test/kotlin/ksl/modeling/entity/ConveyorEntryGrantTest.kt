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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  An entry granted to one request is not granted again before that request's entity has resumed, and an entity
 *  terminated on a conveyor is not left recorded as moving.
 *
 *  One conveyor A to B, 10 cells of size 1, velocity 1, so a cell move takes 1.
 */
class ConveyorEntryGrantTest {

    private class Line(parent: ModelElement, type: Conveyor.Type) : ProcessModel(parent, "line") {
        val c: Conveyor = Conveyor.builder(this, "C").conveyorType(type).velocity(1.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("A", "B", 10).build()
        val log = mutableListOf<String>()
        val actors = mutableMapOf<String, Entity>()
        val setups = mutableListOf<Line.() -> Unit>()

        /** Rides from A to B from [arrive], logging when it has the entry and when it is off. */
        inner class Rider(val tag: String, val arrive: Double) : Entity(tag) {
            val p: KSLProcess = process("ride") {
                delay(arrive)
                val cr = requestConveyor(c, "A")
                log.add("$tag access@$time")
                rideConveyor(cr, "B")
                exitConveyor(cr)
                log.add("$tag off@$time")
            }
        }

        /** Takes the entry at A at [arrive], leaves it without riding at [leave], and then runs [then]. */
        inner class Blocker(val tag: String, val arrive: Double, val leave: Double, val then: () -> Unit) : Entity(tag) {
            val p: KSLProcess = process("block") {
                delay(arrive)
                val cr = requestConveyor(c, "A")
                log.add("$tag access@$time")
                delay(leave - time)
                exitConveyor(cr)
                log.add("$tag left@$time")
                then()
            }
        }

        /** At [at], terminates [tag] and records whether it still reports that it is moving. */
        inner class Probe(val tag: String, val at: Double) : Entity("Probe") {
            val p: KSLProcess = process("probe") {
                delay(at)
                val e = actors.getValue(tag)
                e.terminateProcess()
                log.add("$tag terminated@$time moving=${e.isMoving}")
            }
        }

        fun probe(tag: String, at: Double) = Probe(tag, at).also { activate(it.p) }

        /** Takes the entry at A at [request], rides from [ride] to B and gets off, logging when it is off. */
        inner class Late(val tag: String, val request: Double, val ride: Double) : Entity(tag) {
            val p: KSLProcess = process("late") {
                delay(request)
                val cr = requestConveyor(c, "A")
                delay(ride - time)
                rideConveyor(cr, "B")
                exitConveyor(cr)
                log.add("$tag off@$time")
            }
        }

        fun late(tag: String, request: Double, ride: Double) = Late(tag, request, ride).also { actors[tag] = it; activate(it.p) }

        fun rider(tag: String, arrive: Double) = Rider(tag, arrive).also { actors[tag] = it; activate(it.p) }

        fun blocker(tag: String, arrive: Double, leave: Double, then: () -> Unit) =
            Blocker(tag, arrive, leave, then).also { actors[tag] = it; activate(it.p) }

        override fun initialize() {
            log.clear()
            actors.clear()
            for (s in setups) s()
        }
    }

    private fun run(type: Conveyor.Type, setup: Line.() -> Unit): Pair<Line, Throwable?> {
        val model = Model("EntryGrant${System.nanoTime()}", autoCSVReports = false)
        val line = Line(model, type)
        line.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return line to error
    }

    @Test
    @DisplayName("an entry given to one request is not given again by a withdrawal before that request's entity resumes")
    fun aGrantedEntryIsNotGrantedTwice() {
        for (type in listOf(Conveyor.Type.ACCUMULATING, Conveyor.Type.NON_ACCUMULATING)) {
            val (line, error) = run(type) {
                rider("W", 0.0)
                blocker("X", 2.0, 5.0) { actors.getValue("W").terminateProcess() }
                rider("A1", 3.0)
                rider("B1", 4.0)
            }
            assertNull(error, "$type: the run stopped: $error")
            // X leaves the entry at 5 and A1 has it; W is terminated in the same step. As without the termination
            // (the control below), A1 and B1 have the entry at 5 in turn and are off at 15 and 16.
            assertEquals(
                listOf(
                    "W access@0.0", "X access@2.0", "X left@5.0", "A1 access@5.0", "B1 access@5.0",
                    "A1 off@15.0", "B1 off@16.0"
                ),
                line.log, "$type"
            )
        }
    }

    @Test
    @DisplayName("control: the same entry, left at 5, with nothing terminated")
    fun entryControl() {
        for (type in listOf(Conveyor.Type.ACCUMULATING, Conveyor.Type.NON_ACCUMULATING)) {
            val (line, error) = run(type) {
                rider("W", 0.0)
                blocker("X", 2.0, 5.0) {}
                rider("A1", 3.0)
                rider("B1", 4.0)
            }
            assertNull(error, "$type: the run stopped: $error")
            // a non-accumulating conveyor stops while X blocks the entry, from 2 to 5, so W is off 3 later there
            val wOff = if (type == Conveyor.Type.ACCUMULATING) "W off@11.0" else "W off@14.0"
            assertEquals(
                listOf(
                    "W access@0.0", "X access@2.0", "X left@5.0", "A1 access@5.0", "B1 access@5.0",
                    wOff, "A1 off@15.0", "B1 off@16.0"
                ),
                line.log, "$type"
            )
        }
    }

    @Test
    @DisplayName("an entity terminated while riding is not left recorded as moving")
    fun terminatedWhileRidingIsNotMoving() {
        val (line, error) = run(Conveyor.Type.ACCUMULATING) {
            rider("W", 0.0)
            probe("W", 4.0)
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("W access@0.0", "W terminated@4.0 moving=false"), line.log)
    }

    @Test
    @DisplayName("a cell move paused for an item that is then withdrawn does not shorten the next item's first move")
    fun pausedMoveOfAWithdrawnItemIsDiscarded() {
        // E takes the entry at 5.4, which stops the non-accumulating conveyor part way through W's cell move; W is
        // terminated at 5.5, leaving the conveyor empty; E rides from 8. It is off when it would be with no W at all.
        val (control, controlError) = run(Conveyor.Type.NON_ACCUMULATING) { late("E", 5.4, 8.0) }
        assertNull(controlError)
        val (line, error) = run(Conveyor.Type.NON_ACCUMULATING) {
            rider("W", 0.0)
            late("E", 5.4, 8.0)
            probe("W", 5.5)
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(control.log.filter { it.startsWith("E off") }, line.log.filter { it.startsWith("E off") })
    }
}
