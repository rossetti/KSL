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
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A baseline for the release paths that the F-1 fix changes.
 *
 *  F-1 is that a `MovableResourcePool` never wakes its queued waiters, because
 *  `MovableResourcePool.allocate` hands back the *member's* allocation and `release(allocation)`
 *  therefore processes the queue on behalf of the individual truck, while the waiting requests name
 *  the *pool*. The fix teaches the allocation which level it was requested at, which means changing
 *  the one release overload that every other release path funnels through.
 *
 *  Everything that already works therefore runs through the line being changed. These tests pin it,
 *  and they must pass **before** the fix as well as after — a test that only passes afterwards is a
 *  specification, not a baseline.
 *
 *  Deliberately absent: the *contended* movable-pool case. That is the defect, so its numbers are
 *  supposed to change; it belongs in the red-to-green set, not here. What is pinned here is the
 *  *uncontended* movable-pool path, where no request ever waits and the fix must therefore be
 *  invisible.
 *
 *  Assertions are on observation counts as well as averages, because an average over zero
 *  observations is `NaN` or `0.0` and reads as plausible.
 */
class PoolReleaseBaselineTest {

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return p
    }

    // ── The three paths that already work ───────────────────────────────────

    /**
     *  One holder, one waiter, capacity one. The holder occupies the resource from 0 to 10, so the
     *  waiter — which arrives at 1 — waits exactly 9.
     */
    private abstract class HandoffModel(parent: ModelElement) : ProcessModel(parent, null) {
        var waiterServed = false
        var waiterStart = Double.NaN

        protected abstract suspend fun KSLProcessBuilder.acquireHoldRelease(holdFor: Double)

        inner class Holder : Entity() {
            val work = process("holder") { acquireHoldRelease(10.0) }
        }

        inner class Waiter : Entity() {
            val work = process("waiter") {
                acquireHoldRelease(1.0)
                waiterServed = true
                waiterStart = time
            }
        }

        override fun initialize() {
            waiterServed = false
            waiterStart = Double.NaN
            activate(Holder().work)
            activate(Waiter().work, 1.0)
        }
    }

    private class PlainResourceHandoff(parent: ModelElement) : HandoffModel(parent) {
        val q = RequestQ(this, "Q")
        val res = ResourceWithQ(this, name = "R", capacity = 1, queue = q)

        override suspend fun KSLProcessBuilder.acquireHoldRelease(holdFor: Double) {
            val a = seize(res, 1)
            delay(holdFor)
            release(a)
        }
    }

    private class OrdinaryPoolHandoff(parent: ModelElement) : HandoffModel(parent) {
        val q = RequestQ(this, "Q")
        private val r1 = Resource(this, name = "PooledR", capacity = 1)
        val pool = ResourcePoolWithQ(this, listOf(r1), q, "Pool")

        override suspend fun KSLProcessBuilder.acquireHoldRelease(holdFor: Double) {
            val a = seize(pool, 1)
            delay(holdFor)
            release(a)
        }
    }

    private class SingleMovableResourceHandoff(parent: ModelElement) : HandoffModel(parent) {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        private val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val truck = MovableResourceWithQ(this, locA, speed, null, "Truck")

        override suspend fun KSLProcessBuilder.acquireHoldRelease(holdFor: Double) {
            // entity.currentLocation, not a bare currentLocation: inside a KSLProcessBuilder
            // receiver the entity is not the implicit receiver, unlike inside a process { } block
            entity.currentLocation = locA
            val a = seize(truck)
            delay(holdFor)
            release(a)
        }
    }

    @Test
    @DisplayName("Baseline: releasing a plain resource wakes its waiter")
    fun plainResourceReleaseWakesItsWaiter() {
        val m = simulate("baselinePlainResource") { PlainResourceHandoff(it) }
        assertTrue(m.waiterServed, "the waiter must be served when the holder releases")
        assertEquals(11.0, m.waiterStart, 1e-9, "served at 10, holds 1, so it finishes at 11")
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(2.0, stat.count, 0.0, "one observation per seize")
        assertEquals(4.5, stat.weightedAverage, 1e-9, "waits are exactly 0 and 9")
    }

    @Test
    @DisplayName("Baseline: releasing an ordinary pool allocation wakes its waiter")
    fun ordinaryPoolReleaseWakesItsWaiter() {
        val m = simulate("baselineOrdinaryPool") { OrdinaryPoolHandoff(it) }
        assertTrue(m.waiterServed, "the pool must wake the entity queued for it")
        assertEquals(11.0, m.waiterStart, 1e-9)
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(2.0, stat.count, 0.0)
        assertEquals(4.5, stat.weightedAverage, 1e-9)
    }

    @Test
    @DisplayName("Baseline: releasing a single movable resource wakes its waiter")
    fun singleMovableResourceReleaseWakesItsWaiter() {
        val m = simulate("baselineSingleMovable") { SingleMovableResourceHandoff(it) }
        assertTrue(
            m.waiterServed,
            "a MovableResourceWithQ is seized through the plain-resource path, so it already works",
        )
        assertEquals(11.0, m.waiterStart, 1e-9)
        val stat = m.truck.waitingQ.timeInQ.withinReplicationStatistic
        assertEquals(2.0, stat.count, 0.0)
        assertEquals(4.5, stat.weightedAverage, 1e-9)
    }

    // ── The movable-pool path, uncontended ──────────────────────────────────

    /**
     *  One truck per job, so nothing ever waits and the defect cannot express itself. The fix must
     *  leave this identical — same completions, same observation count, same zero average.
     */
    private class UncontendedMovablePool(parent: ModelElement) : ProcessModel(parent, null) {
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
        private val fleet = List(3) { MovableResource(this, locA, speed, "Truck$it") }
        val trucks = MovableResourcePoolWithQ(this, fleet, speed, q, "Trucks")

        var completed = 0

        inner class Job : Entity() {
            val work = process("job") {
                currentLocation = locA
                val a = seize(trucks, requestLocation = locA)
                delay(10.0)
                release(a)
                completed++
            }
        }

        override fun initialize() {
            completed = 0
            repeat(3) { activate(Job().work) }
        }
    }

    @Test
    @DisplayName("Baseline: an uncontended movable pool completes every job and records no waiting")
    fun uncontendedMovablePoolRecordsNoWait() {
        val m = simulate("baselineUncontendedFleet") { UncontendedMovablePool(it) }
        assertEquals(3, m.completed, "one truck per job, so all three must finish")
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(3.0, stat.count, 0.0, "one observation per seize even when nothing waited")
        assertEquals(0.0, stat.weightedAverage, 1e-9, "uncontended: every wait is zero")
    }

    /**
     *  The shipped convenience over a movable pool, uncontended. `transportWith` does
     *  `seize(fleet) … release(a)`, so it exercises the exact line the fix changes; with one truck
     *  per job it must be unaffected.
     *
     *  Deterministic: constant velocity 10 over a distance of 100, so the loaded move is 10 time
     *  units and the empty move is zero because the trucks start where the jobs are.
     */
    private class UncontendedTransportWith(parent: ModelElement) : ProcessModel(parent, null) {
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
        private val fleet = List(3) { MovableResource(this, locA, speed, "Truck$it") }
        val trucks = MovableResourcePoolWithQ(this, fleet, speed, q, "Trucks")

        var completed = 0
        var lastArrival = Double.NaN

        inner class Job : Entity() {
            val work = process("job") {
                currentLocation = locA
                transportWith(trucks, toLoc = locB)
                completed++
                lastArrival = time
            }
        }

        override fun initialize() {
            completed = 0
            lastArrival = Double.NaN
            repeat(3) { activate(Job().work) }
        }
    }

    @Test
    @DisplayName("Baseline: transportWith over an uncontended movable pool is deterministic")
    fun transportWithUncontendedIsStable() {
        val m = simulate("baselineTransportWith") { UncontendedTransportWith(it) }
        assertEquals(3, m.completed, "one truck per job, so all three must arrive")
        assertEquals(10.0, m.lastArrival, 1e-9, "distance 100 at velocity 10, with a zero empty move")
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(3.0, stat.count, 0.0)
        assertEquals(0.0, stat.weightedAverage, 1e-9)
    }
}
