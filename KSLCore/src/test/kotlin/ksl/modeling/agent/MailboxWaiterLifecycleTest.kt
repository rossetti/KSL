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

import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase B1 — lifecycle of a `receiveMessage` waiter.
 *
 *  A process suspended in `receiveMessage` registers a waiter on the mailbox.
 *  `deliver` removes that waiter when it hands over a message, but that only covers
 *  the *normal* exit. Terminating the process resumes its continuation with a
 *  `ProcessTerminatedException`, which unwinds through the suspension point — and
 *  before the fix, left the waiter registered.
 *
 *  The consequences were both real, and reachable from the public
 *  `Entity.terminateProcess()`:
 *
 *   1. **Silent message loss.** `deliver` selects a matching waiter *ahead of* the
 *      pending queue and of any live receiver, so the next matching message was
 *      handed to the dead waiter and never seen again.
 *   2. **An aborted simulation.** Handing it over then resumed a terminated process,
 *      throwing `IllegalStateException: Tried to resume process ... from an illegal
 *      state: Terminated` out of `simulate()`.
 *
 *  `AgentMailbox.removeWaiter` existed for exactly this — its KDoc names the
 *  "cancellation or completion path" — but had no caller anywhere in the repository.
 *  `receiveMessage` now calls it from a `finally`, so every exit path deregisters.
 */
class MailboxWaiterLifecycleTest {

    /**
     *  A hub owning the mailbox, plus receivers that wait on it. Waiting on another
     *  agent's mailbox is legal and is what makes the "live receiver" case testable:
     *  `deliver` scans waiters in registration order, so a leaked waiter registered
     *  first would win over a live one.
     */
    private class HubModel(
        parent: ModelElement,
        val terminateAt: Double?,
        val deliverAt: Double,
        val liveReceiver: Boolean,
    ) : AgentModel(parent, "hub") {

        val received = mutableListOf<String>()

        inner class Hub(aName: String) : Agent(aName)

        val hub = Hub("hub")

        inner class Receiver(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val msg = receiveMessage(hub.mailbox)
                received.add("${this@Receiver.name}:${(msg as AgentMessage.Inform<*>).payload}")
            }
        }

        /** Pending count observed just after delivery. */
        var sizeAfterDelivery: Int = -1

        /**
         *  Receivers are built fresh each replication rather than held as fields. A
         *  setup-time agent whose process is terminated cannot be re-activated next
         *  replication — the entity still reports that terminated process as current —
         *  and that belongs to `ProcessModel.Entity`, not to this test.
         */
        override fun initialize() {
            super.initialize()
            val doomed = Receiver("doomed")
            activate(doomed.script)
            if (liveReceiver) activate(Receiver("live").script)
            terminateAt?.let { t ->
                schedule(EventActionIfc<Nothing> { doomed.terminateProcess() }, t)
            }
            schedule(EventActionIfc<Nothing> {
                hub.mailbox.deliver(AgentMessage.Inform(hub, "payload"))
                sizeAfterDelivery = hub.mailbox.size
            }, deliverAt)
        }
    }

    private fun run(model: HubModel, replications: Int = 1, length: Double = 10.0) {
        model.model.numberOfReplications = replications
        model.model.lengthOfReplication = length
        model.model.simulate()
    }

    // ── The defect ───────────────────────────────────────────────────────────

    /**
     *  With the sole receiver terminated, the message has nowhere to go and must
     *  queue. Before the fix it was consumed by the leaked waiter (`size == 0`) and
     *  the resume attempt aborted the run.
     */
    @Test
    @DisplayName("B1: a terminated receiver's waiter no longer swallows the next message")
    fun terminatedReceiverDoesNotConsumeMessage() {
        val m = HubModel(Model("waiterLeak"), terminateAt = 1.0, deliverAt = 2.0, liveReceiver = false)
        run(m)
        assertEquals(
            1, m.sizeAfterDelivery,
            "message should queue in pending, not be handed to a terminated receiver",
        )
        assertTrue(m.received.isEmpty(), "no receiver was alive to take it")
    }

    /**
     *  The stronger case: a live receiver registered *after* the doomed one. Waiters
     *  are scanned in registration order, so before the fix the leaked waiter matched
     *  first and the live receiver starved.
     */
    @Test
    @DisplayName("B1: a live receiver still gets the message after a peer is terminated")
    fun liveReceiverStillReceivesAfterPeerTerminated() {
        val m = HubModel(Model("waiterLive"), terminateAt = 1.0, deliverAt = 2.0, liveReceiver = true)
        run(m)
        assertEquals(listOf("live:payload"), m.received, "the live receiver should be resumed")
        assertEquals(0, m.sizeAfterDelivery, "the message was consumed by a live receiver")
    }

    /** Terminating a suspended receiver must not abort the replication. */
    @Test
    @DisplayName("B1: terminating a suspended receiver does not abort the simulation")
    fun terminationDoesNotAbortTheRun() {
        val m = HubModel(Model("waiterNoThrow"), terminateAt = 1.0, deliverAt = 2.0, liveReceiver = false)
        run(m)
        assertEquals(10.0, m.model.lengthOfReplication, 1e-9)
        // Reaching here at all is the assertion: before the fix, simulate() threw
        // IllegalStateException from resuming a terminated process.
    }

    // ── No regression on the normal path ─────────────────────────────────────

    /**
     *  `deliver` already removes a waiter when it hands over a message, so the new
     *  `finally` runs against an empty slot. Removal must stay idempotent, and normal
     *  delivery must be untouched.
     */
    @Test
    @DisplayName("B1: normal delivery still resumes the receiver exactly once")
    fun normalDeliveryIsUnaffected() {
        val m = HubModel(Model("waiterNormal"), terminateAt = null, deliverAt = 2.0, liveReceiver = false)
        run(m)
        assertEquals(listOf("doomed:payload"), m.received)
        assertEquals(0, m.sizeAfterDelivery, "a live waiter takes the message directly")
    }

    /**
     *  `AgentMailbox.reset()` clears waiters at the start of each replication, which
     *  is what bounded the leak to a single replication. That containment must
     *  survive the fix: replication 2 behaves exactly like replication 1.
     */
    @Test
    @DisplayName("B1: waiter cleanup composes with per-replication reset")
    fun cleanupComposesWithReplicationReset() {
        val m = HubModel(Model("waiterReset"), terminateAt = 1.0, deliverAt = 2.0, liveReceiver = true)
        run(m, replications = 3)
        // One live receiver takes one message per replication, and nothing carries over.
        assertEquals(
            listOf("live:payload", "live:payload", "live:payload"), m.received,
            "each replication should behave identically",
        )
    }
}
