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

import ksl.modeling.agent.AgentSignal
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueListenerIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A trigger places its waiter where listeners of the model's own can run: an items trigger's request in a blocking
 *  queue's request queue, a signal trigger's entity in the signal's hold queue. A listener that ends the wait then,
 *  through another trigger or through the trigger's own source, finds the waiter already known to its wait.
 */
class WaitEndedWhilePlacedTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val plans = mutableListOf<Shop.() -> Unit>()
        val log = mutableListOf<String>()

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = KSLEvent.DEFAULT_PRIORITY)
        }

        override fun initialize() {
            log.clear()
            for (p in plans) p()
        }
    }

    /** [build] makes the model's elements before the run, and returns what each replication starts. */
    private fun run(build: Shop.() -> (Shop.() -> Unit)): Shop {
        val model = Model("Placed${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.plans.add(shop.build())
        model.lengthOfReplication = 20.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return shop
    }

    private fun <T : ModelElement.QObject> onEnqueue(queue: ksl.modeling.queue.QueueCIfc<T>, action: () -> Unit) {
        queue.addQueueListener(object : QueueListenerIfc<T> {
            override fun update(status: Queue.Status, queue: Queue<T>, qObject: T?) {
                if (status == Queue.Status.ENQUEUED) action()
            }
        })
    }

    @Test
    @DisplayName("an items trigger's request: a listener that fires another trigger of the wait as it is queued ends the wait")
    fun itemsRequestLosesAsItIsQueued() {
        var parts: BlockingQueue<ModelElement.QObject>? = null
        val shop = run {
            val bq = BlockingQueue<ModelElement.QObject>(this, name = "Parts").also { parts = it }
            val alarm = AgentSignal("Alarm")
            onEnqueue(bq.requestQ) { alarm.fire() }
            return@run {
            actor("W") {
                delay(1.0)
                val notice = alarm.trigger()
                val result = waitForFirst(notice, bq.itemsTrigger())
                log.add("${if (result.firedBy(notice)) "alarm" else "items"}@$time")
            }
            }
        }
        assertEquals(listOf("alarm@1.0"), shop.log)
        assertEquals(0, parts!!.requestQ.size, "the items request left with the wait")
    }

    @Test
    @DisplayName("an items trigger's request: a listener that sends the item it waits for as it is queued fills it")
    fun itemsRequestFilledAsItIsQueued() {
        val shop = run {
            val bq = BlockingQueue<ModelElement.QObject>(this, name = "Parts")
            val alarm = AgentSignal("Alarm")
            var sent = false
            onEnqueue(bq.requestQ) { if (!sent) { sent = true; bq.sendToChannel(QObject()) } }
            return@run {
            sent = false
            actor("W") {
                delay(1.0)
                val items = bq.itemsTrigger()
                val result = waitForFirst(alarm.trigger(), items)
                log.add("${if (result.firedBy(items)) "items" else "alarm"}@$time")
            }
            }
        }
        assertEquals(listOf("items@1.0"), shop.log)
    }

    @Test
    @DisplayName("a signal trigger's entity: a listener that fires another trigger of the wait as it is held ends the wait, and leaves no hold behind")
    fun signalHoldLosesAsItIsHeld() {
        val shop = run {
            val signal = Signal(this, "Go")
            val alarm = AgentSignal("Alarm")
            var armed = true
            onEnqueue(signal.waitingQ) { if (armed) { armed = false; alarm.fire() } }
            return@run {
            armed = true
            actor("W") {
                delay(1.0)
                val notice = alarm.trigger()
                val first = waitForFirst(notice, signal.trigger())
                log.add("${if (first.firedBy(notice)) "alarm" else "signal"}@$time")
                // a later wait on the same signal is an ordinary one
                val go = signal.trigger()
                val second = waitForFirst(go, timeout = 10.0)
                log.add("${if (second.firedBy(go)) "signal" else "timed out"}@$time")
            }
            at(5.0) { signal.signalAll() }
            }
        }
        assertEquals(listOf("alarm@1.0", "signal@5.0"), shop.log)
    }

    @Test
    @DisplayName("a signal trigger's entity: a listener that signals that signal as it is held ends the wait through it")
    fun signalHoldSignalledAsItIsHeld() {
        val shop = run {
            val signal = Signal(this, "Go")
            val alarm = AgentSignal("Alarm")
            var armed = true
            onEnqueue(signal.waitingQ) { if (armed) { armed = false; signal.signalAll() } }
            return@run {
            armed = true
            actor("W") {
                delay(1.0)
                val go = signal.trigger()
                val result = waitForFirst(alarm.trigger(), go)
                log.add("${if (result.firedBy(go)) "signal" else "alarm"}@$time")
            }
            }
        }
        assertEquals(listOf("signal@1.0"), shop.log)
    }
}
