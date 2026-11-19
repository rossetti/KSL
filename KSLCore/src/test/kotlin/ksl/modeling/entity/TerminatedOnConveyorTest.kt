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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  An entity terminated while it uses a conveyor leaves it at once, in every state of its request: whatever it
 *  waits for, holds or covers on the conveyor is given up in the instant of the termination, the items and
 *  requests behind it go on as if it had left, and it can use the conveyor again in a new process.
 *
 *  Each case compares the follower, F, with a reference run in which the victim, V, leaves by ordinary means at
 *  the same instant: it never arrives (it was waiting), it exits from the entry without riding (it held the entry
 *  cell), or it exits at its destination (it was there). A victim terminated while riding is compared with a run
 *  without it, its times chosen so that it never affects F before it is terminated. Termination after a wake and
 *  before the resume is made by moving the conveyor's cell moves to an earlier event priority than the terminator,
 *  which runs before the resume, or by terminating from the step of the process that caused the wake.
 *
 *  Line A to B, five one-unit cells, one time unit per cell: an item that enters at time t reaches B at t + 4.
 */
class TerminatedOnConveyorTest {

    private val types = listOf(Conveyor.Type.ACCUMULATING, Conveyor.Type.NON_ACCUMULATING)

    private class Line(parent: ModelElement, type: Conveyor.Type) : ProcessModel(parent, "line") {
        val c: Conveyor = Conveyor.builder(this, "C").conveyorType(type).velocity(1.0).cellSize(1)
            .maxCellsAllowed(2).firstSegment("A", "B", 5).build()
        val log = mutableListOf<String>()
        val setups = mutableListOf<Line.() -> Unit>()
        val items = mutableMapOf<String, Item>()

        /**
         *  Arrives at [arrive], takes the entry cell at A, waits [holdAtEntry] there (its end at
         *  [entryDelayPriority]), then either leaves from the entry ([ride] false) or rides to B, waits [holdAtDest]
         *  and exits. [afterLeaving] runs in the same step as its exit from the entry.
         */
        inner class Item(
            val tag: String, val arrive: Double, val cells: Int = 1, val holdAtEntry: Double = 0.0,
            val entryDelayPriority: Int = KSLEvent.DEFAULT_PRIORITY, val ride: Boolean = true,
            val holdAtDest: Double = 0.0, val afterLeaving: (() -> Unit)? = null
        ) : Entity(tag) {
            val p: KSLProcess = process("use") {
                delay(arrive)
                val cr = requestConveyor(c, "A", cells)
                log.add("$tag access@$time")
                if (holdAtEntry > 0.0) delay(holdAtEntry, delayPriority = entryDelayPriority)
                if (!ride) {
                    exitConveyor(cr)
                    log.add("$tag left@$time")
                    afterLeaving?.invoke()
                    return@process
                }
                rideConveyor(cr, "B")
                log.add("$tag arrived@$time")
                if (holdAtDest > 0.0) delay(holdAtDest)
                exitConveyor(cr)
                log.add("$tag exited@$time")
            }

            /** A second use of the conveyor, after the first process was terminated. */
            val again: KSLProcess = process("again") {
                val cr = requestConveyor(c, "A", cells)
                log.add("$tag again access@$time")
                rideConveyor(cr, "B")
                exitConveyor(cr)
                log.add("$tag again exited@$time")
            }
        }

        fun item(
            tag: String, arrive: Double, cells: Int = 1, holdAtEntry: Double = 0.0,
            entryDelayPriority: Int = KSLEvent.DEFAULT_PRIORITY, ride: Boolean = true, holdAtDest: Double = 0.0,
            afterLeaving: (() -> Unit)? = null
        ): Item = Item(tag, arrive, cells, holdAtEntry, entryDelayPriority, ride, holdAtDest, afterLeaving)
            .also { items[tag] = it; activate(it.p) }

        fun terminate(tag: String, at: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY) {
            schedule({ _: KSLEvent<Nothing> -> items.getValue(tag).terminateProcess() }, at, priority = priority)
        }

        /** Logs, at [t], what [what] reads then. */
        fun probe(t: Double, label: String, what: () -> Any) {
            schedule({ _: KSLEvent<Nothing> -> log.add("probe $label@$t = ${what()}") }, t)
        }

        fun useAgain(tag: String, at: Double) {
            schedule({ _: KSLEvent<Nothing> -> activate(items.getValue(tag).again) }, at)
        }

        override fun initialize() {
            log.clear()
            items.clear()
            for (s in setups) s()
        }
    }

    /** What a run produced: the log, the error that stopped it if any, and the conveyor after it. */
    private class Outcome(val log: List<String>, val error: Throwable?, val line: Line) {
        fun of(tag: String): List<String> = log.filter { it.startsWith("$tag ") }
    }

    private fun run(type: Conveyor.Type, length: Double = 60.0, configure: Line.() -> Unit = {}, setup: Line.() -> Unit): Outcome {
        val model = Model("ConveyorTermination${System.nanoTime()}", autoCSVReports = false)
        val line = Line(model, type)
        line.configure()
        line.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return Outcome(line.log.toList(), error, line)
    }

    /** The terminated run completes, the follower's record is the reference's, and the victim is off the conveyor. */
    private fun assertLeftAtOnce(type: Conveyor.Type, case: String, terminated: Outcome, reference: Outcome, follower: String = "F") {
        assertNull(terminated.error, "$type, $case: the run stopped: ${terminated.error}")
        assertNull(reference.error, "$type, $case: the reference run stopped: ${reference.error}")
        assertEquals(reference.of(follower), terminated.of(follower), "$type, $case: the follower")
        val victim = terminated.line.items.getValue("V")
        assertNull(victim.conveyorRequest, "$type, $case: the victim still holds its conveyor request")
        assertTrue(terminated.line.c.conveyorRequests().none { it.entity === victim }, "$type, $case: the victim's item is still on the conveyor")
    }

    // ---------- waiting for the entry cell ----------

    @Test
    @DisplayName("terminated while waiting for the entry cell: the request leaves the access queue, and the one behind is served in its place")
    fun whileWaiting() {
        for (type in types) {
            val terminated = run(type) {
                item("H", 0.0, holdAtEntry = 5.0)
                item("V", 1.0)
                item("F", 2.0)
                terminate("V", 3.0)
            }
            val reference = run(type) {
                item("H", 0.0, holdAtEntry = 5.0)
                item("F", 2.0)
            }
            assertLeftAtOnce(type, "waiting", terminated, reference)
        }
    }

    @Test
    @DisplayName("terminated after it was woken for the entry cell by an exit from the entry, before it resumed")
    fun wokenByAnExitFromTheEntry() {
        for (type in types) {
            val terminated = run(type) {
                item("H", 0.0, holdAtEntry = 4.0, ride = false, afterLeaving = { items.getValue("V").terminateProcess() })
                item("V", 1.0)
                item("F", 2.0)
            }
            val reference = run(type) {
                item("H", 0.0, holdAtEntry = 4.0, ride = false)
                item("F", 2.0)
            }
            assertLeftAtOnce(type, "woken by an exit from the entry", terminated, reference)
        }
    }

    @Test
    @DisplayName("terminated after it was woken for the entry cell by a cell move, before it resumed")
    fun wokenByACellMove() {
        for (type in types) {
            // H rides at 5 and covers the entry cell at 6; that move wakes V, which is terminated before it resumes
            val terminated = run(type, configure = { c.defaultMovementPriority = -10 }) {
                item("H", 0.0, holdAtEntry = 5.0)
                item("V", 1.0)
                item("F", 2.0)
                terminate("V", 6.0, priority = 0)
            }
            val reference = run(type, configure = { c.defaultMovementPriority = -10 }) {
                item("H", 0.0, holdAtEntry = 5.0)
                item("F", 2.0)
            }
            assertLeftAtOnce(type, "woken by a cell move", terminated, reference)
        }
    }

    // ---------- holding the entry cell ----------

    @Test
    @DisplayName("terminated while holding the entry cell before riding: the cell is freed, as by an exit from the entry")
    fun holdingTheEntry() {
        for (type in types) {
            val terminated = run(type) {
                item("V", 0.0, holdAtEntry = 10.0)
                item("F", 1.0)
                terminate("V", 3.0)
            }
            val reference = run(type) {
                item("V", 0.0, holdAtEntry = 3.0, ride = false)
                item("F", 1.0)
            }
            assertLeftAtOnce(type, "holding the entry", terminated, reference)
        }
    }

    @Test
    @DisplayName("terminated after asking to ride, before the ride started: the entry cell is freed, and the ride starts for nobody")
    fun askedToRide() {
        for (type in types) {
            // H rides from 0 and covers the entry cell from 1; V takes the entry behind it at 1 and asks to ride at 2,
            // at an early event priority; the terminator, at 2 too, comes after that and before the ride action
            val terminated = run(type) {
                item("H", 0.0)
                item("V", 0.5, holdAtEntry = 1.0, entryDelayPriority = -20)
                item("F", 1.2)
                terminate("V", 2.0, priority = -10)
            }
            val reference = run(type) {
                item("H", 0.0)
                item("V", 0.5, holdAtEntry = 1.0, entryDelayPriority = -20, ride = false)
                item("F", 1.2)
            }
            assertLeftAtOnce(type, "asked to ride", terminated, reference)
        }
    }

    // ---------- positioned to enter, or partly on ----------

    @Test
    @DisplayName("terminated while positioned to enter the conveyor: it no longer holds the entry, and the request behind takes it")
    fun positionedToEnter() {
        for (type in types) {
            // V asks to ride at 1.5 behind H; the ride action positions it to enter at the next cell move, at 2, and
            // it is terminated before that move (accumulating: the ride action and the move are both at 2)
            val at = if (type == Conveyor.Type.ACCUMULATING) 2.0 else 1.7
            val priority = if (type == Conveyor.Type.ACCUMULATING) 5000 else KSLEvent.DEFAULT_PRIORITY
            val terminated = run(type) {
                item("H", 0.0)
                item("V", 0.5, holdAtEntry = 1.0)
                item("F", 1.2)
                terminate("V", at, priority)
            }
            val reference = run(type) {
                item("H", 0.0)
                item("V", 0.5, holdAtEntry = at - 1.0, ride = false)
                item("F", 1.2)
            }
            assertLeftAtOnce(type, "positioned to enter", terminated, reference)
        }
    }

    @Test
    @DisplayName("an item two cells long terminated while it is partly on the conveyor: the cell it covers is freed")
    fun partlyOn() {
        for (type in types) {
            // V, two cells long, enters at 1 covering one cell, and is terminated at 1.5, before it covers the second
            val terminated = run(type) {
                item("V", 0.0, cells = 2)
                item("F", 4.0)
                terminate("V", 1.5)
            }
            val reference = run(type) {
                item("F", 4.0)
            }
            assertLeftAtOnce(type, "partly on", terminated, reference)
        }
    }

    // ---------- riding ----------

    @Test
    @DisplayName("terminated while riding: its item is off the belt at once, and the exit it was going to block stays free")
    fun riding() {
        for (type in types) {
            // V enters at 1 and is at cell 3 at 3, when it is terminated; F arrives at 4 and rides to B
            val terminated = run(type) {
                item("V", 0.0)
                item("F", 4.0)
                terminate("V", 3.0)
                useAgain("V", 20.0)
            }
            val reference = run(type) {
                item("F", 4.0)
            }
            assertLeftAtOnce(type, "riding", terminated, reference)
            assertEquals(listOf("V again access@20.0"), terminated.of("V").filter { "again access" in it }, "$type: the victim uses the conveyor again")
        }
    }

    // ---------- at the destination ----------

    @Test
    @DisplayName("terminated after it was woken at its destination, before it resumed: the exit cell is freed")
    fun wokenAtTheDestination() {
        for (type in types) {
            // V enters at 1 and reaches B at 5; that move wakes it, and it is terminated before it resumes
            val terminated = run(type, configure = { c.defaultMovementPriority = -10 }) {
                item("V", 0.0)
                item("F", 1.0)
                terminate("V", 5.0, priority = 0)
            }
            val reference = run(type, configure = { c.defaultMovementPriority = -10 }) {
                item("V", 0.0)
                item("F", 1.0)
            }
            assertLeftAtOnce(type, "woken at the destination", terminated, reference)
        }
    }

    @Test
    @DisplayName("terminated at its destination before exiting: the exit cell is freed, as by an exit then")
    fun atTheDestination() {
        for (type in types) {
            val terminated = run(type) {
                item("V", 0.0, holdAtDest = 5.0)
                item("F", 1.0)
                terminate("V", 7.0)
            }
            val reference = run(type) {
                item("V", 0.0, holdAtDest = 2.0)
                item("F", 1.0)
            }
            assertLeftAtOnce(type, "at the destination", terminated, reference)
        }
    }

    @Test
    @DisplayName("terminated while its item moves off the conveyor: the item is off, and the items behind move as they would have")
    fun exiting() {
        for (type in types) {
            // V reaches B at 5 and exits at once; its item moves off at 6; it is terminated at 5.5
            val terminated = run(type) {
                item("V", 0.0)
                item("F", 1.0)
                terminate("V", 5.5)
            }
            val reference = run(type) {
                item("V", 0.0)
                item("F", 1.0)
            }
            assertLeftAtOnce(type, "exiting", terminated, reference)
        }
    }

    @Test
    @DisplayName("terminated after it was woken off the conveyor, before it resumed: it holds no request and can use the conveyor again")
    fun wokenAfterExiting() {
        for (type in types) {
            val terminated = run(type, configure = { c.defaultMovementPriority = -10 }) {
                item("V", 0.0)
                item("F", 1.0)
                terminate("V", 6.0, priority = 0)
                useAgain("V", 20.0)
            }
            val reference = run(type, configure = { c.defaultMovementPriority = -10 }) {
                item("V", 0.0)
                item("F", 1.0)
            }
            assertLeftAtOnce(type, "woken after exiting", terminated, reference)
            assertEquals(listOf("V again access@20.0"), terminated.of("V").filter { "again access" in it }, "$type: the victim uses the conveyor again")
        }
    }

    // ---------- what is counted and traced ----------

    @Test
    @DisplayName("the access queue's length falls, and the cells occupied fall, at the termination; the trace shows the item leave")
    fun countsAndTrace() {
        for (type in types) {
            val waiting = run(type) {
                item("H", 0.0, holdAtEntry = 5.0)
                item("V", 1.0)
                item("F", 2.0)
                terminate("V", 3.0)
                probe(3.5, "waiting") { c.accessQueueAt("A").size }
            }
            assertEquals(listOf("probe waiting@3.5 = 1"), waiting.log.filter { it.startsWith("probe") }, "$type: only F waits")

            val exits = mutableListOf<String>()
            val model = Model("ConveyorTrace${System.nanoTime()}", autoCSVReports = false)
            model.animationSink = object : ksl.animation.AnimationSink {
                override val isActive = true
                override fun emit(event: ksl.animation.AnimationEvent) {
                    if (event is ksl.animation.AnimationEvent.ConveyorExited) exits.add("${event.entityId}@${event.simTime}")
                }
            }
            val line = Line(model, type)
            line.setups.add {
                item("V", 0.0)
                terminate("V", 3.0)
                probe(3.5, "cells") { c.numOccupiedCells }
            }
            model.numberOfReplications = 1
            model.lengthOfReplication = 20.0
            model.simulate()
            assertEquals(listOf("probe cells@3.5 = 0"), line.log.filter { it.startsWith("probe") }, "$type: V's cell is free")
            assertEquals(listOf("${line.items.getValue("V").id}@3.0"), exits, "$type: the trace takes V's item off the conveyor")
        }
    }

    // ---------- the end of a replication ----------

    @Test
    @DisplayName("a replication that ends with entities in every state: the next replication runs, and nothing is emitted after the end")
    fun endOfAReplication() {
        for (type in types) {
            val emitted = mutableListOf<Pair<Int, Double>>()
            val model = Model("ConveyorEnd${System.nanoTime()}", autoCSVReports = false)
            model.animationSink = object : ksl.animation.AnimationSink {
                override val isActive = true
                override fun emit(event: ksl.animation.AnimationEvent) {
                    emitted.add(model.currentReplicationNumber to event.simTime)
                }
            }
            val line = Line(model, type)
            line.setups.add {
                item("Riding", 0.0)                       // on the belt at the end
                item("AtB", 0.0, holdAtDest = 100.0)      // not reached B yet if the run is short; see the length
                item("Holding", 1.5, holdAtEntry = 100.0) // holds the entry cell at the end
                item("Waiting", 2.0)                      // waits for the entry cell at the end
            }
            model.numberOfReplications = 2
            model.lengthOfReplication = 3.5
            model.simulate()
            assertEquals(2, model.currentReplicationNumber, "$type: the second replication ran")
            val afterTheEnd = emitted.filter { (rep, t) -> rep == 1 && t > 3.5 }
            assertEquals(emptyList(), afterTheEnd, "$type: emitted after the end of the first replication")
        }
    }
}
