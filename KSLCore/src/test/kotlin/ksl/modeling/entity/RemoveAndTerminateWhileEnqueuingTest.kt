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

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueListenerIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  An entity is placed in a hold queue, or a blocking queue's sender queue, while its process still runs, and the
 *  queue's listeners are told then. Removing and terminating it from there is refused before anything changes, so
 *  the entity stays in the queue and goes on as if nothing had been asked.
 */
class RemoveAndTerminateWhileEnqueuingTest {

    private class Dock(parent: ModelElement, val senders: Boolean) : ProcessModel(parent, "Dock") {
        val holdQ = HoldQueue(this, "HoldQ")
        val bin = BlockingQueue<Box>(this, capacity = 1, name = "Bin")
        val events = mutableListOf<String>()
        var refusal: Throwable? = null
        var stillQueued = false
        lateinit var waiter: Waiter

        inner class Box : QObject()

        init {
            if (senders) {
                bin.senderQ.addQueueListener(object : QueueListenerIfc<Entity> {
                    override fun update(status: Queue.Status, queue: Queue<Entity>, qObject: Entity?) {
                        if (status != Queue.Status.ENQUEUED || qObject !== waiter) return
                        try {
                            bin.removeAndTerminateWaitingSenders(waiter)
                        } catch (e: IllegalStateException) {
                            refusal = e
                            stillQueued = bin.senderQ.contains(waiter)
                        }
                    }
                })
            } else {
                holdQ.addQueueListener(object : QueueListenerIfc<Entity> {
                    override fun update(status: Queue.Status, queue: Queue<Entity>, qObject: Entity?) {
                        if (status != Queue.Status.ENQUEUED || qObject !== waiter) return
                        try {
                            holdQ.removeAndTerminate(waiter)
                        } catch (e: IllegalStateException) {
                            refusal = e
                            stillQueued = holdQ.contains(waiter)
                        }
                    }
                })
            }
        }

        // fills the bin, so the waiter's send blocks
        inner class Filler : Entity() {
            val p = process { send(Box(), bin) }
        }

        inner class Waiter : Entity() {
            val p = process {
                if (senders) send(Box(), bin) else hold(holdQ)
                events.add("went on@$time")
            }
        }

        inner class Taker : Entity() {
            val p = process {
                val got = waitForItems(bin, 1)
                events.add("took ${got.size}@$time")
            }
        }

        override fun initialize() {
            waiter = Waiter()
            if (senders) {
                activate(Filler().p)
                activate(waiter.p, 0.5)
                activate(Taker().p, 1.0)
            } else {
                activate(waiter.p, 0.5)
                schedule(::letGo, 1.0)
            }
        }

        private fun letGo(e: KSLEvent<Nothing>) {
            if (holdQ.contains(waiter)) holdQ.removeAndResume(waiter)
        }
    }

    private fun run(senders: Boolean): Dock {
        val model = Model("Enqueuing$senders${System.nanoTime()}", autoCSVReports = false)
        val dock = Dock(model, senders)
        model.lengthOfReplication = 10.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return dock
    }

    @Test
    @DisplayName("HoldQueue.removeAndTerminate of an entity whose process is running throws before removing it")
    fun holdQueueRefusesBeforeRemoving() {
        val dock = run(senders = false)
        assertTrue(dock.refusal != null, "the call was not refused")
        assertTrue(dock.stillQueued, "the entity was removed before the refusal: ${dock.refusal}")
        assertEquals(listOf("went on@1.0"), dock.events)
    }

    @Test
    @DisplayName("removeAndTerminateWaitingSenders of a sender whose process is running throws before removing it")
    fun senderQueueRefusesBeforeRemoving() {
        val dock = run(senders = true)
        assertTrue(dock.refusal != null, "the call was not refused")
        assertTrue(dock.stillQueued, "the sender was removed before the refusal: ${dock.refusal}")
        assertEquals(listOf("took 1@1.0", "went on@1.0"), dock.events.sorted())
    }
}
