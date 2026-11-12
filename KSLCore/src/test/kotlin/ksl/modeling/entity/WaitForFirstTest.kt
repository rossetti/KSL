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
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Waiting for the first of a message, a signal and items. Whichever fires first wins and the
 *  others are withdrawn at that instant, each as its source withdraws a wait that ends: the
 *  mailbox waiter removed, the request removed with its wait recorded, the entity out of the
 *  signal's queue with its wait recorded. Ties at one instant are decided by the order of the
 *  events that cause them, never by argument order.
 */
class WaitForFirstTest {

    private class MixModel(parent: ModelElement) : AgentModel(parent, "mix") {
        val signal = Signal(this, "S")
        val q = BlockingQueue<Item>(this, name = "Q")
        val log = mutableListOf<String>()
        val setups = mutableListOf<MixModel.() -> Unit>()

        inner class Hub : Agent("hub")

        val hub = Hub()
        val mailbox get() = hub.mailbox

        /** Triggers held as properties, so they are reused by every wait and every replication. */
        val messageTrigger = hub.mailbox.trigger()
        val signalTrigger = signal.trigger()
        val itemsTrigger = q.itemsTrigger()

        inner class Item(val tag: String) : QObject() {
            override fun toString(): String = tag
        }

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body(this@Actor) }
        }

        /** An entity whose two processes run as a sequence: a timed mixed wait, then a second process. */
        inner class Sequenced : Entity("seq") {
            val first: KSLProcess = process("first") { mixed("seq", timeout = 3.0) }
            val second: KSLProcess = process("second") { log.add("second@$time") }

            init {
                useProcessSequence = true
                processSequence = mutableListOf(first, second)
            }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.(Actor) -> Unit): Actor =
            Actor(tag, body).also { activate(it.p) }

        fun name(r: WaitForFirstResult): String = when (r.trigger) {
            messageTrigger -> "message"
            signalTrigger -> "signal"
            itemsTrigger -> "items"
            else -> "timeout"
        }

        /** Waits for the first of the three, in the given order, and logs which and when. */
        suspend fun KSLProcessBuilder.mixed(
            tag: String,
            order: List<WaitTrigger<*>> = listOf(messageTrigger, signalTrigger, itemsTrigger),
            timeout: Double = 20.0,
            timeoutPriority: Int = TIMEOUT_PRIORITY
        ): WaitForFirstResult {
            val r = waitForFirst(order, timeout = timeout, timeoutPriority = timeoutPriority)
            log.add("$tag:${name(r)}@$time")
            return r
        }

        fun send(tag: String) = q.sendToChannel(Item(tag))
        fun message(p: String) = mailbox.deliver(AgentMessage.Inform(hub, p))

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        /** What each source holds and records, read during the run. */
        fun sources(): String =
            "pendingMessages=${mailbox.size}, inSignalQ=${signal.waitingQ.size}, " +
                    "signalWaits=${signal.waitingQ.timeInQ.withinReplicationStatistic.count.toInt()}, " +
                    "requests=${q.requestQ.size}, requestWaits=${q.requestQ.timeInQ.withinReplicationStatistic.count.toInt()}"

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(reps: Int = 1, sink: AnimationSink? = null, setup: MixModel.() -> Unit): MixModel {
        val model = Model("Mix${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = MixModel(model)
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    // ── Each kind winning, the losers withdrawn at the claim ────────────────

    @Test
    @DisplayName("a message wins: the entity leaves the signal's queue and the request leaves, both recorded; a second message at the instant is kept")
    fun messageWins() {
        var seen = ""
        val m = run {
            actor("W") { mixed("W") }
            at(3.0) { message("m1"); message("m2"); seen = sources() }
        }
        assertEquals(listOf("W:message@3.0"), m.log)
        assertEquals("pendingMessages=1, inSignalQ=0, signalWaits=1, requests=0, requestWaits=1", seen)
    }

    @Test
    @DisplayName("a signal wins: the request leaves recorded and the mailbox waiter is gone; the winner leaves the signal's queue after its resume")
    fun signalWins() {
        var atClaim = ""
        var after = ""
        val m = run {
            actor("W") { mixed("W") }
            at(3.0) { signal.signalAll(); message("m"); atClaim = sources() }
            at(4.0) { after = sources() }
        }
        assertEquals(listOf("W:signal@3.0"), m.log)
        assertEquals("pendingMessages=1, inSignalQ=1, signalWaits=0, requests=0, requestWaits=1", atClaim)
        assertEquals("pendingMessages=1, inSignalQ=0, signalWaits=1, requests=0, requestWaits=1", after)
    }

    @Test
    @DisplayName("items win: the entity leaves the signal's queue recorded and the mailbox waiter is gone; the items are taken after the resume")
    fun itemsWin() {
        var atClaim = ""
        val m = run {
            actor("W") {
                val r = mixed("W")
                log.add("got ${r.valueOf(itemsTrigger)}")
            }
            at(3.0) { send("i1"); message("m"); atClaim = sources() }
        }
        assertEquals(listOf("W:items@3.0", "got [i1]"), m.log)
        assertEquals("pendingMessages=1, inSignalQ=0, signalWaits=1, requests=1, requestWaits=0", atClaim)
    }

    @Test
    @DisplayName("the timer wins: every registration is withdrawn, each recorded as its source records it")
    fun timerWins() {
        var seen = ""
        val m = run {
            actor("W") { mixed("W", timeout = 5.0) }
            at(6.0) { message("late"); seen = sources() }
        }
        assertEquals(listOf("W:timeout@5.0"), m.log)
        assertEquals("pendingMessages=1, inSignalQ=0, signalWaits=1, requests=0, requestWaits=1", seen)
    }

    // ── Ties at one instant ─────────────────────────────────────────────────

    @Test
    @DisplayName("ties: the higher-priority causing event wins, whatever the argument order")
    fun higherPriorityCauseWins() {
        for (order in listOf(true, false)) {
            val m = run {
                actor("W") { mixed("W", if (order) listOf(itemsTrigger, signalTrigger) else listOf(signalTrigger, itemsTrigger)) }
                at(3.0) { send("i") }
                at(3.0, priority = KSLEvent.DEFAULT_PRIORITY - 1) { signal.signalAll() }
            }
            assertEquals(listOf("W:signal@3.0"), m.log, "argument order items-first = $order")
        }
    }

    @Test
    @DisplayName("ties: at equal priority the earlier-scheduled causing event wins")
    fun earlierScheduledCauseWins() {
        val itemsFirst = run {
            actor("W") { mixed("W", listOf(signalTrigger, itemsTrigger)) }
            at(3.0) { send("i") }
            at(3.0) { signal.signalAll() }
        }
        assertEquals(listOf("W:items@3.0"), itemsFirst.log)
        val signalFirst = run {
            actor("W") { mixed("W", listOf(itemsTrigger, signalTrigger)) }
            at(3.0) { signal.signalAll() }
            at(3.0) { send("i") }
        }
        assertEquals(listOf("W:signal@3.0"), signalFirst.log)
    }

    @Test
    @DisplayName("ties: two causes in one event are decided by the order of the code")
    fun codeOrder() {
        val signalThenItems = run {
            actor("W") { mixed("W", listOf(itemsTrigger, signalTrigger)) }
            at(3.0) { signal.signalAll(); send("i") }
        }
        assertEquals(listOf("W:signal@3.0"), signalThenItems.log)
        val itemsThenSignal = run {
            actor("W") { mixed("W", listOf(signalTrigger, itemsTrigger)) }
            at(3.0) { send("i"); signal.signalAll() }
        }
        assertEquals(listOf("W:items@3.0"), itemsThenSignal.log)
    }

    @Test
    @DisplayName("ties: the timer at the instant of a cause loses at the default priority and wins with an earlier timeoutPriority")
    fun timerTie() {
        val causeWins = run {
            actor("W") { mixed("W", timeout = 3.0) }
            at(3.0) { message("m") }
        }
        assertEquals(listOf("W:message@3.0"), causeWins.log)
        var pending = -1
        val timerWins = run {
            actor("W") { mixed("W", timeout = 3.0, timeoutPriority = KSLEvent.DEFAULT_PRIORITY - 1) }
            at(3.0) { message("m"); pending = mailbox.size }
        }
        assertEquals(listOf("W:timeout@3.0"), timerWins.log)
        assertEquals(1, pending)
    }

    // ── The result ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the result: trigger, firedBy, typed valueOf, both misuse errors, and still valid after the triggers are reused")
    fun result() {
        var first: WaitForFirstResult? = null
        var unknown: WaitTrigger<Unit>? = null
        val m = run {
            unknown = signal.trigger()
            actor("W") {
                first = mixed("W")
                mixed("W")
            }
            at(3.0) { send("i1") }
            at(5.0) { signal.signalAll() }
        }
        assertEquals(listOf("W:items@3.0", "W:signal@5.0"), m.log)
        val r = first!!
        assertTrue(r.firedBy(m.itemsTrigger))
        assertTrue(!r.firedBy(m.signalTrigger))
        val items: List<MixModel.Item> = r.valueOf(m.itemsTrigger)
        assertEquals("[i1]", items.toString(), "the first result keeps its value after the triggers were used again")
        assertNull(r.valueOrNull(m.signalTrigger))
        assertFailsWith<IllegalStateException> { r.valueOf(m.signalTrigger) }
        assertFailsWith<IllegalArgumentException> { r.valueOf(unknown!!) }
        assertFailsWith<IllegalArgumentException> { r.valueOrNull(unknown!!) }
    }

    // ── Termination and the end of a replication ────────────────────────────

    @Test
    @DisplayName("termination of a mixed wait withdraws every registration with nothing recorded, and nothing fires afterwards")
    fun termination() {
        var seen = ""
        val m = run {
            val w = actor("W") { mixed("W", timeout = 5.0) }
            at(2.0) { w.terminateProcess() }
            at(3.0) { message("m"); send("i"); signal.signalAll(); seen = sources() }
        }
        assertEquals(emptyList(), m.log)
        assertEquals("pendingMessages=1, inSignalQ=0, signalWaits=0, requests=0, requestWaits=0", seen)
    }

    @Test
    @DisplayName("a mixed wait open at the end of a replication leaves nothing behind for the next")
    fun openAtEndOfReplication() {
        val seen = mutableListOf<String>()
        val m = run(reps = 3) {
            actor("W") { mixed("W", timeout = Double.POSITIVE_INFINITY) }
            at(1.0) { seen.add(sources()) }
        }
        assertEquals(List(3) { "pendingMessages=0, inSignalQ=1, signalWaits=0, requests=1, requestWaits=0" }, seen)
        assertEquals(emptyList(), m.log)
    }

    @Test
    @DisplayName("triggers held as properties are reused after a timeout, after a termination, across replications and a second simulate")
    fun reuse() {
        val model = Model("MixReuse${System.nanoTime()}", autoCSVReports = false)
        val m = MixModel(model)
        m.setups.add {
            val doomed = actor("D") { mixed("D") }
            actor("W") {
                mixed("W", timeout = 2.0)
                mixed("W")
                mixed("W")
            }
            at(1.0) { doomed.terminateProcess() }
            at(3.0) { signal.signalAll() }
            at(4.0) { message("m") }
        }
        model.numberOfReplications = 2
        model.lengthOfReplication = 20.0
        val runs = mutableListOf<List<String>>()
        repeat(2) {
            model.simulate()
            runs.add(m.log.toList())
        }
        assertEquals(listOf("W:timeout@2.0", "W:signal@3.0", "W:message@4.0"), runs[0])
        assertEquals(runs[0], runs[1], "a second simulate behaves as the first")
    }

    // ── Processes around a timed wait ───────────────────────────────────────

    @Test
    @DisplayName("a timed wait in a sub-process: the sub-process times out and continues; its caller resumes when it completes")
    fun subProcess() {
        val m = run {
            val worker = Actor("worker") { mixed("worker", timeout = 4.0) }
            actor("caller") { waitFor(worker.p); log.add("caller@$time") }
        }
        assertEquals(listOf("worker:timeout@4.0", "caller@4.0"), m.log)
    }

    @Test
    @DisplayName("the caller terminated while its sub-process waits: the sub-process is terminated and its wait withdrawn")
    fun callerTerminated() {
        var seen = ""
        var workerGroup: Any? = Unit
        val m = run {
            val worker = Actor("worker") { mixed("worker", timeout = 4.0) }
            val caller = actor("caller") { waitFor(worker.p); log.add("caller@$time") }
            at(2.0) { caller.terminateProcess(); workerGroup = worker.myWaitGroup; seen = sources() }
        }
        assertEquals(emptyList(), m.log, "neither the worker's timer nor anything else fires")
        assertNull(workerGroup)
        assertEquals("pendingMessages=0, inSignalQ=0, signalWaits=0, requests=0, requestWaits=0", seen)
    }

    @Test
    @DisplayName("blockUntilCompleted on a process in a timed wait is released when the process completes after its timeout")
    fun blockUntilCompleted() {
        val m = run {
            val worker = actor("worker") { mixed("worker", timeout = 4.0); delay(1.0) }
            actor("blocker") { blockUntilCompleted(worker.p); log.add("blocker@$time") }
        }
        assertEquals(listOf("worker:timeout@4.0", "blocker@5.0"), m.log)
    }

    @Test
    @DisplayName("in a process sequence, the next process starts after a timed wait times out and its process completes")
    fun processSequence() {
        val m = run {
            startProcessSequence(Sequenced())
        }
        assertEquals(listOf("seq:timeout@3.0", "second@3.0"), m.log)
    }

    // ── The trace ───────────────────────────────────────────────────────────

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace of a mixed wait: WaitingForSignal at registration; no SignalReceived when another wins; WaitTimedOut once on a timeout")
    fun trace() {
        val sink = CollectingSink()
        lateinit var itemsWinner: MixModel.Actor
        lateinit var timedOut: MixModel.Actor
        run(sink = sink) {
            itemsWinner = actor("I") { mixed("I") }
            timedOut = actor("T") { waitForFirst(signalTrigger, messageTrigger, timeout = 4.0, suspensionName = "either") }
            at(2.0) { send("i") }
        }
        fun of(e: MixModel.Actor) = sink.events.mapNotNull { ev ->
            when (ev) {
                is AnimationEvent.WaitingForSignal -> if (ev.entityId == e.id) "WaitingForSignal@${ev.simTime}" else null
                is AnimationEvent.SignalReceived -> if (ev.entityId == e.id) "SignalReceived@${ev.simTime}" else null
                is AnimationEvent.WaitTimedOut -> if (ev.entityId == e.id) "WaitTimedOut(${ev.suspensionName})@${ev.simTime}" else null
                else -> null
            }
        }
        assertEquals(listOf("WaitingForSignal@0.0"), of(itemsWinner))
        assertEquals(listOf("WaitingForSignal@0.0", "WaitTimedOut(either)@4.0"), of(timedOut))
    }
}
