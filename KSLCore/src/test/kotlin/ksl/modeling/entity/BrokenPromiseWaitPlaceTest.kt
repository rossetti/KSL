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

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.receiveMessage
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A seize trigger wins a `waitForFirst`, and in the same instant, before the entity resumes, what it was promised
 *  goes away. The wait goes on for all its triggers, and each of the other triggers keeps its place with its
 *  source: a mailbox receiver among the receivers, an entity in a signal's queue, an items request among the
 *  requests, another seize trigger's request in its resource's queue. Each wait is recorded once, for its whole
 *  length. While the win is undecided, each source passes over the waiting entity as if it had left. When the
 *  promise is kept, the outcome is what it has always been.
 *
 *  In every case W begins its wait at 1 while H holds R, and at 5 H releases R, which wakes W's seize trigger.
 *  In the cases where the promise breaks, H fails R in the same step as the release.
 */
class BrokenPromiseWaitPlaceTest {

    private class Shop(parent: ModelElement) : AgentModel(parent, "Shop") {
        val r = ResourceWithQ(this, "R", capacity = 1)
        val r2 = ResourceWithQ(this, "R2", capacity = 1)
        val r3 = ResourceWithQ(this, "R3", capacity = 1)
        val q = BlockingQueue<Item>(this, name = "Q")
        val signal = Signal(this, "S")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Hub : Agent("hub")

        val hub = Hub()

        inner class Item(val tag: String) : QObject() {
            override fun toString(): String = tag
        }

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) { body() }

            /** Moves this entity's request waiting for [from] to [to]. */
            fun moveRequest(from: ResourceWithQ, to: ResourceWithQ) {
                val request = from.myWaitingQ.first { it.entity === this } as ResourceRequest
                moveRequestToResource(request, from.myWaitingQ, to, ProcessModel.RESUME_PRIORITY)
            }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        fun message(p: String) = hub.mailbox.deliver(AgentMessage.Inform(hub, p))

        /** H holds R from 0, and at 5 releases it and runs [inTheSameStep] in the same step. */
        fun holder(inTheSameStep: () -> Unit) = actor("H") {
            val a = seize(r)
            delay(5.0)
            release(a)
            inTheSameStep()
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(setup: Shop.() -> Unit): Shop {
        val model = Model("BrokenPromiseWaitPlace${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        var error: Throwable? = null
        try {
            model.simulate()
        } catch (e: Throwable) {
            error = e
        }
        assertNull(error, "the run stopped: $error")
        return shop
    }

    @Test
    @DisplayName("a broken promise: the wait's mailbox receiver keeps its place among the receivers")
    fun mailboxReceiverKeepsItsPlace() {
        val shop = run {
            holder { r.fail() }
            actor("W") {
                delay(1.0)
                val m = hub.mailbox.trigger()
                val res = waitForFirst(r.seizeTrigger(), m, timeout = 20.0)
                log.add("W ${if (res.firedBy(m)) "message" else if (res.timedOut) "timeout" else "seize"}@$time")
            }
            actor("X") {
                delay(3.0)
                receiveMessage(hub.mailbox)
                log.add("X message@$time")
            }
            at(6.0) { message("m1") }
        }
        assertEquals(listOf("W message@6.0"), shop.log)
    }

    @Test
    @DisplayName("a broken promise, the wait then won by its signal: the wait in the signal's queue is recorded once, for its whole length")
    fun signalWaitRecordedOnce() {
        var stats = ""
        val shop = run {
            holder { r.fail() }
            actor("W") {
                delay(1.0)
                val s = signal.trigger()
                val res = waitForFirst(r.seizeTrigger(), s)
                log.add("W ${if (res.firedBy(s)) "signal" else "other"}@$time waited=${res.timeWaited}")
            }
            at(7.0) { signal.signalAll() }
            at(8.0) {
                val st = signal.waitingQ.timeInQ.withinReplicationStatistic
                stats = "count=${st.count} average=${st.weightedAverage}"
            }
        }
        assertEquals(listOf("W signal@7.0 waited=6.0"), shop.log)
        assertEquals("count=1.0 average=6.0", stats)
    }

    @Test
    @DisplayName("a broken promise: the wait's items request keeps its place, and its wait is recorded once")
    fun itemsRequestKeepsItsPlace() {
        var stats = ""
        val shop = run {
            holder { r.fail() }
            actor("W") {
                delay(1.0)
                val items = q.itemsTrigger(1)
                val res = waitForFirst(r.seizeTrigger(), items)
                log.add("W ${if (res.firedBy(items)) "items" else "other"}@$time")
            }
            actor("X") {
                delay(3.0)
                waitForItems(q, 1)
                log.add("X items@$time")
            }
            at(6.0) { q.sendToChannel(Item("i1")) }
            at(8.0) {
                val st = q.requestQ.timeInQ.withinReplicationStatistic
                stats = "count=${st.count} average=${st.weightedAverage}"
            }
        }
        assertEquals(listOf("W items@6.0"), shop.log)
        assertEquals("count=1.0 average=5.0", stats, "W's one wait for items, from 1 to 6")
    }

    @Test
    @DisplayName("a broken promise: the entity stays first in a FIFO signal's queue, and a signalFirst while the win is undecided goes to the next entity")
    fun signalQueuePlaceAndSignalFirstDuringTheHold() {
        val shop = run {
            holder {
                signal.signalFirst()
                r.fail()
            }
            actor("W") {
                delay(1.0)
                val s = signal.trigger()
                val res = waitForFirst(r.seizeTrigger(), s)
                log.add("W ${if (res.firedBy(s)) "signal" else "other"}@$time")
            }
            actor("X") {
                delay(3.0)
                waitFor(signal)
                log.add("X signal@$time")
            }
            actor("Y") {
                delay(4.0)
                waitFor(signal)
                log.add("Y signal@$time")
            }
            at(7.0) { signal.signalFirst() }
        }
        assertEquals(listOf("X signal@5.0", "W signal@7.0"), shop.log)
    }

    @Test
    @DisplayName("a broken promise: another seize trigger of the same wait keeps its place in its resource's queue")
    fun otherSeizeTriggerKeepsItsPlace() {
        val shop = run {
            holder { r.fail() }
            actor("K") {
                val a = seize(r2)
                delay(20.0)
                release(a)
            }
            actor("W") {
                delay(1.0)
                val second = r2.seizeTrigger()
                val first = r.seizeTrigger()
                val res = waitForFirst(first, second)
                log.add("W ${if (res.firedBy(second)) "R2" else "R"}@$time")
                release(if (res.firedBy(second)) res.valueOf(second) else res.valueOf(first))
            }
            actor("V") {
                delay(3.0)
                val a = seize(r2)
                log.add("V R2@$time")
                release(a)
            }
        }
        assertEquals("W R2@20.0", shop.log.first())
    }

    @Test
    @DisplayName("another seize trigger's request moved while the win is undecided only changes queues: the promise kept, it leaves the new queue")
    fun heldRequestMovedThenPromiseKept() {
        var after = ""
        val shop = run {
            lateinit var w: Shop.Actor
            holder { w.moveRequest(r2, r3) }
            actor("K") {
                val a = seize(r2)
                delay(20.0)
                release(a)
            }
            w = actor("W") {
                delay(1.0)
                val first = r.seizeTrigger()
                val second = r2.seizeTrigger()
                val res = waitForFirst(first, second)
                log.add("W ${if (res.firedBy(first)) "R" else "R2"}@$time")
                release(if (res.firedBy(first)) res.valueOf(first) else res.valueOf(second))
            }
            at(6.0) { after = "r3 busy=${r3.numBusy} r3 waiting=${r3.waitingQ.size} r2 waiting=${r2.waitingQ.size}" }
        }
        assertEquals(listOf("W R@5.0"), shop.log)
        assertEquals("r3 busy=0 r3 waiting=0 r2 waiting=0", after)
    }

    @Test
    @DisplayName("another seize trigger's request moved while the win is undecided, then the promise broken: the reopened wait takes the new resource's free unit")
    fun heldRequestMovedThenPromiseBroken() {
        val shop = run {
            lateinit var w: Shop.Actor
            holder {
                w.moveRequest(r2, r3)
                r.fail()
            }
            actor("K") {
                val a = seize(r2)
                delay(20.0)
                release(a)
            }
            w = actor("W") {
                delay(1.0)
                val first = r.seizeTrigger()
                val second = r2.seizeTrigger()
                val res = waitForFirst(first, second)
                val a = if (res.firedBy(first)) res.valueOf(first) else res.valueOf(second)
                log.add("W ${a.resource.name}@$time")
                release(a)
            }
        }
        assertEquals(listOf("W R3@5.0"), shop.log)
    }

    @Test
    @DisplayName("a broken promise with a message sent while the win is undecided and no other receiver: the reopened wait takes it at once")
    fun messageDuringTheHoldIsTakenOnReopening() {
        val shop = run {
            holder {
                message("m1")
                r.fail()
            }
            actor("W") {
                delay(1.0)
                val m = hub.mailbox.trigger()
                val res = waitForFirst(r.seizeTrigger(), m)
                log.add("W ${if (res.firedBy(m)) "message" else "other"}@$time mailbox=${hub.mailbox.size}")
            }
        }
        assertEquals(listOf("W message@5.0 mailbox=0"), shop.log)
    }

    @Test
    @DisplayName("a broken promise in a timed wait: it times out at its original deadline")
    fun timedWaitKeepsItsDeadline() {
        val shop = run {
            holder { r.fail() }
            actor("W") {
                delay(1.0)
                val res = waitForFirst(r.seizeTrigger(), signal.trigger(), timeout = 10.0)
                log.add("W timedOut=${res.timedOut}@$time waited=${res.timeWaited}")
            }
        }
        assertEquals(listOf("W timedOut=true@11.0 waited=10.0"), shop.log)
    }

    @Test
    @DisplayName("terminated while the win is undecided: every source is left clean and nothing is recorded")
    fun terminatedDuringTheHold() {
        var after = ""
        val shop = run {
            lateinit var w: Shop.Actor
            holder { w.terminateProcess() }
            w = actor("W") {
                delay(1.0)
                waitForFirst(r.seizeTrigger(), signal.trigger(), q.itemsTrigger(1), hub.mailbox.trigger())
                log.add("W resumed@$time")
            }
            actor("V") {
                delay(2.0)
                val a = seize(r)
                log.add("V R@$time")
                release(a)
            }
            at(10.0) {
                after = "inSignal=${signal.waitingQ.size} signalWaits=${signal.waitingQ.timeInQ.withinReplicationStatistic.count} " +
                        "requests=${q.requestQ.size} itemWaits=${q.requestQ.timeInQ.withinReplicationStatistic.count}"
            }
        }
        assertEquals(listOf("V R@5.0"), shop.log)
        assertEquals("inSignal=0 signalWaits=0.0 requests=0 itemWaits=0.0", after)
    }

    @Test
    @DisplayName("the promise kept: the other sources end up as they always have, and a signalFirst in the same step goes to the next entity")
    fun promiseKeptLeavesSourcesAsBefore() {
        var stats = ""
        val shop = run {
            holder { signal.signalFirst() }
            actor("W") {
                delay(1.0)
                val s = signal.trigger()
                val seizeT = r.seizeTrigger()
                val res = waitForFirst(seizeT, s, q.itemsTrigger(1))
                log.add("W ${if (res.firedBy(s)) "signal" else "other"}@$time")
                if (res.firedBy(seizeT)) release(res.valueOf(seizeT))
            }
            actor("Y") {
                delay(2.0)
                waitFor(signal)
                log.add("Y signal@$time")
            }
            at(8.0) {
                val st = signal.waitingQ.timeInQ.withinReplicationStatistic
                val it = q.requestQ.timeInQ.withinReplicationStatistic
                stats = "signal count=${st.count} sum=${st.weightedSum} items count=${it.count} sum=${it.weightedSum} requests=${q.requestQ.size}"
            }
        }
        assertEquals(listOf("W other@5.0", "Y signal@5.0"), shop.log)
        assertEquals("signal count=2.0 sum=7.0 items count=1.0 sum=4.0 requests=0", stats)
    }
}
