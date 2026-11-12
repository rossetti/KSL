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
import ksl.animation.entityIdOrNull
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Receiving items from a blocking queue with a time limit, and items triggers in a wait for the
 *  first of several things. While it waits, the request is in the queue's request queue exactly as
 *  an untimed receive's; when the wait ends otherwise, the request leaves, its wait recorded, and
 *  the requests behind it are looked at once the wait's claim is over.
 */
class TimedBlockingQueueReceiveTest {

    private class QModel(parent: ModelElement, capacity: Int, firstFillable: Boolean) : ProcessModel(parent, "timedQ") {
        val q = BlockingQueue<Item>(this, capacity, "Q").also {
            if (firstFillable) it.receiverRequestSelector = it.FirstFillableRequest()
        }
        val signal = Signal(this, "S")
        val log = mutableListOf<String>()
        val setups = mutableListOf<QModel.() -> Unit>()

        inner class Item(val tag: String) : QObject() {
            override fun toString(): String = tag
        }

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
            val p: KSLProcess = process { body(this@Actor) }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.(Actor) -> Unit): Actor =
            Actor(tag, body).also { activate(it.p) }

        fun record(tag: String, r: WaitResult<List<Item>>) {
            log.add(if (r.timedOut) "$tag:timedOut waited=${r.timeWaited}@$time" else "$tag:${r.value} waited=${r.timeWaited}@$time")
        }

        /** Waits for [amount] items starting with [prefix], with [timeout], and logs the outcome. */
        fun timed(tag: String, timeout: Double, amount: Int = 1, prefix: String = "", timeoutPriority: Int = TIMEOUT_PRIORITY): Actor =
            actor(tag) {
                record(tag, waitForItemsOrTimeout(q, timeout, amount, { it.tag.startsWith(prefix) }, timeoutPriority = timeoutPriority))
            }

        fun untimed(tag: String, amount: Int = 1, prefix: String = ""): Actor = actor(tag) {
            val got = waitForItems(q, amount, { it.tag.startsWith(prefix) })
            log.add("$tag:$got@$time")
        }

        fun send(tag: String) = q.sendToChannel(Item(tag))

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        val requestWaits: Double get() = q.requestQ.timeInQ.withinReplicationStatistic.count

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(
        capacity: Int = Int.MAX_VALUE,
        firstFillable: Boolean = false,
        warmUp: Double = 0.0,
        sink: AnimationSink? = null,
        setup: QModel.() -> Unit
    ): QModel {
        val model = Model("TimedQ${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = QModel(model, capacity, firstFillable)
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = warmUp
        model.simulate()
        return m
    }

    @Test
    @DisplayName("items already in the channel, no request waiting: taken at once")
    fun fastPath() {
        val m = run {
            send("a1"); send("a2")
            timed("R", 5.0, amount = 2)
        }
        assertEquals(listOf("R:[a1, a2] waited=0.0@0.0"), m.log)
        assertTrue(m.q.channelQ.isEmpty)
    }

    @Test
    @DisplayName("filled before the deadline: satisfied when the items arrive")
    fun filledBeforeDeadline() {
        val m = run {
            timed("R", 10.0)
            at(3.0) { send("a") }
        }
        assertEquals(listOf("R:[a] waited=3.0@3.0"), m.log)
    }

    @Test
    @DisplayName("timed out: the request leaves the request queue, its wait recorded; nothing is taken")
    fun timedOut() {
        var requestsAfter = -1
        var waitsAfter = Double.NaN
        var channelAfter = -1
        val m = run {
            timed("R", 5.0, amount = 2)
            at(2.0) { send("a") }
            at(6.0) { requestsAfter = q.requestQ.size; waitsAfter = requestWaits; channelAfter = q.channelQ.size }
        }
        assertEquals(listOf("R:timedOut waited=5.0@5.0"), m.log)
        assertEquals(0, requestsAfter)
        assertEquals(1.0, waitsAfter)
        assertEquals(1, channelAfter, "the item stays in the channel")
    }

    @Test
    @DisplayName("items arriving at the deadline instant win at default priorities")
    fun deadlineInstant() {
        val m = run {
            timed("R", 5.0)
            at(5.0) { send("a") }
        }
        assertEquals(listOf("R:[a] waited=5.0@5.0"), m.log)
    }

    @Test
    @DisplayName("a request already selected when the timer fires is not timed out")
    fun selectedBeforeTimer() {
        val m = run {
            // The send selects the request; the timer then runs before the resume the selection scheduled.
            timed("R", 5.0, timeoutPriority = ProcessModel.RESUME_PRIORITY - 1)
            at(5.0, priority = ProcessModel.RESUME_PRIORITY - 2) { send("a") }
        }
        assertEquals(listOf("R:[a] waited=5.0@5.0"), m.log)
    }

    @Test
    @DisplayName("a timed-out request at the head lets the one behind it be filled at the timeout")
    fun timedOutHeadUnblocks() {
        val m = run {
            timed("Head", 5.0, amount = 2)
            untimed("Behind")
            at(2.0) { send("a") }
        }
        assertEquals(listOf("Head:timedOut waited=5.0@5.0", "Behind:[a]@5.0"), m.log)
    }

    @Test
    @DisplayName("a losing request at the head lets the one behind it be filled at the claim")
    fun losingHeadUnblocks() {
        val m = run {
            actor("Head") {
                val items = q.itemsTrigger(2)
                val sig = signal.trigger()
                val r = waitForFirst(items, sig, timeout = 20.0)
                log.add("Head:${if (r.firedBy(sig)) "signal" else "other"}@$time")
            }
            untimed("Behind")
            at(2.0) { send("a") }
            at(4.0) { signal.signalAll() }
        }
        assertEquals(listOf("Behind:[a]@4.0", "Head:signal@4.0"), m.log.sorted())
        assertEquals(2.0, m.requestWaits, "the loser's wait and the behind request's wait are both recorded")
    }

    @Test
    @DisplayName("termination: the request leaves with nothing recorded; a later item stays in the channel")
    fun termination() {
        var requestsAfter = -1
        var waitsAfter = Double.NaN
        var channelAfter = -1
        val m = run {
            val r = timed("R", 10.0)
            at(2.0) { r.terminateProcess() }
            at(3.0) { send("a"); requestsAfter = q.requestQ.size; waitsAfter = requestWaits; channelAfter = q.channelQ.size }
        }
        assertEquals(emptyList(), m.log)
        assertEquals(0, requestsAfter)
        assertEquals(0.0, waitsAfter)
        assertEquals(1, channelAfter)
    }

    @Test
    @DisplayName("two items triggers on one queue: an item matching both goes to the first passed")
    fun twoItemsTriggers() {
        fun scenario(swap: Boolean) = run {
            actor("W") {
                val aTrigger = q.itemsTrigger(1, { it.tag.startsWith("a") })
                val bTrigger = q.anyItemsTrigger({ it.tag.endsWith("b") })
                val r = if (swap) waitForFirst(bTrigger, aTrigger, timeout = 20.0) else waitForFirst(aTrigger, bTrigger, timeout = 20.0)
                val which = if (r.firedBy(aTrigger)) "a" else "b"
                log.add("W:$which:${r.valueOf(r.trigger!!)}@$time")
            }
            at(3.0) { send("ab") }
        }
        val first = scenario(swap = false)
        assertEquals(listOf("W:a:[ab]@3.0"), first.log)
        assertEquals(0, first.q.requestQ.size)
        assertEquals(listOf("W:b:[ab]@3.0"), scenario(swap = true).log)
    }

    @Test
    @DisplayName("a request selected while it registers ends the wait at once and leaves no resume behind")
    fun selectedWhileRegistering() {
        val m = run(firstFillable = true) {
            untimed("Big", amount = 5)
            at(1.0) { send("a") }
            actor("R") {
                delay(2.0)
                record("R", waitForItemsOrTimeout(q, 10.0))
                delay(10.0)
                log.add("R:delayed@$time")
            }
        }
        assertEquals(listOf("R:[a] waited=0.0@2.0", "R:delayed@12.0"), m.log)
    }

    @Test
    @DisplayName("while waiting the entity reports the state of the untimed receive")
    fun state() {
        val seen = mutableListOf<String>()
        run {
            val a = timed("A", 10.0)
            val b = actor("B") { waitForAnyItemsOrTimeout(q, 10.0, { true }) }
            at(1.0) { seen.add("${a.isBlockedReceiving}:${a.currentSuspendType}"); seen.add("${b.isBlockedReceiving}:${b.currentSuspendType}") }
        }
        assertEquals(listOf("true:WAIT_FOR_ITEMS", "true:WAIT_FOR_ANY_ITEMS"), seen)
    }

    @Test
    @DisplayName("the extension forms and the any-items form take what their untimed forms take")
    fun extensionForms() {
        val m = run {
            actor("A") { record("A", q.waitForOrTimeout(10.0, amount = 2)) }
            at(1.0) { send("x1"); send("x2"); send("x3") }
            actor("B") { delay(2.0); record("B", q.waitForAnyOrTimeout(10.0, { true })) }
            actor("C") { delay(3.0); record("C", waitForAnyItemsOrTimeout(q, 1.0, { true })) }
        }
        assertEquals(listOf("A:[x1, x2] waited=1.0@1.0", "B:[x3] waited=0.0@2.0", "C:timedOut waited=1.0@4.0"), m.log)
    }

    @Test
    @DisplayName("a full queue of rejected items with blocked senders: the receiver times out; the senders stay blocked; the counts balance")
    fun fullQueueOfRejectedItems() {
        val after = mutableListOf<Number>()
        val m = run(capacity = 2) {
            send("x1"); send("x2")
            actor("S") { send(Item("x3"), q); log.add("S:sent@$time") }
            timed("R", 5.0, prefix = "a")
            at(6.0) { after.addAll(listOf(q.senderQ.size, q.channelQ.size, requestWaits, q.requestQ.size)) }
        }
        assertEquals(listOf("R:timedOut waited=5.0@5.0"), m.log)
        assertEquals(listOf<Number>(1, 2, 1.0, 0), after, "senders blocked, channel full, one request wait recorded, no request left")
    }

    @Test
    @DisplayName("first-fillable selection over timed and untimed requests keeps its order; a timed-out request is never selected")
    fun firstFillableMix() {
        val order = run(firstFillable = true) {
            timed("T", 10.0, prefix = "a")
            untimed("U", prefix = "a")
            at(2.0) { send("a1") }
        }
        assertEquals(listOf("T:[a1] waited=2.0@2.0"), order.log)

        val afterTimeout = run(firstFillable = true) {
            timed("T", 3.0, prefix = "a")
            untimed("U", prefix = "a")
            at(5.0) { send("a1") }
        }
        assertEquals(listOf("T:timedOut waited=3.0@3.0", "U:[a1]@5.0"), afterTimeout.log)
    }

    @Test
    @DisplayName("a wait across the warm-up is recorded as an untimed wait ending at the same time")
    fun acrossWarmUp() {
        fun stats(m: QModel) = with(m.q.requestQ) {
            listOf(timeInQ.withinReplicationStatistic.count, timeInQ.withinReplicationStatistic.weightedAverage, numInQ.withinReplicationStatistic.weightedAverage)
        }
        val untimed = run(warmUp = 5.0) {
            actor("R") { delay(2.0); waitForItems(q, 1) }
            at(8.0) { send("a") }
        }
        val byItems = run(warmUp = 5.0) {
            actor("R") { delay(2.0); waitForItemsOrTimeout(q, 20.0) }
            at(8.0) { send("a") }
        }
        val byTimeout = run(warmUp = 5.0) {
            actor("R") { delay(2.0); waitForItemsOrTimeout(q, 6.0) }
        }
        assertEquals(stats(untimed), stats(byItems))
        assertEquals(stats(untimed), stats(byTimeout))
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace: a timed receive emits no wait event when filled, and WaitTimedOut once when it times out")
    fun trace() {
        val sink = CollectingSink()
        lateinit var filled: QModel.Actor
        lateinit var timedOut: QModel.Actor
        run(sink = sink) {
            filled = timed("F", 10.0, prefix = "f")
            timedOut = actor("T") { waitForItemsOrTimeout(q, 4.0, 1, { it.tag.startsWith("t") }, suspensionName = "pick") }
            at(2.0) { send("f") }
        }
        val waitTypes = setOf(
            AnimationEvent.WaitingForSignal::class, AnimationEvent.SignalReceived::class, AnimationEvent.WaitTimedOut::class
        )
        assertEquals(emptyList(), sink.events.filter { it::class in waitTypes && it.entityIdOrNull == filled.id })
        assertEquals(
            listOf<AnimationEvent>(AnimationEvent.WaitTimedOut(4.0, timedOut.id, "pick")),
            sink.events.filter { it::class in waitTypes && it.entityIdOrNull == timedOut.id }
        )
    }

    @Test
    @DisplayName("an amount below one is refused when the trigger is made")
    fun amountChecked() {
        var message: String? = null
        run { message = runCatching { q.itemsTrigger(0) }.exceptionOrNull()?.message }
        assertTrue(message!!.contains(">= 1"), message)
    }
}
