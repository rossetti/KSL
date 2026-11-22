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

package ksl.modeling.entity.guiderules

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Rules stated in the waits tutorial (`ksl-waits-tutorial.md`), each checked on the smallest model that shows it:
 *
 *  - `removeAndTerminate` does not record the wait of the request it removes (case 2);
 *  - making a `seizeTrigger()` seizes nothing and queues nothing by itself (case 3);
 *  - a signal reaches only the processes waiting on it when it is sent, and a signal sent with nobody waiting
 *    reaches no one later: a signal has no memory (cases 4 and 5, and the pitfalls);
 *  - an event due after the end of the replication is never put on the calendar, so cancelling it throws (case 4,
 *    the event version).
 */
class WaitsTutorialRulesTest {

    /** A process model whose actors run the bodies the test gives them, and which logs what they see. */
    private open class Base(parent: ModelElement, name: String) : ProcessModel(parent, name) {
        val log = mutableListOf<String>()
        val setups = mutableListOf<Base.() -> Unit>()

        inner class Actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit): Actor =
            Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        /** Schedules `action` in `dt` from now and returns its event. */
        fun scheduleIn(dt: Double, action: () -> Unit): KSLEvent<Nothing> =
            schedule({ _: KSLEvent<Nothing> -> action() }, dt)

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun <T : Base> run(m: Model, base: T, length: Double = 50.0): Pair<T, Throwable?> {
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        val error = try {
            m.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return base to error
    }

    // ---------- case 2: removeAndTerminate ----------

    /**
     *  R (one unit, queue Q) is held by H from 0 to 10. W asks for it at 1, V at 2. At 4 W's request is removed with
     *  `removeAndTerminate`. V is served at 10, after a wait of 8. H, served on arrival, records a wait of 0.
     */
    private class Clinic(parent: ModelElement) : Base(parent, "Clinic") {
        val q = RequestQ(this, "Q")
        val r = ResourceWithQ(this, "R", queue = q)
        var countAtEnd = -1L

        override fun replicationEnded() {
            countAtEnd = q.timeInQ.withinReplicationStatistic.count.toLong()
        }
    }

    @Test
    @DisplayName("removeAndTerminate does not record the wait of the request it removes")
    fun removeAndTerminateRecordsNoWait() {
        val m = Model("RemoveAndTerminate${System.nanoTime()}", autoCSVReports = false)
        val clinic = Clinic(m)
        clinic.setups.add { with(clinic) {
            actor("H") { val a = seize(r); delay(10.0); release(a) }
            val w = actor("W") { delay(1.0); val a = seize(r); log.add("W served@$time"); release(a) }
            actor("V") { delay(2.0); val a = seize(r); log.add("V served@$time"); delay(1.0); release(a) }
            at(4.0) { q.removeAndTerminate(q.first { it.entity === w }) }
        } }
        val (c, error) = run(m, clinic)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("V served@10.0"), c.log)
        // H's wait of 0 (served on arrival) and V's of 8 are recorded; W's 3 units of waiting are not
        assertEquals(2L, c.countAtEnd, "the waits recorded in Q")
        assertEquals(4.0, c.q.timeInQ.acrossReplicationStatistic.average, 1e-12)
    }

    // ---------- case 3: a seize trigger ----------

    /** R has one unit. A trigger is made at 0, while R is idle, and at 2, while H holds R (from 1 to 5). */
    private class Triggers(parent: ModelElement) : Base(parent, "Triggers") {
        val r = ResourceWithQ(this, "R")
    }

    @Test
    @DisplayName("making a seizeTrigger seizes nothing and queues nothing by itself")
    fun seizeTriggerSeizesAndQueuesNothing() {
        val m = Model("SeizeTrigger${System.nanoTime()}", autoCSVReports = false)
        val model = Triggers(m)
        model.setups.add { with(model) {
            actor("Maker") {
                r.seizeTrigger()
                log.add("idle: busy=${r.numBusy} waiting=${r.waitingQ.size}")
                delay(2.0)
                r.seizeTrigger(amountNeeded = 1)
                log.add("held: busy=${r.numBusy} waiting=${r.waitingQ.size}")
                delay(10.0)
                log.add("end: busy=${r.numBusy} waiting=${r.waitingQ.size}")
            }
            actor("H") {
                delay(1.0)
                val a: Allocation = seize(r)
                log.add("H got@$time")
                delay(4.0)
                release(a)
            }
        } }
        val (t, error) = run(m, model)
        assertNull(error, "the run stopped: $error")
        assertEquals(
            listOf("idle: busy=0 waiting=0", "H got@1.0", "held: busy=1 waiting=0", "end: busy=0 waiting=0"),
            t.log
        )
    }

    // ---------- cases 4 and 5: a signal has no memory ----------

    private class Signals(parent: ModelElement) : Base(parent, "Signals") {
        val signal = Signal(this, "Go")
    }

    @Test
    @DisplayName("a signal sent with nobody waiting reaches no one later: a signal has no memory")
    fun signalHasNoMemory() {
        // the signal at 1 finds nobody; C begins to wait at 2, and only the signal at 5 reaches it
        val m = Model("SignalNoMemory${System.nanoTime()}", autoCSVReports = false)
        val model = Signals(m)
        model.setups.add { with(model) {
            at(1.0) { signal.signal() }
            actor("C") { delay(2.0); waitFor(signal); log.add("C resumed@$time") }
            at(5.0) { signal.signal() }
        } }
        val (s, error) = run(m, model)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("C resumed@5.0"), s.log)
    }

    @Test
    @DisplayName("a signal wakes only the entities waiting on it at the moment it is sent")
    fun signalWakesOnlyThoseWaitingThen() {
        // A waits from 0 and is woken by the signal at 1; B begins to wait at 2 and nothing reaches it
        val m = Model("SignalOnlyWaiting${System.nanoTime()}", autoCSVReports = false)
        val model = Signals(m)
        model.setups.add { with(model) {
            actor("A") { waitFor(signal); log.add("A resumed@$time") }
            actor("B") { delay(2.0); waitFor(signal); log.add("B resumed@$time") }
            at(1.0) { signal.signal() }
        } }
        val (s, error) = run(m, model)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("A resumed@1.0"), s.log)
    }

    // ---------- case 4, the event version: an event due after the end ----------

    /** The replication lasts 10. At 5 one event is scheduled for 8 and one for 25, after the end. */
    private class Events(parent: ModelElement) : Base(parent, "Events")

    @Test
    @DisplayName("an event due after the end of the replication is never on the calendar, and cancelling it throws")
    fun eventAfterTheEndIsNotScheduled() {
        val m = Model("EventAfterEnd${System.nanoTime()}", autoCSVReports = false)
        val model = Events(m)
        model.setups.add {
            at(5.0) {
                val inside = scheduleIn(3.0) { log.add("inside ran@$time") }
                val after = scheduleIn(20.0) { log.add("after ran@$time") }
                log.add("inside scheduled=${inside.isScheduled}")
                log.add("after scheduled=${after.isScheduled}")
                val outcome = try {
                    after.cancel = true
                    "cancelled"
                } catch (e: IllegalArgumentException) {
                    "threw"
                }
                log.add("cancelling after: $outcome")
            }
        }
        val (e, error) = run(m, model, length = 10.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(
            listOf("inside scheduled=true", "after scheduled=false", "cancelling after: threw", "inside ran@8.0"),
            e.log
        )
    }
}
