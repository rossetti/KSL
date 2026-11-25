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
package ksl.modeling.agent

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.RequestSelectionRuleIfc
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A queued request whose entity is running, as while the entity's wait for the first of several things is
 *  still registering, cannot be removed and terminated: the call throws before anything changes.
 */
class RemoveAndTerminateRunningEntityTest {

    /**
     *  A bed held from 0, its queue with a selection rule, and a message already in the mailbox. At 0.5 the
     *  receiver waits for a bed or a message: its bed request is queued, and the message wins at once. Taking the
     *  message tells the mailbox's observer, which tries to remove and terminate the receiver's bed request (or,
     *  with [items], its request at a bin, where an earlier receiver waits for two items beside the one there).
     *  The holder releases at 2 and a later seize takes the bed then.
     */
    private class Ward(parent: ModelElement, val items: Boolean) : AgentModel(parent) {
        val bedQ = RequestQ(this, "BedQ").also { q ->
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                var left = resource.numAvailableUnits
                requestQ.filter { r -> (r.amountRequested <= left).also { if (it) left -= r.amountRequested } }
            }
        }
        val bed = ResourceWithQ(this, "Bed", capacity = 1, queue = bedQ)
        val bin = BlockingQueue<Thing>(this, name = "Bin")
        val owner = PermanentAgent("Owner")
        val events = mutableListOf<String>()
        var refusal: Throwable? = null
        var stillQueued = false
        lateinit var receiver: Receiver

        inner class Thing : QObject()

        init {
            owner.mailbox.attachObserver(object : MailboxObserver<AgentMessage> {
                override fun onMessageConsumed(message: AgentMessage, currentSize: Int) {
                    try {
                        if (items) {
                            val request = bin.requestQ.immutableList.first { it.receiver === receiver }
                            bin.removeAndTerminateChannelRequest(request)
                        } else {
                            val request = bedQ.first { it.entity === receiver }
                            bedQ.removeAndTerminate(request)
                        }
                    } catch (e: IllegalStateException) {
                        refusal = e
                        stillQueued = if (items) bin.requestQ.immutableList.any { it.receiver === receiver } else bedQ.any { it.entity === receiver }
                    }
                }
            })
        }

        inner class Holder : Entity() {
            val script = process {
                val a = seize(bed)
                delay(2.0)
                release(a)
            }
        }

        // waits, at the head of the bin's queue, for two items when only one is there: the receiver's items
        // trigger is then decided only once queued, and the bin's selector, serving the head, serves no one
        inner class EarlyReceiver : Entity() {
            val script = process {
                waitForFirst(bin.itemsTrigger(2), timeout = 1.0)
            }
        }

        inner class Sender : Entity() {
            val script = process {
                send(Thing(), bin)
            }
        }

        inner class Receiver : Entity() {
            val script = process {
                val first = if (items) bin.itemsTrigger(1) else bed.seizeTrigger()
                val messageTrigger = owner.mailbox.trigger()
                val result = waitForFirst(first, messageTrigger, timeout = 5.0)
                events.add("won:${if (result.trigger === messageTrigger) "message" else "other"}@$time")
            }
        }

        inner class Later : Entity() {
            val script = process {
                val a = seize(bed)
                events.add("later got the bed@$time")
                release(a)
            }
        }

        override fun initialize() {
            super.initialize()
            owner.mailbox.deliver(AgentMessage.Inform(owner, "ready"))
            receiver = Receiver()
            activate(Holder().script)
            if (items) {
                activate(Sender().script, 0.1)
                activate(EarlyReceiver().script, 0.2)
            }
            activate(receiver.script, 0.5)
            activate(Later().script, 1.0)
        }
    }

    private fun run(items: Boolean): Ward {
        val model = Model("RemoveRunning$items${System.nanoTime()}", autoCSVReports = false)
        val ward = Ward(model, items)
        model.lengthOfReplication = 10.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return ward
    }

    @Test
    @DisplayName("removeAndTerminate of a request whose entity is running throws before removing it")
    fun requestQRefusesBeforeRemoving() {
        val ward = run(items = false)
        assertTrue(ward.refusal != null, "the call was not refused")
        assertTrue(ward.stillQueued, "the request was removed before the refusal: ${ward.refusal}")
        assertEquals(listOf("won:message@0.5", "later got the bed@2.0"), ward.events)
    }

    @Test
    @DisplayName("removeAndTerminateChannelRequest of a request whose receiver is running throws before removing it")
    fun channelRequestRefusesBeforeRemoving() {
        val ward = run(items = true)
        assertTrue(ward.refusal != null, "the call was not refused")
        assertTrue(ward.stillQueued, "the request was removed before the refusal: ${ward.refusal}")
        assertEquals(listOf("won:message@0.5", "later got the bed@2.0"), ward.events)
    }
}
