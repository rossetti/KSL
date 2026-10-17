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
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Who gets a freed unit when requests wait for it by different routes, since R1.8: the request that entered
 *  its queue first, across every queue that may hold one, with each queue keeping its own discipline; and a
 *  woken request takes exactly what it was promised.
 */
class PoolWakeupOrderTest {

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name, autoCSVReports = false)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        val error = runCatching { m.simulate() }.exceptionOrNull()
        assertNull(error, "the run must not fail: ${error?.message}")
        return p
    }

    /** W is held directly until 50. A pool waiter arrives at 35 and a direct waiter at 40. */
    private class EarliestAcrossQueues(parent: ModelElement, rule: RequestQueueNotificationRuleIfc?) :
        ProcessModel(parent, null) {
        val w = ResourceWithQ(this, name = "W")
        val pool = ResourcePoolWithQ(this, listOf(w), name = "P")
        val served = mutableMapOf<String, Double>()

        init {
            if (rule != null) w.initialRequestQueueNotificationRule = rule
        }

        inner class Holder : Entity() {
            val work = process { val a = seize(w); delay(50.0); release(a) }
        }

        inner class PoolWaiter : Entity() {
            val work = process { val a = seize(pool); served["pool"] = time; delay(1.0); release(a) }
        }

        inner class DirectWaiter : Entity() {
            val work = process { val a = seize(w); served["direct"] = time; delay(1.0); release(a) }
        }

        override fun initialize() {
            served.clear()
            activate(Holder().work)
            activate(PoolWaiter().work, 35.0)
            activate(DirectWaiter().work, 40.0)
        }
    }

    @Test
    @DisplayName("The request that has waited longest gets the unit, whichever route it asked by")
    fun earliestArrivalAcrossQueues() {
        val m = simulate("earliest") { EarliestAcrossQueues(it, null) }
        assertEquals(50.0, m.served["pool"]!!, 1e-9, "the pool waiter arrived first")
        assertEquals(51.0, m.served["direct"]!!, 1e-9)
    }

    @Test
    @DisplayName("RegistrationOrderNotificationRule restores the order before R1.8: the member's own queue first")
    fun registrationOrderRestoresTheOldOrder() {
        val m = simulate("registration") { EarliestAcrossQueues(it, RegistrationOrderNotificationRule) }
        assertEquals(50.0, m.served["direct"]!!, 1e-9, "the member's own queue was registered first")
        assertEquals(51.0, m.served["pool"]!!, 1e-9)
    }

    /** A ranked member queue: a low-priority waiter arrives first, a high-priority one after it. */
    private class RankedQueue(parent: ModelElement) : ProcessModel(parent, null) {
        private val q = RequestQ(this, "RankedQ", Queue.Discipline.RANKED)
        val w = ResourceWithQ(this, name = "W", queue = q)
        val pool = ResourcePoolWithQ(this, listOf(w), name = "P")
        val order = mutableListOf<String>()

        inner class Holder : Entity() {
            val work = process { val a = seize(pool); delay(10.0); release(a) }
        }

        inner class Waiter(private val label: String, rank: Int) : Entity() {
            init {
                priority = rank
            }

            val work = process { val a = seize(w); order += label; delay(1.0); release(a) }
        }

        override fun initialize() {
            order.clear()
            activate(Holder().work)
            activate(Waiter("low", 10).work, 1.0)
            activate(Waiter("high", 1).work, 2.0)
        }
    }

    @Test
    @DisplayName("A ranked queue still offers its highest-priority request first")
    fun rankedQueueKeepsItsDiscipline() {
        val m = simulate("ranked") { RankedQueue(it) }
        assertEquals(listOf("high", "low"), m.order)
    }

    /** A request for two units of a pool of two one-unit members, freed at 5 and at 6. */
    private class TwoUnits(parent: ModelElement) : ProcessModel(parent, null) {
        private val w1 = Resource(this, name = "W1")
        private val w2 = Resource(this, name = "W2")
        val pool = ResourcePoolWithQ(this, listOf(w1, w2), name = "P")
        var servedAt = Double.NaN
        var membersHeld = 0

        inner class Holder(private val until: Double) : Entity() {
            val work = process { val a = seize(pool); delay(until); release(a) }
        }

        inner class TwoUnitRequester : Entity() {
            val work = process {
                val a = seize(pool, amountNeeded = 2)
                servedAt = time
                membersHeld = a.allocations.size
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            servedAt = Double.NaN
            activate(Holder(5.0).work)
            activate(Holder(6.0).work)
            activate(TwoUnitRequester().work, 1.0)
        }
    }

    @Test
    @DisplayName("A request for two units waits for both, and takes one from each member")
    fun multiUnitPoolRequest() {
        val m = simulate("twoUnits") { TwoUnits(it) }
        assertEquals(6.0, m.servedAt, 1e-9)
        assertEquals(2, m.membersHeld)
    }

    /** Two members freed at 10; waiters by three routes. */
    private class SameInstant(parent: ModelElement) : ProcessModel(parent, null) {
        val w1 = ResourceWithQ(this, name = "W1")
        val w2 = ResourceWithQ(this, name = "W2")
        val pool = ResourcePoolWithQ(this, listOf(w1, w2), name = "P")
        val served = mutableMapOf<String, Double>()

        inner class Holder(private val r: ResourceWithQ) : Entity() {
            val work = process { val a = seize(r); delay(10.0); release(a) }
        }

        inner class Waiter(private val label: String, private val body: suspend KSLProcessBuilder.() -> Allocation) :
            Entity() {
            val work = process { val a = body(); served[label] = time; delay(5.0); release(a) }
        }

        inner class PoolWaiter : Entity() {
            val work = process { val a = seize(pool); served["pool"] = time; delay(5.0); release(a) }
        }

        override fun initialize() {
            served.clear()
            activate(Holder(w1).work)
            activate(Holder(w2).work)
            activate(PoolWaiter().work, 1.0)
            activate(Waiter("w1") { seize(w1) }.work, 2.0)
            activate(Waiter("w2") { seize(w2) }.work, 3.0)
        }
    }

    @Test
    @DisplayName("Two members freed at one instant, three waiters across three queues: two are served, then the third")
    fun sameInstantRelease() {
        val m = simulate("sameInstant") { SameInstant(it) }
        assertEquals(3, m.served.size, "everyone is eventually served")
        assertEquals(2, m.served.values.count { it == 10.0 }, "two at the instant the members are freed: ${m.served}")
        assertEquals(15.0, m.served.values.max(), 1e-9, "the third when one of them releases")
    }

    /**
     *  A woken waiter is terminated before it resumes, at the same instant. Its promised unit must go to the
     *  next waiter rather than sit free.
     */
    private class AbandonedPromise(parent: ModelElement) : ProcessModel(parent, null) {
        val w = ResourceWithQ(this, name = "W")
        var nextServedAt = Double.NaN
        private lateinit var first: Waiter

        inner class Holder : Entity() {
            val work = process {
                val a = seize(w)
                delay(10.0)
                release(a, releasePriority = KSLEvent.LOW_PRIORITY)
            }
        }

        inner class Waiter(private val record: Boolean) : Entity() {
            val work = process { val a = seize(w); if (record) nextServedAt = time; delay(1.0); release(a) }
        }

        inner class Killer : Entity() {
            val work = process { first.terminateProcess() }
        }

        override fun initialize() {
            nextServedAt = Double.NaN
            first = Waiter(false)
            activate(Holder().work)
            activate(first.work, 1.0)
            activate(Waiter(true).work, 2.0)
            // after the release at 10 (at the default priority), before the woken waiter resumes (at low priority)
            activate(Killer().work, 10.0, KSLEvent.MEDIUM_LOW_PRIORITY)
        }
    }

    @Test
    @DisplayName("A promise abandoned by termination is offered to the next waiter")
    fun abandonedPromiseIsOfferedOn() {
        val m = simulate("abandoned") { AbandonedPromise(it) }
        assertEquals(10.0, m.nextServedAt, 1e-9)
    }

    /**
     *  A waiter is woken for a unit at 10, and the unit's resource goes off shift in the same instant, before
     *  the waiter resumes. The waiter cannot take what it was promised; it waits again and is served when the
     *  resource comes back on shift at 20.
     */
    private class PromiseBroken(parent: ModelElement) : ProcessModel(parent, null) {
        val w = ResourceWithQ(this, name = "W")
        private val shift = ShiftControl(w)
        var servedAt = Double.NaN

        inner class Holder : Entity() {
            val work = process {
                val a = seize(w)
                delay(10.0)
                release(a, releasePriority = KSLEvent.LOW_PRIORITY)
            }
        }

        inner class Waiter : Entity() {
            val work = process { val a = seize(w); servedAt = time; delay(1.0); release(a) }
        }

        inner class Supervisor : Entity() {
            val work = process {
                shift.goOffShift()
                delay(10.0)
                shift.goOnShift()
            }
        }

        override fun initialize() {
            servedAt = Double.NaN
            shift.initialize()
            activate(Holder().work)
            activate(Waiter().work, 1.0)
            // after the release at 10, before the woken waiter resumes at low priority
            activate(Supervisor().work, 10.0, KSLEvent.MEDIUM_LOW_PRIORITY)
        }
    }

    @Test
    @DisplayName("A woken waiter whose promise cannot be kept waits again rather than failing")
    fun brokenPromiseWaitsAgain() {
        val m = simulate("promiseBroken") { PromiseBroken(it) }
        assertEquals(20.0, m.servedAt, 1e-9)
    }

    /**
     *  A plain resource seized through two queues, with no pool involved: A holds it from 0 to 10 through a
     *  separate queue, and B asks at 1 through the resource's own queue. Before R1.8 the release woke only A's
     *  queue, and B waited beside an idle resource for the rest of the run.
     */
    private class TwoQueues(parent: ModelElement) : ProcessModel(parent, null) {
        val r = ResourceWithQ(this, name = "R")
        private val otherQ = RequestQ(this, "OtherQ")
        var servedAt = Double.NaN

        inner class A : Entity() {
            val work = process { val a = seize(r, queue = otherQ); delay(10.0); release(a) }
        }

        inner class B : Entity() {
            val work = process { val a = seize(r); servedAt = time; delay(5.0); release(a) }
        }

        override fun initialize() {
            servedAt = Double.NaN
            activate(A().work)
            activate(B().work, 1.0)
        }
    }

    @Test
    @DisplayName("Releasing through one queue wakes a waiter in the resource's other queue")
    fun releaseWakesTheOtherQueue() {
        val m = simulate("twoQueues") { TwoQueues(it) }
        assertEquals(10.0, m.servedAt, 1e-9)
    }

    /**
     *  The mirror of [TwoQueues]: A holds R through R's own queue, and B waits in a separate queue that the
     *  model never registered on R. The seize registers it, so A's release reaches B.
     */
    private class WaiterInSeparateQueue(parent: ModelElement) : ProcessModel(parent, null) {
        val r = ResourceWithQ(this, name = "R")
        private val otherQ = RequestQ(this, "OtherQ")
        var servedAt = Double.NaN

        inner class A : Entity() {
            val work = process { val a = seize(r); delay(10.0); release(a) }
        }

        inner class B : Entity() {
            val work = process { val a = seize(r, queue = otherQ); servedAt = time; delay(5.0); release(a) }
        }

        override fun initialize() {
            servedAt = Double.NaN
            activate(A().work)
            activate(B().work, 1.0)
        }
    }

    @Test
    @DisplayName("A waiter in a separate queue is woken by a release through the resource's own queue")
    fun separateQueueWaiterIsWoken() {
        val m = simulate("separateQueue") { WaiterInSeparateQueue(it) }
        assertEquals(10.0, m.servedAt, 1e-9)
    }

    /** A pool seized through a separate queue, its only member held directly until 10. */
    private class PoolThroughSeparateQueue(parent: ModelElement) : ProcessModel(parent, null) {
        val w = ResourceWithQ(this, name = "W")
        val pool = ResourcePool(this, listOf(w), name = "P")
        private val poolQ = RequestQ(this, "PoolQ")
        var servedAt = Double.NaN

        inner class Holder : Entity() {
            val work = process { val a = seize(w); delay(10.0); release(a) }
        }

        inner class PoolWaiter : Entity() {
            val work = process { val a = seize(pool, queue = poolQ); servedAt = time; delay(1.0); release(a) }
        }

        override fun initialize() {
            servedAt = Double.NaN
            activate(Holder().work)
            activate(PoolWaiter().work, 1.0)
        }
    }

    @Test
    @DisplayName("A pool seized through a separate queue is woken when its member is released directly")
    fun poolThroughSeparateQueueIsWoken() {
        val m = simulate("poolSeparateQueue") { PoolThroughSeparateQueue(it) }
        assertEquals(10.0, m.servedAt, 1e-9)
    }

    /**
     *  One queue holding requests for two resources. X goes off shift at 5 and becomes inactive at 10, when its
     *  busy unit is released, while one request waits for X and another for Y; only the entity waiting for X is
     *  told.
     */
    private class SharedQueueInactive(parent: ModelElement) : ProcessModel(parent, null) {
        val x = ResourceWithQ(this, name = "X")
        val y = ResourceWithQ(this, name = "Y")
        private val shared = RequestQ(this, "SharedQ")
        private val xShift = ShiftControl(x)
        val told = mutableListOf<String>()

        inner class Holder(private val r: ResourceWithQ, private val until: Double) : Entity() {
            val work = process { val a = seize(r); delay(until); release(a) }
        }

        inner class Waiter(private val label: String, private val r: ResourceWithQ) : Entity() {
            val work = process { val a = seize(r, queue = shared); delay(1.0); release(a) }

            override fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(
                queue: RequestQ, resource: Resource, request: Request
            ) {
                told += label
            }
        }

        inner class Supervisor : Entity() {
            val work = process { delay(5.0); xShift.goOffShift(); delay(10.0); xShift.goOnShift() }
        }

        override fun initialize() {
            told.clear()
            xShift.initialize()
            activate(Holder(x, 10.0).work)
            activate(Holder(y, 20.0).work)
            activate(Waiter("forX", x).work, 1.0)
            activate(Waiter("forY", y).work, 2.0)
            activate(Supervisor().work)
        }
    }

    /**
     *  B waits in a separate queue for R from 1 to 10, and P's waiter waits for the pool from 2 to 15. At 5 a
     *  probe checks both queues and tries to unregister them; at 30, with nothing waiting, it tries again.
     */
    private class Unregister(parent: ModelElement) : ProcessModel(parent, null) {
        val r = ResourceWithQ(this, name = "R")
        val w = ResourceWithQ(this, name = "W")
        private val pool = ResourcePool(this, listOf(w), name = "P")
        private val otherQ = RequestQ(this, "OtherQ")
        private val poolQ = RequestQ(this, "PoolQ")
        val waiting = mutableListOf<Boolean>()
        val refused = mutableListOf<Boolean>()

        inner class Holder(private val res: ResourceWithQ, private val until: Double) : Entity() {
            val work = process { val a = seize(res); delay(until); release(a) }
        }

        inner class B : Entity() {
            val work = process { val a = seize(r, queue = otherQ); delay(1.0); release(a) }
        }

        inner class PoolWaiter : Entity() {
            val work = process { val a = seize(pool, queue = poolQ); delay(1.0); release(a) }
        }

        private fun attempt() {
            waiting += otherQ.hasRequestsWaitingFor(r)
            waiting += poolQ.hasRequestsWaitingFor(w)
            refused += runCatching { r.unregisterCapacityChangeQueue(otherQ) }.isFailure
            refused += runCatching { w.unregisterCapacityChangeQueue(poolQ) }.isFailure
        }

        inner class Probe : Entity() {
            val work = process { delay(5.0); attempt(); delay(25.0); attempt() }
        }

        override fun initialize() {
            waiting.clear()
            refused.clear()
            activate(Holder(r, 10.0).work)
            activate(Holder(w, 15.0).work)
            activate(B().work, 1.0)
            activate(PoolWaiter().work, 2.0)
            activate(Probe().work)
        }
    }

    @Test
    @DisplayName("A queue cannot be unregistered while it holds a request waiting for the resource")
    fun unregisterRefusedWhileWaiting() {
        val m = simulate("unregister") { Unregister(it) }
        assertEquals(listOf(true, true, false, false), m.waiting, "waiting at 5, directly and through the pool; none at 30")
        assertEquals(listOf(true, true, false, false), m.refused, "refused at 5; allowed at 30")
    }

    @Test
    @DisplayName("Only requests for the resource that became inactive are told, in a queue shared with another")
    fun inactiveNotificationIsForTheResourceOnly() {
        val m = simulate("sharedInactive") { SharedQueueInactive(it) }
        assertEquals(listOf("forX"), m.told)
    }
}
