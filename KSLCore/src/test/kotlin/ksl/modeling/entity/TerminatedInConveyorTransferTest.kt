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
 *  An entity terminated during a transfer from one conveyor to another, at each boundary of the hand-over: what
 *  it holds on the conveyor it leaves (its source request) and what the transfer has asked for or holds on the
 *  next (the target request) are both given up at once, so the items behind it on the first conveyor and the
 *  requests behind it at the second's entry go on as if it had left.
 *
 *  C1 runs A to B and C2 runs B to D, each five one-unit cells, one time unit per cell. V rides C1 from 0 (at B at
 *  5) and transfers to C2 at B. F1 rides C1 behind it and exits at B. H2 holds C2's entry at B from 0 to 10, then
 *  leaves it or rides on. F2 asks for C2's entry at B at 8. The boundaries are those of the transfer's hand-over:
 *  1, the helper's process scheduled and not started; 2, the helper waiting for C2's entry; 3, the helper woken
 *  for it; 4, the helper done and V not yet resumed; 5, V asked to exit C1 (held in its exit cell until it can
 *  land, or moving off); 6, V's item off C1 and V not yet resumed; 7, V riding C2 on its own request.
 *
 *  F1 is compared with a run in which V exits C1 at B at the same instant and does not transfer. F2's access to
 *  C2 is given by hand: it is the instant the entry is free of H2 and of the transfer.
 */
class TerminatedInConveyorTransferTest {

    private val types = listOf(Conveyor.Type.ACCUMULATING, Conveyor.Type.NON_ACCUMULATING)

    private class Pair(parent: ModelElement, type: Conveyor.Type) : ProcessModel(parent, "pair") {
        val c1: Conveyor = Conveyor.builder(this, "C1").conveyorType(type).velocity(1.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("A", "B", 5).build()
        val c2: Conveyor = Conveyor.builder(this, "C2").conveyorType(type).velocity(1.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("B", "D", 5).build()
        val log = mutableListOf<String>()
        val setups = mutableListOf<Pair.() -> Unit>()
        val actors = mutableMapOf<String, Entity>()

        /** Rides C1 from A to B, waits [holdAtB], then transfers to C2 ([transfer]) or exits C1. */
        inner class Rider(val tag: String, val arrive: Double, val holdAtB: Double = 0.0, val transfer: Boolean = true) : Entity(tag) {
            val p: KSLProcess = process("ride") {
                delay(arrive)
                val cr = requestConveyor(c1, "A")
                rideConveyor(cr, "B")
                log.add("$tag at B@$time")
                if (holdAtB > 0.0) delay(holdAtB)
                if (!transfer) {
                    exitConveyor(cr)
                    log.add("$tag off C1@$time")
                    return@process
                }
                val tr = transferTo(cr, c2, "B")
                log.add("$tag off C1@$time")
                rideConveyor(tr, "D")
                exitConveyor(tr)
                log.add("$tag off C2@$time")
            }
        }

        /** Takes C2's entry at B at [arrive], holds it [hold], then leaves it ([ride] false) or rides to D. */
        inner class AtB(
            val tag: String, val arrive: Double, val hold: Double = 0.0, val ride: Boolean = true,
            val afterLeaving: (() -> Unit)? = null
        ) : Entity(tag) {
            val p: KSLProcess = process("atB") {
                delay(arrive)
                val cr = requestConveyor(c2, "B")
                log.add("$tag access C2@$time")
                if (hold > 0.0) delay(hold)
                if (!ride) {
                    exitConveyor(cr)
                    log.add("$tag left C2@$time")
                    afterLeaving?.invoke()
                    return@process
                }
                rideConveyor(cr, "D")
                exitConveyor(cr)
                log.add("$tag off C2@$time")
            }
        }

        fun rider(tag: String, arrive: Double, holdAtB: Double = 0.0, transfer: Boolean = true) =
            Rider(tag, arrive, holdAtB, transfer).also { actors[tag] = it; activate(it.p) }

        fun atB(tag: String, arrive: Double, hold: Double = 0.0, ride: Boolean = true, afterLeaving: (() -> Unit)? = null) =
            AtB(tag, arrive, hold, ride, afterLeaving).also { actors[tag] = it; activate(it.p) }

        fun terminate(tag: String, at: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY) {
            schedule({ _: KSLEvent<Nothing> -> actors.getValue(tag).terminateProcess() }, at, priority = priority)
        }

        /** Runs [action] at time [t]. */
        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        /** Terminates [tag] from an event scheduled now, at [priority]: after what is already scheduled at it. */
        fun terminateNext(tag: String, priority: Int) {
            schedule({ _: KSLEvent<Nothing> -> actors.getValue(tag).terminateProcess() }, 0.0, priority = priority)
        }

        override fun initialize() {
            log.clear()
            actors.clear()
            for (s in setups) s()
        }
    }

    private class Outcome(val log: List<String>, val error: Throwable?, val pair: Pair) {
        fun of(tag: String): List<String> = log.filter { it.startsWith("$tag ") }
    }

    private fun run(type: Conveyor.Type, configure: Pair.() -> Unit = {}, setup: Pair.() -> Unit): Outcome {
        val model = Model("TransferTermination${System.nanoTime()}", autoCSVReports = false)
        val pair = Pair(model, type)
        pair.configure()
        pair.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 80.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return Outcome(pair.log.toList(), error, pair)
    }

    /** The run completes, nothing of V's is left on either conveyor, F2 had C2's entry at [f2Access], F1 as in [reference]. */
    private fun assertLeftAtOnce(type: Conveyor.Type, case: String, terminated: Outcome, reference: Outcome, f2Access: Double) {
        assertNull(terminated.error, "$type, $case: the run stopped: ${terminated.error}")
        assertNull(reference.error, "$type, $case: the reference run stopped: ${reference.error}")
        assertEquals(reference.of("F1"), terminated.of("F1"), "$type, $case: F1, behind V on C1")
        assertEquals("F2 access C2@$f2Access", terminated.of("F2").firstOrNull(), "$type, $case: F2, at C2's entry")
        val v = terminated.pair.actors.getValue("V")
        assertNull(v.conveyorRequest, "$type, $case: V still holds a conveyor request")
        for (c in listOf(terminated.pair.c1, terminated.pair.c2)) {
            assertTrue(c.conveyorRequests().none { it.entity === v || it.entity is ProcessModel.CTransferEntity },
                "$type, $case: a request of V or of its transfer is still on ${c.name}")
        }
    }

    /** The reference for F1: V exits C1 at B at [t] and does not transfer. */
    private fun reference(type: Conveyor.Type, t: Double, h2Rides: Boolean, configure: Pair.() -> Unit = {}): Outcome = run(type, configure) {
        rider("V", 0.0, holdAtB = t - 5.0, transfer = false)
        rider("F1", 1.0, transfer = false)
        atB("H2", 0.0, hold = 10.0, ride = h2Rides)
        atB("F2", 8.0)
    }

    @Test
    @DisplayName("boundary 1: terminated after the transfer's process was scheduled and before it started")
    fun helperScheduled() {
        for (type in types) {
            // V reaches B at 5 in a C1 move at an early priority, resumes at 1 and asks to transfer, which schedules
            // the transfer's process at 10000; the terminator runs at 2, between them
            val configure: Pair.() -> Unit = { c1.defaultMovementPriority = -10 }
            val terminated = run(type, configure) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = false)
                atB("F2", 8.0)
                terminate("V", 5.0, priority = 2)
            }
            assertLeftAtOnce(type, "boundary 1", terminated, reference(type, 5.0, h2Rides = false, configure), f2Access = 10.0)
        }
    }

    @Test
    @DisplayName("boundary 2: terminated while the transfer waits for the next conveyor's entry")
    fun helperWaiting() {
        for (type in types) {
            val terminated = run(type) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = false)
                atB("F2", 8.0)
                terminate("V", 7.0)
            }
            assertLeftAtOnce(type, "boundary 2", terminated, reference(type, 7.0, h2Rides = false), f2Access = 10.0)
        }
    }

    @Test
    @DisplayName("boundary 3: terminated after the transfer was woken for the next conveyor's entry, before it resumed")
    fun helperWoken() {
        for (type in types) {
            val terminated = run(type) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = false, afterLeaving = { actors.getValue("V").terminateProcess() })
                atB("F2", 8.0)
            }
            assertLeftAtOnce(type, "boundary 3", terminated, reference(type, 10.0, h2Rides = false), f2Access = 10.0)
        }
    }

    @Test
    @DisplayName("boundary 4: terminated after the transfer took the next conveyor's entry, before the entity resumed")
    fun helperDone() {
        for (type in types) {
            // H2's exit at 10 wakes the transfer; the terminator, scheduled in the same step at priority 1, runs after
            // the transfer's resume and before V's
            val terminated = run(type) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = false, afterLeaving = { terminateNext("V", priority = ProcessModel.RESUME_PRIORITY) })
                atB("F2", 8.0)
            }
            assertLeftAtOnce(type, "boundary 4", terminated, reference(type, 10.0, h2Rides = false), f2Access = 10.0)
        }
    }

    @Test
    @DisplayName("boundary 5: terminated after asking to exit the first conveyor, held in its exit cell to land or moving off")
    fun exitAsked() {
        for (type in types) {
            // H2 rides at 10 and covers C2's entry at 11. Accumulating: the transfer takes the entry behind it at 11,
            // V asks to exit and is held in C1's exit cell until it can land. Non-accumulating: the transfer waits for
            // the entry to be free, at 12, and V's item is moving off C1 from 12
            val t = if (type == Conveyor.Type.ACCUMULATING) 11.5 else 12.5
            val terminated = run(type) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = true)
                atB("F2", 8.0)
                terminate("V", t)
            }
            // F1's reference: V exits C1 when its exit began, at 11.5 (held, accumulating) or at 12 (moving off)
            val exitBegan = if (type == Conveyor.Type.ACCUMULATING) 11.5 else 12.0
            assertLeftAtOnce(type, "boundary 5", terminated, reference(type, exitBegan, h2Rides = true), f2Access = t)
        }
    }

    @Test
    @DisplayName("boundary 6: terminated after its item left the first conveyor, before the entity resumed")
    fun offTheFirstConveyor() {
        for (type in types) {
            // Accumulating: V crosses when C2's move at 12 frees the entry, in that move; non-accumulating: V's item is
            // off C1 at C1's move at 13. The move that wakes V runs at an early priority; the terminator at 0
            val accumulating = type == Conveyor.Type.ACCUMULATING
            val t = if (accumulating) 12.0 else 13.0
            val configure: Pair.() -> Unit = { if (accumulating) c2.defaultMovementPriority = -10 else c1.defaultMovementPriority = -10 }
            val terminated = run(type, configure) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = true)
                atB("F2", 8.0)
                terminate("V", t, priority = 0)
            }
            val untouched = run(type, configure) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = true)
                atB("F2", 8.0)
            }
            // V's item was off C1 already, so F1 is as without the termination
            assertLeftAtOnce(type, "boundary 6", terminated, untouched, f2Access = t)
        }
    }

    @Test
    @DisplayName("boundary 7: terminated while riding the next conveyor on its own request")
    fun ridingTheNext() {
        for (type in types) {
            val untouched = run(type) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = true)
                atB("F2", 8.0)
            }
            val offC1 = untouched.of("V").first { it.startsWith("V off C1@") }.substringAfter("@").toDouble()
            val terminated = run(type) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = true)
                atB("F2", 8.0)
                terminate("V", offC1 + 1.5)
            }
            // V is already off C1 and through C2's entry, so F1, and F2's access, are as without the termination
            val f2Access = untouched.of("F2").first().substringAfter("@").toDouble()
            assertLeftAtOnce(type, "boundary 7", terminated, untouched, f2Access = f2Access)
        }
    }

    @Test
    @DisplayName("boundary 2 by the queue: removeAndTerminate of the transfer's request ends V and hands V to the callback")
    fun helperWaitingRemovedByItsQueue() {
        for (type in types) {
            val handed = mutableListOf<ProcessModel.Entity>()
            val terminated = run(type) {
                rider("V", 0.0)
                rider("F1", 1.0, transfer = false)
                atB("H2", 0.0, hold = 10.0, ride = false)
                atB("F2", 8.0)
                at(7.0) {
                    val q = c2.accessQueueAt("B") as ConveyorQ
                    val waiting = q.toList().single()
                    q.removeAndTerminate(waiting) { handed.add(it) }
                }
            }
            assertEquals(listOf<ProcessModel.Entity>(terminated.pair.actors.getValue("V")), handed, "$type: the callback is given V")
            assertLeftAtOnce(type, "boundary 2 by the queue", terminated, reference(type, 7.0, h2Rides = false), f2Access = 10.0)
        }
    }

    // ---------- an item waiting to land, whose landing cell's occupant is terminated ----------

    @Test
    @DisplayName("an item waiting to land on a cell whose occupant is terminated lands at the end of the receiving conveyor's move")
    fun landingAfterTheOccupantIsTerminated() {
        // Only an accumulating conveyor makes an item wait to land: onto a non-accumulating one the transfer waits
        // for the entry cell to be free. H2 covers C2's entry from 11, and V waits to land behind it; H2 is
        // terminated at 11.5, while C2's move to 12 is in progress, or while C2 is stopped from 11.2 to 15
        val type = Conveyor.Type.ACCUMULATING
        for (stopped in listOf(false, true)) {
            val build: Pair.(Boolean) -> Unit = { terminateH2 ->
                rider("V", 0.0)
                atB("H2", 0.0, hold = 10.0, ride = true)
                if (stopped) {
                    at(11.2) { c2.stopConveyor() }
                    at(15.0) { c2.startConveyor() }
                }
                if (terminateH2) terminate("H2", 11.5)
            }
            val terminated = run(type) { build(true) }
            val untouched = run(type) { build(false) }
            assertNull(terminated.error, "stopped=$stopped: the run stopped: ${terminated.error}")
            // the move in progress at 12, or the paused move completed 0.8 after the restart at 15
            val expected = if (stopped) "V off C1@15.8" else "V off C1@12.0"
            assertEquals(expected, terminated.of("V").first { it.startsWith("V off C1@") }, "stopped=$stopped")
            assertEquals(untouched.of("V").first { it.startsWith("V off C1@") }, expected, "stopped=$stopped: as without the termination")
        }
    }
}
