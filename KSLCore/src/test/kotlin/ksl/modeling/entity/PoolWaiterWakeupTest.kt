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
import kotlin.test.assertTrue

/**
 *  A request waiting in a `RequestQ` names what was **wanted**; a release names what was **freed**;
 *  `RequestQ` matches the two by identity. A request built by a seize against a *pool* names the
 *  pool — it has to, because no member has been chosen at the moment it queues.
 *
 *  Ordinary pools honour that on release because `ResourcePool.allocate` returns a
 *  `ResourcePoolAllocation`, a distinct type that remembers the pool, so overload resolution selects
 *  a release that hands the pool to the queue. `MovableResourcePool.allocate` returned the *member's*
 *  own allocation, so the generic release handed over the individual truck, which matches none of the
 *  pool's waiting requests. Nothing on the pool side compensated, so a waiter was never woken.
 *
 *  It was not a deadlock, which is why it stayed hidden: a seize consults pool availability directly
 *  and only suspends when nothing is free, so later arrivals barge past the queue and the model keeps
 *  running while everything that ever waited is silently lost. Measured on the chapter 8 test-and-
 *  repair model, roughly one part in fifty vanished, at a rate linear in the length of the run.
 *
 *  Every test here failed before the fix.
 */
class PoolWaiterWakeupTest {

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return p
    }

    /** Two locations 100 apart at velocity 10, so a loaded move takes exactly 10 time units. */
    private abstract class FleetModel(parent: ModelElement, numTrucks: Int) : ProcessModel(parent, null) {
        protected val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val q = RequestQ(this, "TruckQ")
        private val fleet = List(numTrucks) { MovableResource(this, locA, speed, "Truck$it") }
        val trucks = MovableResourcePoolWithQ(this, fleet, speed, q, "Trucks")
    }

    // ── The reported defect ─────────────────────────────────────────────────

    private class ContendedFleet(parent: ModelElement) : FleetModel(parent, numTrucks = 1) {
        var completed = 0

        inner class Job : Entity() {
            val work = process("job") {
                entity.currentLocation = locA
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
    @DisplayName("A contended movable pool serves every queued job")
    fun contendedMovablePoolServesEveryJob() {
        val m = simulate("contendedFleet") { ContendedFleet(it) }
        assertEquals(
            3, m.completed,
            "one truck, three jobs: releasing it must wake the next waiter. Before the fix exactly " +
                "one job completed and the other two were stranded for the rest of the replication",
        )
        val stat = m.q.timeInQ.withinReplicationStatistic
        assertEquals(3.0, stat.count, 0.0, "every seize records a waiting-time observation")
        assertEquals(10.0, stat.weightedAverage, 1e-9, "waits are exactly 0, 10 and 20")
    }

    // ── The same defect reached through termination ─────────────────────────

    private class TerminatedFleetHolder(parent: ModelElement) : FleetModel(parent, numTrucks = 1) {
        var waiterServed = false
        lateinit var holder: Holder

        inner class Holder : Entity() {
            val work = process("holder") {
                entity.currentLocation = locA
                seize(trucks, requestLocation = locA)
                delay(100.0)
            }
        }

        inner class Waiter : Entity() {
            val work = process("waiter") {
                entity.currentLocation = locA
                val a = seize(trucks, requestLocation = locA)
                waiterServed = true
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            waiterServed = false
            holder = Holder()
            activate(holder.work)
            activate(Waiter().work, 1.0)
            schedule(::killHolder, 5.0)
        }

        private fun killHolder(event: KSLEvent<Nothing>) {
            holder.terminateProcess()
        }
    }

    @Test
    @DisplayName("Terminating an entity that holds a fleet truck wakes the pool's waiter")
    fun terminatingAFleetHolderWakesThePoolsWaiter() {
        val m = simulate("terminatedFleetHolder") { TerminatedFleetHolder(it) }
        assertTrue(
            m.waiterServed,
            "termination releases through releaseAllResources(), which funnels into the same release " +
                "overload, so the pool must be woken there too",
        )
        assertTrue(m.trucks.hasAvailableUnits, "the truck went back to the pool")
    }

    // ── Gate G2: the same defect on an ordinary pool ────────────────────────

    /**
     *  `ResourcePoolAllocation.allocations` is public, so a modeller can release a member-level
     *  allocation of an ordinary pool directly. That bypasses the type-guided release which is the
     *  only reason ordinary pools worked, and lands on exactly the path movable pools always took.
     */
    private class OrdinaryPoolInnerRelease(parent: ModelElement) : ProcessModel(parent, null) {
        val q = RequestQ(this, "PoolQ")
        private val r1 = Resource(this, name = "PooledR", capacity = 1)
        val pool = ResourcePoolWithQ(this, listOf(r1), q, "Pool")

        var waiterServed = false

        inner class Holder : Entity() {
            val work = process("holder") {
                val a = seize(pool, 1)
                delay(10.0)
                release(a.allocations.first())      // the member-level allocation, not the wrapper
            }
        }

        inner class Waiter : Entity() {
            val work = process("waiter") {
                val a = seize(pool, 1)
                waiterServed = true
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            waiterServed = false
            activate(Holder().work)
            activate(Waiter().work, 1.0)
        }
    }

    @Test
    @DisplayName("Releasing an ordinary pool's member allocation directly still wakes the pool's waiter")
    fun releasingAnInnerPoolAllocationWakesTheWaiter() {
        val m = simulate("innerPoolRelease") { OrdinaryPoolInnerRelease(it) }
        assertTrue(m.waiterServed, "the request names the pool however the allocation is released")
    }

    /**
     *  The broader consequence of the same gap: `releaseAllResources()` walks the entity's
     *  member-level allocations, so terminating an entity holding an **ordinary** pool allocation
     *  released it through the member-level path too, and stranded that pool's waiters.
     */
    private class TerminatedOrdinaryPoolHolder(parent: ModelElement) : ProcessModel(parent, null) {
        val q = RequestQ(this, "PoolQ")
        private val r1 = Resource(this, name = "PooledR", capacity = 1)
        val pool = ResourcePoolWithQ(this, listOf(r1), q, "Pool")

        var waiterServed = false
        lateinit var holder: Holder

        inner class Holder : Entity() {
            val work = process("holder") {
                seize(pool, 1)
                delay(100.0)
            }
        }

        inner class Waiter : Entity() {
            val work = process("waiter") {
                val a = seize(pool, 1)
                waiterServed = true
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            waiterServed = false
            holder = Holder()
            activate(holder.work)
            activate(Waiter().work, 1.0)
            schedule(::killHolder, 5.0)
        }

        private fun killHolder(event: KSLEvent<Nothing>) {
            holder.terminateProcess()
        }
    }

    @Test
    @DisplayName("Terminating an entity that holds an ordinary pool allocation wakes the pool's waiter")
    fun terminatingAnOrdinaryPoolHolderWakesThePoolsWaiter() {
        val m = simulate("terminatedOrdinaryPoolHolder") { TerminatedOrdinaryPoolHolder(it) }
        assertTrue(
            m.waiterServed,
            "termination walks the entity's member-level allocations, so ordinary pools were exposed " +
                "to the same defect through releaseAllResources()",
        )
    }

    // ── The shipped convenience, under contention ───────────────────────────

    private class ContendedTransportWith(parent: ModelElement) : FleetModel(parent, numTrucks = 1) {
        var completed = 0

        inner class Job : Entity() {
            val work = process("job") {
                entity.currentLocation = locA
                transportWith(trucks, toLoc = locB)
                completed++
            }
        }

        override fun initialize() {
            completed = 0
            repeat(3) { activate(Job().work) }
        }
    }

    @Test
    @DisplayName("transportWith over a contended movable pool serves every job")
    fun transportWithUnderContentionServesEveryJob() {
        val m = simulate("contendedTransportWith") { ContendedTransportWith(it) }
        assertEquals(
            3, m.completed,
            "transportWith does seize(fleet) ... release(a), so it rode on the defect; every shipped " +
                "model that moves parts with a pool of transporters was losing the ones that waited",
        )
    }
}
