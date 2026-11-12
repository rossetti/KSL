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

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.modeling.queue.Queue
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Waiting for a signal with a time limit. While it waits, the entity is in the signal's queue as
 *  in `waitFor(signal)`, so it is counted, ranked and signalled in the same way; when the time runs
 *  out it leaves the queue, recording its wait when asked to.
 */
class TimedSignalTest {

    private class SignalModel(parent: ModelElement, discipline: Queue.Discipline) : ProcessModel(parent, "timedSignal") {
        val signal = Signal(this, "S", discipline)
        val other = Signal(this, "other")
        val log = mutableListOf<String>()
        val setups = mutableListOf<SignalModel.() -> Unit>()

        inner class Waiter(val tag: String, val body: suspend KSLProcessBuilder.(Waiter) -> Unit) : Entity(tag) {
            val p: KSLProcess = process { body(this@Waiter) }
        }

        fun waiter(tag: String, body: suspend KSLProcessBuilder.(Waiter) -> Unit): Waiter =
            Waiter(tag, body).also { activate(it.p) }

        /** A waiter that waits for the signal with [timeout] and logs the outcome. */
        fun timed(
            tag: String,
            timeout: Double,
            waitStats: Boolean = true,
            timeoutPriority: Int = TIMEOUT_PRIORITY,
            waitPriority: Int? = null
        ): Waiter = waiter(tag) {
            val r = if (waitPriority == null) waitForOrTimeout(signal, timeout, waitStats = waitStats, timeoutPriority = timeoutPriority)
            else waitForOrTimeout(signal, timeout, waitPriority = waitPriority, waitStats = waitStats, timeoutPriority = timeoutPriority)
            log.add(if (r.timedOut) "$tag:timedOut waited=${r.timeWaited}@$time" else "$tag:signalled waited=${r.timeWaited}@$time")
        }

        fun untimed(tag: String): Waiter = waiter(tag) {
            waitFor(signal)
            log.add("$tag:signalled@$time")
        }

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        val timeInQCount: Double get() = signal.waitingQ.timeInQ.withinReplicationStatistic.count

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(
        discipline: Queue.Discipline = Queue.Discipline.FIFO,
        sink: AnimationSink? = null,
        warmUp: Double = 0.0,
        setup: SignalModel.() -> Unit
    ): SignalModel {
        val model = Model("TimedSignal${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = SignalModel(model, discipline)
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = warmUp
        model.simulate()
        return m
    }

    @Test
    @DisplayName("signalled before the deadline: satisfied at the signal; the entity leaves the queue, its wait recorded")
    fun signalledBeforeDeadline() {
        var countAfter = Double.NaN
        val m = run {
            timed("W", 10.0)
            at(3.0) { signal.signalAll() }
            at(4.0) { countAfter = timeInQCount }
        }
        assertEquals(listOf("W:signalled waited=3.0@3.0"), m.log)
        assertEquals(1.0, countAfter)
        assertTrue(m.signal.waitingQ.isEmpty)
    }

    @Test
    @DisplayName("timed out: the entity leaves the queue at the deadline, its wait recorded when waitStats is true and not when false")
    fun timedOut() {
        var inQueueAfter = -1
        var countWithStats = Double.NaN
        val withStats = run {
            timed("W", 5.0)
            at(6.0) { inQueueAfter = signal.waitingQ.size; countWithStats = timeInQCount }
        }
        assertEquals(listOf("W:timedOut waited=5.0@5.0"), withStats.log)
        assertEquals(0, inQueueAfter)
        assertEquals(1.0, countWithStats)

        var countWithoutStats = Double.NaN
        val withoutStats = run {
            timed("W", 5.0, waitStats = false)
            at(6.0) { countWithoutStats = timeInQCount }
        }
        assertEquals(listOf("W:timedOut waited=5.0@5.0"), withoutStats.log)
        assertEquals(0.0, countWithoutStats)
    }

    @Test
    @DisplayName("a signal at the deadline instant wins at default priorities; a timer before the signal's event wins and the signal finds nobody")
    fun deadlineInstant() {
        val signalled = run {
            timed("W", 5.0)
            at(5.0) { signal.signalAll() }
        }
        assertEquals(listOf("W:signalled waited=5.0@5.0"), signalled.log)

        val timedOut = run {
            timed("W", 5.0, timeoutPriority = KSLEvent.DEFAULT_PRIORITY - 1)
            at(5.0) { signal.signalAll() }
        }
        assertEquals(listOf("W:timedOut waited=5.0@5.0"), timedOut.log)
    }

    @Test
    @DisplayName("a timer due between the signal and the resume finds the wait claimed: one outcome, one resume")
    fun timerBetweenSignalAndResume() {
        val m = run {
            // The signal's event, then the timer, then the resume the signal scheduled.
            timed("W", 5.0, timeoutPriority = ProcessModel.RESUME_PRIORITY - 1)
            at(5.0, priority = ProcessModel.RESUME_PRIORITY - 2) { signal.signalAll() }
            waiter("after") { delay(6.0); log.add("after@$time") }
        }
        assertEquals(listOf("W:signalled waited=5.0@5.0", "after@6.0"), m.log)
    }

    @Test
    @DisplayName("signalAll over timed and untimed waiters resumes each once, in queue order; a repeat at the same instant does nothing")
    fun signalAllMixed() {
        val m = run {
            untimed("U0")
            timed("T1", 10.0)
            untimed("U2")
            timed("T3", 10.0)
            at(4.0) { signal.signalAll(); signal.signalAll() }
        }
        assertEquals(
            listOf("U0:signalled@4.0", "T1:signalled waited=4.0@4.0", "U2:signalled@4.0", "T3:signalled waited=4.0@4.0"),
            m.log
        )
        assertTrue(m.signal.waitingQ.isEmpty)
    }

    @Test
    @DisplayName("signalling a range of ranks reaches the entities at those ranks when the signal is given")
    fun signalRange() {
        val m = run {
            timed("T0", 10.0)
            untimed("U1")
            timed("T2", 10.0)
            timed("T3", 10.0)
            at(4.0) { signal.signal(1..2) }
        }
        assertEquals(
            listOf("U1:signalled@4.0", "T2:signalled waited=4.0@4.0", "T0:timedOut waited=10.0@10.0", "T3:timedOut waited=10.0@10.0"),
            m.log
        )
    }

    @Test
    @DisplayName("termination: the entity leaves the queue with nothing recorded, and a later signal finds nobody")
    fun termination() {
        var inQueue = -1
        var count = Double.NaN
        val m = run {
            val w = timed("W", 10.0)
            at(2.0) { w.terminateProcess() }
            at(3.0) { inQueue = signal.waitingQ.size; count = timeInQCount; signal.signalAll() }
        }
        assertEquals(emptyList(), m.log)
        assertEquals(0, inQueue)
        assertEquals(0.0, count)
    }

    @Test
    @DisplayName("a wait with two signal triggers is refused at the call, with the reason")
    fun twoSignalTriggers() {
        var error: String? = null
        run {
            waiter("W") {
                try {
                    waitForFirst(signal.trigger(), other.trigger())
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }
        }
        val msg = assertNotNull(error)
        assertTrue(msg.contains("at most one signal trigger"), msg)
    }

    @Test
    @DisplayName("while waiting the entity reports the state of waitFor(signal)")
    fun state() {
        val seen = mutableListOf<String>()
        val m = run {
            val w = timed("W", 10.0)
            at(1.0) { seen.add("signal=${w.isWaitingForSignal}, type=${w.currentSuspendType}") }
            at(2.0) { signal.signalAll() }
        }
        assertEquals(listOf("signal=true, type=WAIT_FOR_SIGNAL"), seen)
        assertEquals(listOf("W:signalled waited=2.0@2.0"), m.log)
    }

    @Test
    @DisplayName("a priority change while waiting in a ranked queue reorders it as for an untimed wait; the timer still fires")
    fun priorityChangeInRankedQueue() {
        val m = run(discipline = Queue.Discipline.RANKED) {
            timed("A", 10.0, waitPriority = 5)
            val b = timed("B", 10.0, waitPriority = 5)
            at(2.0) { b.priority = 1 }
            at(3.0) { signal.signalFirst() }
        }
        assertEquals(listOf("B:signalled waited=3.0@3.0", "A:timedOut waited=10.0@10.0"), m.log)
    }

    @Test
    @DisplayName("a wait across the warm-up is recorded as an untimed wait ending at the same time")
    fun acrossWarmUp() {
        fun stats(m: SignalModel) = with(m.signal.waitingQ) {
            listOf(timeInQ.withinReplicationStatistic.count, timeInQ.withinReplicationStatistic.weightedAverage, numInQ.withinReplicationStatistic.weightedAverage)
        }
        val untimed = run(warmUp = 5.0) {
            waiter("W") { delay(2.0); waitFor(signal) }
            at(8.0) { signal.signalAll() }
        }
        val bySignal = run(warmUp = 5.0) {
            waiter("W") { delay(2.0); waitForOrTimeout(signal, 20.0) }
            at(8.0) { signal.signalAll() }
        }
        val byTimeout = run(warmUp = 5.0) {
            waiter("W") { delay(2.0); waitForOrTimeout(signal, 6.0) }
        }
        assertEquals(stats(untimed), stats(bySignal))
        assertEquals(stats(untimed), stats(byTimeout))
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace: WaitingForSignal then SignalReceived when signalled; WaitingForSignal then WaitTimedOut once when timed out")
    fun trace() {
        val sink = CollectingSink()
        lateinit var signalled: SignalModel.Waiter
        lateinit var timedOut: SignalModel.Waiter
        run(sink = sink) {
            signalled = waiter("s") { waitForOrTimeout(signal, 10.0) }
            timedOut = waiter("t") { waitForOrTimeout(signal, 4.0, suspensionName = "gate") }
            at(2.0) { signal.signal(signalled) }
        }
        fun of(e: SignalModel.Waiter) = sink.events.mapNotNull { ev ->
            when (ev) {
                is AnimationEvent.WaitingForSignal -> if (ev.entityId == e.id) "WaitingForSignal(${ev.signalName})@${ev.simTime}" else null
                is AnimationEvent.SignalReceived -> if (ev.entityId == e.id) "SignalReceived(${ev.signalName})@${ev.simTime}" else null
                is AnimationEvent.WaitTimedOut -> if (ev.entityId == e.id) "WaitTimedOut(${ev.suspensionName})@${ev.simTime}" else null
                else -> null
            }
        }
        assertEquals(listOf("WaitingForSignal(S)@0.0", "SignalReceived(S)@2.0"), of(signalled))
        assertEquals(listOf("WaitingForSignal(S)@0.0", "WaitTimedOut(gate)@4.0"), of(timedOut))
    }

    @Test
    @DisplayName("a signal trigger held as a property is reused across waits and replications")
    fun triggerReuse() {
        val model = Model("TimedSignalReuse${System.nanoTime()}", autoCSVReports = false)
        val m = SignalModel(model, Queue.Discipline.FIFO)
        val t = m.signal.trigger()
        m.setups.add {
            waiter("W") {
                for (i in 1..2) {
                    val r = waitForFirst(t, timeout = 3.0)
                    log.add(if (r.timedOut) "timedOut@$time" else "signalled@$time")
                }
            }
            at(1.0) { signal.signalAll() }
        }
        model.numberOfReplications = 2
        model.lengthOfReplication = 20.0
        model.simulate()
        assertEquals(listOf("signalled@1.0", "timedOut@4.0"), m.log)
        assertFalse(m.signal.waitingQ.isNotEmpty)
    }
}
