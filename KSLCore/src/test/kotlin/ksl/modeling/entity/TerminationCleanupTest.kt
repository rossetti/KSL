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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A suspension registers the entity somewhere so that something can later wake it — a request in a
 *  request queue, a request in a blocking queue, an entry in a blockage, a back-reference in a
 *  suspension. The matching deregistration sits in the process code immediately *after* `suspend()`.
 *  Terminating the process unwinds the coroutine through that suspension point, so the
 *  deregistration never runs and the registration outlives the process that made it.
 *
 *  Every test here terminates a process through the public `Entity.terminateProcess()` and then does
 *  the ordinary thing that would act on the leaked registration. Before this cleanup existed each
 *  one aborted the run with the same message — *Tried to resume process … from an illegal state:
 *  Terminated* — one symptom with four distinct causes, which is what made it hard to chase.
 *
 *  The supported reneging paths (`RequestQ.removeAndTerminate` and its siblings on the other queue
 *  types) never hit this, because they remove the registration *before* terminating. These tests
 *  deliberately use the bare `terminateProcess()` instead.
 */
class TerminationCleanupTest {

    private fun <T : ProcessModel> simulate(name: String, length: Double, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        m.simulate()
        return p
    }

    // ── seize: a Request left in a RequestQ ─────────────────────────────────

    /**
     *  One unit of capacity. The holder keeps it until 20. A doomed entity queues behind it and is
     *  terminated at 5; a later entity queues at 10 and must be served when the holder releases.
     */
    private class SeizeLeakModel(parent: ModelElement) : ProcessModel(parent, null) {
        val q = RequestQ(this, "Q")
        val res = ResourceWithQ(this, name = "R", capacity = 1, queue = q)

        var laterEntityWasServed = false
        lateinit var doomed: Waiter

        inner class Holder : Entity() {
            val work = process("holder") {
                val a = seize(res, 1)
                delay(20.0)
                release(a)
            }
        }

        inner class Waiter : Entity() {
            val work = process("waiter") {
                val a = seize(res, 1)
                laterEntityWasServed = true
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            laterEntityWasServed = false
            activate(Holder().work)
            doomed = Waiter()
            activate(doomed.work, 1.0)
            activate(Waiter().work, 10.0)
            schedule(::terminateDoomed, 5.0)
        }

        private fun terminateDoomed(event: KSLEvent<Nothing>) {
            doomed.terminateProcess()
        }
    }

    @Test
    @DisplayName("Termination removes the entity's seize request from the request queue")
    fun terminatedSeizeReleasesItsRequest() {
        val m = simulate("terminatedSeizeRequest", 100.0) { SeizeLeakModel(it) }
        assertTrue(m.laterEntityWasServed, "the resource must still be usable after a waiter is terminated")
        assertEquals(0, m.q.size, "the terminated entity's request must not be left in the queue")
    }

    /**
     *  Capacity two, two doomed waiters. Each leaked request would permanently subtract its
     *  requested amount from `RequestQ.effectiveAvailableFor`, so a later two-unit seize could never
     *  be satisfied even though both units are free.
     */
    private class SeizeCapacityLeakModel(parent: ModelElement) : ProcessModel(parent, null) {
        val q = RequestQ(this, "Q")
        val res = ResourceWithQ(this, name = "R", capacity = 2, queue = q)

        var bigSeizeSucceeded = false
        val doomed = mutableListOf<Waiter>()

        inner class Holder : Entity() {
            val work = process("holder") {
                val a = seize(res, 2)
                delay(20.0)
                release(a)
            }
        }

        inner class Waiter : Entity() {
            val work = process("waiter") {
                val a = seize(res, 1)
                delay(1.0)
                release(a)
            }
        }

        inner class BigUser : Entity() {
            val work = process("bigUser") {
                val a = seize(res, 2)
                bigSeizeSucceeded = true
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            bigSeizeSucceeded = false
            doomed.clear()
            activate(Holder().work)
            repeat(2) {
                val w = Waiter()
                doomed.add(w)
                activate(w.work, 1.0)
            }
            activate(BigUser().work, 25.0)
            schedule(::terminateDoomed, 5.0)
        }

        private fun terminateDoomed(event: KSLEvent<Nothing>) {
            for (w in doomed) w.terminateProcess()
        }
    }

    @Test
    @DisplayName("Terminated seize requests do not permanently withhold capacity from the queue")
    fun terminatedSeizeDoesNotLeakCapacity() {
        val m = simulate("terminatedSeizeCapacity", 100.0) { SeizeCapacityLeakModel(it) }
        assertEquals(0, m.q.size, "no terminated request may be left behind")
        assertTrue(
            m.bigSeizeSucceeded,
            "a later full-capacity seize must succeed; a leaked resume-pending request would starve it forever",
        )
    }

    // ── waitForItems: a ChannelRequest left in a BlockingQueue ──────────────

    /**
     *  Two receivers wait on an empty channel; the first is terminated. Requests are scanned in
     *  queue order, so a leaked request registered first is offered the item ahead of the live
     *  receiver — the item is consumed by, or offered to, a process that can never take it.
     */
    private class ItemRequestLeakModel(parent: ModelElement) : ProcessModel(parent, null) {
        val bq = BlockingQueue<ModelElement.QObject>(this, name = "BQ")

        var liveReceiverGotItem = false
        lateinit var doomed: Receiver

        inner class Receiver(private val isLive: Boolean) : Entity() {
            val work = process("rx") {
                waitForItems(bq, 1)
                if (isLive) liveReceiverGotItem = true
            }
        }

        inner class Sender : Entity() {
            val work = process("tx") {
                delay(10.0)
                send(QObject("item"), bq)
            }
        }

        override fun initialize() {
            liveReceiverGotItem = false
            doomed = Receiver(isLive = false)
            activate(doomed.work)
            activate(Receiver(isLive = true).work, 0.5)
            activate(Sender().work)
            schedule(::terminateDoomed, 5.0)
        }

        private fun terminateDoomed(event: KSLEvent<Nothing>) {
            doomed.terminateProcess()
        }
    }

    @Test
    @DisplayName("Termination removes the entity's item request from the blocking queue")
    fun terminatedWaitForItemsReleasesItsRequest() {
        val m = simulate("terminatedItemRequest", 100.0) { ItemRequestLeakModel(it) }
        assertTrue(
            m.liveReceiverGotItem,
            "the item must reach the live receiver, not be offered to the terminated one ahead of it",
        )
        assertEquals(0, m.bq.requestQ.size, "both requests are gone: one deregistered at termination, one filled")
        assertEquals(0, m.bq.channelQ.size, "the item must have been taken from the channel")
    }

    // ── waitFor(blockage): an entry left in Blockage.myBlockedEntities ──────

    private class BlockageLeakModel(parent: ModelElement) : ProcessModel(parent, null) {
        lateinit var blocker: Blocker
        lateinit var doomed: Blocked

        var liveWaiterResumed = false

        inner class Blocker : Entity() {
            val blk = Blockage("B")
            val work = process("blocker") {
                startBlockage(blk)
                delay(10.0)
                clearBlockage(blk)
            }
        }

        inner class Blocked(private val isLive: Boolean) : Entity() {
            val work = process("blocked") {
                waitFor(blocker.blk)
                if (isLive) liveWaiterResumed = true
            }
        }

        override fun initialize() {
            liveWaiterResumed = false
            blocker = Blocker()
            activate(blocker.work)
            doomed = Blocked(isLive = false)
            activate(doomed.work, 1.0)
            activate(Blocked(isLive = true).work, 1.0)
            schedule(::terminateDoomed, 5.0)
        }

        private fun terminateDoomed(event: KSLEvent<Nothing>) {
            doomed.terminateProcess()
        }
    }

    @Test
    @DisplayName("Termination removes the entity from the blockage it was waiting on")
    fun terminatedBlockageWaiterIsDeregistered() {
        val m = simulate("terminatedBlockageWaiter", 100.0) { BlockageLeakModel(it) }
        assertTrue(m.liveWaiterResumed, "the surviving waiter must still be resumed when the blockage clears")
        assertFalse(
            m.blocker.blk.hasBlockedEntities,
            "the terminated entity must not remain registered on the blockage",
        )
    }

    // ── suspendFor: a back-reference left on a Suspension ───────────────────

    /**
     *  The doomed entity is terminated while suspended on its `Suspension`. Resuming that suspension
     *  afterwards must not reach the dead process, and must not be an error either — whoever holds
     *  the suspension cannot be expected to know the waiting process was terminated. It is dropped
     *  and logged.
     *
     *  The suspension is *abandoned*, not *resumed*, which is what keeps it usable by a later
     *  process. Actually re-running a process on the terminated entity is not possible until the
     *  entity is released from its dead process, so the reuse itself is asserted in the H-15 tests
     *  rather than here; what is pinned here is the state that makes reuse legal.
     */
    private class SuspensionLeakModel(parent: ModelElement) : ProcessModel(parent, null) {
        lateinit var doomed: Sleeper

        var wakerFinished = false

        inner class Sleeper : Entity() {
            val susp = Suspension("S")
            val work = process("sleeper") {
                suspendFor(susp)
            }
        }

        inner class Waker : Entity() {
            val work = process("waker") {
                delay(10.0)
                resume(doomed.susp)
                wakerFinished = true
            }
        }

        override fun initialize() {
            wakerFinished = false
            doomed = Sleeper()
            activate(doomed.work)
            activate(Waker().work)
            schedule(::terminateDoomed, 5.0)
        }

        private fun terminateDoomed(event: KSLEvent<Nothing>) {
            doomed.terminateProcess()
        }
    }

    @Test
    @DisplayName("Termination abandons the suspension without marking it resumed")
    fun terminatedSuspendForAbandonsItsSuspension() {
        val m = simulate("terminatedSuspension", 100.0) { SuspensionLeakModel(it) }
        assertTrue(m.wakerFinished, "the run must complete; resuming an abandoned suspension is not an error")
        assertTrue(m.doomed.susp.isAbandoned, "the suspension must record that its waiter's process ended")
        assertFalse(
            m.doomed.susp.isResumed,
            "a wait ended by termination is not a completed one, so the suspension stays reusable (gate E11)",
        )
        assertFalse(m.doomed.susp.isSuspended, "nothing is waiting on the suspension any more")
    }

    /**
     *  The guard against over-tightening option (b): dropping a resume is for *abandoned*
     *  suspensions only. A suspension that was never passed through `suspendFor` has the same
     *  null back-reference, and resuming it is still a modelling error that must be reported.
     */
    private class UnusedSuspensionModel(parent: ModelElement) : ProcessModel(parent, null) {
        var failure: String? = null

        inner class Actor : Entity() {
            val neverUsed = Suspension("NeverUsed")
            val work = process("actor") {
                delay(1.0)
                try {
                    resume(neverUsed)
                } catch (e: IllegalArgumentException) {
                    failure = e.message
                }
            }
        }

        override fun initialize() {
            failure = null
            activate(Actor().work)
        }
    }

    @Test
    @DisplayName("Resuming a suspension that was never used is still an error")
    fun resumingAnUnusedSuspensionStillFails() {
        val m = simulate("unusedSuspension", 100.0) { UnusedSuspensionModel(it) }
        val msg = m.failure
        assertTrue(
            msg != null && msg.contains("not associated with a suspended entity"),
            "an unused suspension must still report the error; only abandoned ones are dropped, but was: $msg",
        )
    }

    /**
     *  The normal path must not be mistaken for abandonment. `Suspension.resume()` nulls the back
     *  reference when it schedules the resume, so the `finally` in `suspendFor` sees a null and must
     *  leave `isAbandoned` false — otherwise every ordinary use of a suspension would be flagged.
     */
    private class NormalSuspensionModel(parent: ModelElement) : ProcessModel(parent, null) {
        lateinit var sleeper: Sleeper

        inner class Sleeper : Entity() {
            val susp = Suspension("S")
            val work = process("sleeper") {
                suspendFor(susp)
            }
        }

        inner class Waker : Entity() {
            val work = process("waker") {
                delay(5.0)
                resume(sleeper.susp)
            }
        }

        override fun initialize() {
            sleeper = Sleeper()
            activate(sleeper.work)
            activate(Waker().work)
        }
    }

    @Test
    @DisplayName("A normally resumed suspension is not marked abandoned")
    fun normalResumeDoesNotMarkAbandoned() {
        val m = simulate("normalSuspension", 100.0) { NormalSuspensionModel(it) }
        assertTrue(m.sleeper.susp.isResumed, "the normal path must still mark the suspension resumed")
        assertFalse(m.sleeper.susp.isAbandoned, "the finally must not mistake a completed wait for an abandoned one")
    }
}
