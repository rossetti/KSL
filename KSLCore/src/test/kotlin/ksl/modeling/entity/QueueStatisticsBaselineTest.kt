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

import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.MovableResourcePoolWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 *  A baseline for the request-queue waiting-time statistics that every `seize` and every
 *  `waitForItems` produces.
 *
 *  These tests contain no terminations and assert nothing about termination. They exist because the
 *  termination-cleanup work adds a queue removal that runs on the **success** path as well as the
 *  failure path. If that removal is ever allowed to pre-empt the statistic-bearing one, `Queue.remove`
 *  finds nothing to remove and records no observation — and every seize in the library silently stops
 *  contributing to its queue's `timeInQ`. That is a normal-path regression produced by an
 *  abnormal-path fix, so no termination test would catch it. These will.
 *
 *  Each assertion is on the **observation count** as well as the average. An average over zero
 *  observations is `NaN` or `0.0` and can read as plausible; a count cannot.
 *
 *  The models are deterministic — fixed delays, fixed capacities, no random variables — so the
 *  expected waiting times are exact rather than statistical.
 */
class QueueStatisticsBaselineTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    /**
     *  Capacity one, [numJobs] jobs released together, each holding for 10 time units. Job *i*
     *  therefore waits exactly 10*i*: 0, 10, 20, …
     */
    private class PlainSeizeModel(parent: ModelElement, val numJobs: Int = 3) : ProcessModel(parent, null) {
        val q = RequestQ(this, "PlainQ")
        val res = ResourceWithQ(this, name = "R", capacity = 1, queue = q)

        /** Counted in process code so the statistic can be checked against reality, not a constant. */
        var completedSeizes = 0

        inner class Job : Entity() {
            val work = process("job") {
                val a = seize(res, 1)
                completedSeizes++
                delay(10.0)
                release(a)
            }
        }

        override fun initialize() {
            repeat(numJobs) { activate(Job().work) }
        }
    }

    /** The same pattern through the resource-pool overload of `seize`. */
    private class PoolSeizeModel(parent: ModelElement) : ProcessModel(parent, null) {
        val q = RequestQ(this, "PoolQ")
        private val r1 = Resource(this, name = "PooledR", capacity = 1)
        val pool = ResourcePoolWithQ(this, listOf(r1), q, "Pool")

        inner class Job : Entity() {
            val work = process("job") {
                val a = seize(pool, 1)
                delay(10.0)
                release(a)
            }
        }

        override fun initialize() {
            repeat(3) { activate(Job().work) }
        }
    }

    /** The same pattern through the movable-resource-pool overload of `seize`. */
    private class MovablePoolSeizeModel(parent: ModelElement, val numTrucks: Int = 3) : ProcessModel(parent, null) {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        private val locA = dm.Location("A")
        private val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val q = RequestQ(this, "TruckQ")
        private val fleet = List(numTrucks) {
            MovableResource(this, initLocation = locA, defaultVelocity = speed, name = "Truck$it")
        }
        val trucks = MovableResourcePoolWithQ(this, fleet, speed, q, "Trucks")

        var completedJobs = 0

        inner class Job : Entity() {
            val work = process("job") {
                currentLocation = locA
                val a = seize(trucks, requestLocation = locA)
                delay(10.0)
                release(a)
                completedJobs++
            }
        }

        override fun initialize() {
            repeat(3) { activate(Job().work) }
        }
    }

    /**
     *  Two receivers wait from time zero; items arrive at 5 and 15. The receivers therefore wait
     *  exactly 5 and 15 in the blocking queue's request queue.
     */
    private class ItemRequestModel(parent: ModelElement) : ProcessModel(parent, null) {
        val bq = BlockingQueue<ModelElement.QObject>(this, name = "BQ")

        inner class Receiver : Entity() {
            val work = process("rx") {
                waitForItems(bq, 1)
            }
        }

        inner class Sender : Entity() {
            val work = process("tx") {
                delay(5.0)
                send(QObject("item1"), bq)
                delay(10.0)
                send(QObject("item2"), bq)
            }
        }

        override fun initialize() {
            activate(Receiver().work)
            activate(Receiver().work)
            activate(Sender().work)
        }
    }

    /**
     *  Three jobs, capacity one; the middle one reneges at time 5 through the supported path,
     *  `RequestQ.removeAndTerminate`. Job 1 waits 0 and job 3 waits 10; the reneger contributes
     *  nothing, because `removeAndTerminate` defaults to `waitStats = false`.
     */
    private class RenegeModel(parent: ModelElement) : ProcessModel(parent, null) {
        val q = RequestQ(this, "RenegeQ")
        val res = ResourceWithQ(this, name = "R", capacity = 1, queue = q)
        var renegedCount = 0

        inner class Job : Entity() {
            val work = process("job") {
                val a = seize(res, 1)
                delay(10.0)
                release(a)
            }
        }

        lateinit var reneger: Job

        override fun initialize() {
            activate(Job().work)
            reneger = Job()
            activate(reneger.work)
            activate(Job().work)
            schedule(::renege, 5.0)
        }

        private fun renege(event: KSLEvent<Nothing>) {
            val request = q.find { it.entity == reneger }
            if (request != null) {
                q.removeAndTerminate(request)
                renegedCount++
            }
        }
    }

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return p
    }

    // ── The three seize overloads ───────────────────────────────────────────

    @Test
    @DisplayName("Baseline: seize on a ResourceWithQ records one time-in-queue observation per seize")
    fun seizeOnResourceRecordsTimeInQueue() {
        val m = simulate("baselinePlainSeize") { PlainSeizeModel(it) }
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(3.0, stat.count, 0.0, "one observation per seize, including the seize that did not wait")
        assertEquals(10.0, stat.weightedAverage, 1e-9, "waits are exactly 0, 10 and 20")
    }

    @Test
    @DisplayName("Baseline: seize on a ResourcePoolWithQ records one time-in-queue observation per seize")
    fun seizeOnResourcePoolRecordsTimeInQueue() {
        val m = simulate("baselinePoolSeize") { PoolSeizeModel(it) }
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(3.0, stat.count, 0.0)
        assertEquals(10.0, stat.weightedAverage, 1e-9)
    }

    /**
     *  Unlike the two overloads above, this one runs **uncontended** — one truck per job, so every
     *  wait is zero. That is deliberate, and not a weaker test than it looks: what matters here is
     *  the observation *count*, because pre-empting the statistic-bearing removal shows up as a
     *  count of zero whether or not anybody waited.
     *
     *  Contention cannot be used, because a `MovableResourcePool` does not resume its queued waiters
     *  when an allocation is released. `release(allocation)` processes the queue for
     *  `allocation.myResource`, which is the individual movable resource, while a
     *  `MovableResourcePoolRequest` reports the *pool* as its resource — so the request never
     *  matches and the waiter is stranded. Measured on this code: with N trucks and 3 jobs, exactly
     *  N jobs complete. That is a pre-existing defect unrelated to termination and is out of scope
     *  here; it is recorded in the plan document.
     */
    @Test
    @DisplayName("Baseline: seize on a MovableResourcePoolWithQ records one time-in-queue observation per seize")
    fun seizeOnMovableResourcePoolRecordsTimeInQueue() {
        val m = simulate("baselineMovablePoolSeize") { MovablePoolSeizeModel(it) }
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(3, m.completedJobs, "sanity: uncontended, so every job must finish")
        assertEquals(3.0, stat.count, 0.0, "one observation per seize even when nothing waited")
        assertEquals(0.0, stat.weightedAverage, 1e-9, "uncontended: every wait is zero")
    }

    // ── The blocking-queue request queue ────────────────────────────────────

    @Test
    @DisplayName("Baseline: waitForItems records one time-in-queue observation per request")
    fun waitForItemsRecordsTimeInQueue() {
        val m = simulate("baselineWaitForItems") { ItemRequestModel(it) }
        val stat = m.bq.requestQ.timeInQ.withinReplicationStatistic
        assertEquals(2.0, stat.count, 0.0, "one observation per filled request")
        assertEquals(10.0, stat.weightedAverage, 1e-9, "waits are exactly 5 and 15")
    }

    // ── The count is the assertion that matters ─────────────────────────────

    @Test
    @DisplayName("Baseline: the observation count equals the number of completed seizes")
    fun observationCountEqualsCompletedSeizes() {
        val m = simulate("baselineSeizeCount") { PlainSeizeModel(it, numJobs = 5) }
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(5, m.completedSeizes, "sanity: every job should have finished its seize")
        assertEquals(
            m.completedSeizes.toDouble(), stat.count, 0.0,
            "an observation is recorded for every seize -- neither pre-empted nor duplicated",
        )
    }

    // ── The reneging convention the cleanup sweep must follow ───────────────

    @Test
    @DisplayName("Baseline: a reneged request contributes no waiting-time observation")
    fun renegingContributesNoWaitObservation() {
        val m = simulate("baselineRenege") { RenegeModel(it) }
        assertEquals(1, m.renegedCount, "sanity: the reneger must actually have been found and removed")
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(2.0, stat.count, 0.0, "only the two jobs that completed their seize are observed")
        assertEquals(5.0, stat.weightedAverage, 1e-9, "waits are exactly 0 and 10")
        assertNotNull(m.res.waitingQ, "the resource keeps its queue reference")
        assertEquals(0, m.q.size, "no request is left behind by the supported reneging path")
    }
}
