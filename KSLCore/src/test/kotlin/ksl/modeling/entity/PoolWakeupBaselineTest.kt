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

import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Baselines for the R1.8 pool wakeup rework, recorded on `develop` at `c06710b0c` before any change.
 *
 *  The rework replaces the three wake sites with one routine on the freed member. Models without pools,
 *  and pools whose members keep a fixed capacity, never reach the lost-wakeup cases, so their numbers must
 *  not move: those tests are expected to stay green throughout. A pool whose members follow a capacity
 *  schedule does reach one (a member coming back on shift never wakes the pool's waiters), so its numbers
 *  are expected to change when the wake routine lands. That test pins today's values so the change is seen
 *  and reviewed rather than missed, and is updated deliberately at that point.
 *
 *  Each model draws arrivals and service from fixed streams, so a value moves only if the logic does.
 */
class PoolWakeupBaselineTest {

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name, autoCSVReports = false)
        val p = factory(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 2000.0
        m.simulate()
        return p
    }

    /** Arrivals, a seize of what the subclass serves from, a service delay, a release. */
    private abstract class QueueingModel(
        parent: ModelElement,
        meanTimeBetweenArrivals: Double
    ) : ProcessModel(parent, null) {
        private val tba = ExponentialRV(meanTimeBetweenArrivals, 1)
        private val service = RandomVariable(this, ExponentialRV(1.5, 2))
        private val generator = EntityGenerator(::Customer, tba, tba)

        abstract val queue: RequestQ

        protected abstract suspend fun KSLProcessBuilder.serve(time: Double)

        inner class Customer : Entity() {
            val work = process(isDefaultProcess = true) { serve(service.value) }
        }
    }

    private class MMcWithQ(parent: ModelElement) : QueueingModel(parent, 1.0) {
        val server = ResourceWithQ(this, name = "Servers", capacity = 2)
        override val queue: RequestQ get() = server.myWaitingQ

        override suspend fun KSLProcessBuilder.serve(time: Double) {
            val a = seize(server)
            delay(time)
            release(a)
        }
    }

    private class FixedCapacityPool(parent: ModelElement) : QueueingModel(parent, 0.6) {
        private val members = List(3) { Resource(this, name = "Member$it", capacity = 1) }
        override val queue = RequestQ(this, "PoolQ")
        val pool = ResourcePoolWithQ(this, members, queue, "Pool")

        override suspend fun KSLProcessBuilder.serve(time: Double) {
            val a = seize(pool)
            delay(time)
            release(a)
        }
    }

    /** Each member works 50 time units, then is off for 10, repeating. */
    private class ScheduledPool(parent: ModelElement) : QueueingModel(parent, 1.2) {
        private val members = List(2) { i ->
            val r = Resource(this, name = "Member$i", capacity = 1)
            val schedule = CapacitySchedule(this, 0.0, repeatable = true, name = "Shift$i")
            schedule.addItem(capacity = 1, duration = 50.0)
            schedule.addItem(capacity = 0, duration = 10.0)
            r.useSchedule(schedule, CapacityChangeRule.IGNORE)
            r
        }
        override val queue = RequestQ(this, "PoolQ")
        val pool = ResourcePoolWithQ(this, members, queue, "Pool")

        override suspend fun KSLProcessBuilder.serve(time: Double) {
            val a = seize(pool)
            delay(time)
            release(a)
        }
    }

    private fun assertQueue(queue: RequestQ, expectedCount: Double, expectedWait: Double) {
        val stat = queue.timeInQ.acrossReplicationStatistic
        val count = queue.timeInQ.withinReplicationStatistic.count
        println("${queue.name}: last-replication count = $count, across-replication mean wait = ${stat.average}")
        assertEquals(expectedCount, count, 0.0, "observations in the last replication")
        assertEquals(expectedWait, stat.average, 1e-9, "across-replication mean time in queue")
    }

    @Test
    @DisplayName("Unchanged by the pool wakeup rework: a ResourceWithQ with no pool")
    fun resourceWithQueueNoPool() {
        val m = simulate("baselineMMc") { MMcWithQ(it) }
        assertQueue(m.queue, MMC_COUNT, MMC_WAIT)
    }

    @Test
    @DisplayName("Unchanged by the pool wakeup rework: a pool whose members keep a fixed capacity")
    fun fixedCapacityPool() {
        val m = simulate("baselineFixedPool") { FixedCapacityPool(it) }
        assertQueue(m.queue, FIXED_POOL_COUNT, FIXED_POOL_WAIT)
    }

    @Test
    @DisplayName("Changed by the pool wakeup rework by design (re-pinned): a pool whose members follow a capacity schedule")
    fun scheduledPool() {
        val m = simulate("baselineScheduledPool") { ScheduledPool(it) }
        assertQueue(m.queue, SCHEDULED_POOL_COUNT, SCHEDULED_POOL_WAIT)
    }

    companion object {
        // Recorded on develop @ c06710b0c, before the R1.8 changes.
        const val MMC_COUNT = 1991.0
        const val MMC_WAIT = 1.925768944960903
        const val FIXED_POOL_COUNT = 3264.0
        const val FIXED_POOL_WAIT = 1.8650706870421305
        const val SCHEDULED_POOL_COUNT = 1682.0
        // Since R1.8: a member back on shift now wakes the pool's waiters at once. Before (c06710b0c): 4.419765484845876
        const val SCHEDULED_POOL_WAIT = 3.5992793301523984
    }
}
