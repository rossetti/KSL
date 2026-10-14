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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Lost wakeups between pools and their members, one test per case (F1 to F4, and F6). Each is written as the
 *  behaviour the R1.8 pool wakeup rework must produce, so each fails on `develop` at `c06710b0c` and passes once
 *  the rework lands. They are disabled until then.
 *
 *  In every case a single unit is freed at a known time and one entity is waiting for it through a different
 *  route than the one the unit was released through. The waiter should get the unit the moment it is freed.
 */
class PoolWakeupDefectTest {

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name, autoCSVReports = false)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return p
    }

    private abstract class WaiterModel(parent: ModelElement) : ProcessModel(parent, null) {
        var servedAt = Double.NaN

        override fun initialize() {
            servedAt = Double.NaN
        }
    }

    /** F1: the unit is released through a pool; the waiter asked for the member directly. */
    private class ReleasedThroughPoolWaiterOnMember(parent: ModelElement) : WaiterModel(parent) {
        val w = ResourceWithQ(this, name = "W")
        val pool = ResourcePoolWithQ(this, listOf(w), name = "P")

        inner class Holder : Entity() {
            val work = process { val a = seize(pool); delay(5.0); release(a) }
        }

        inner class Waiter : Entity() {
            val work = process { val a = seize(w); servedAt = time; delay(1.0); release(a) }
        }

        override fun initialize() {
            super.initialize()
            activate(Holder().work)
            activate(Waiter().work, 1.0)
        }
    }

    /** F2: the unit is released directly; the waiter asked for the pool. */
    private class ReleasedDirectlyWaiterOnPool(parent: ModelElement) : WaiterModel(parent) {
        val w = ResourceWithQ(this, name = "W")
        val pool = ResourcePoolWithQ(this, listOf(w), name = "P")

        inner class Holder : Entity() {
            val work = process { val a = seize(w); delay(5.0); release(a) }
        }

        inner class Waiter : Entity() {
            val work = process { val a = seize(pool); servedAt = time; delay(1.0); release(a) }
        }

        override fun initialize() {
            super.initialize()
            activate(Holder().work)
            activate(Waiter().work, 1.0)
        }
    }

    /** F3: the member belongs to two pools; released through one, the waiter asked for the other. */
    private class OverlappingPools(parent: ModelElement) : WaiterModel(parent) {
        val w = Resource(this, name = "W")
        val p1 = ResourcePoolWithQ(this, listOf(w), name = "P1")
        val p2 = ResourcePoolWithQ(this, listOf(w), name = "P2")

        inner class Holder : Entity() {
            val work = process { val a = seize(p1); delay(5.0); release(a) }
        }

        inner class Waiter : Entity() {
            val work = process { val a = seize(p2); servedAt = time; delay(1.0); release(a) }
        }

        override fun initialize() {
            super.initialize()
            activate(Holder().work)
            activate(Waiter().work, 1.0)
        }
    }

    /** F4: the member is off shift until 5, then comes back; the waiter asked for the pool. */
    private class MemberBackOnShift(parent: ModelElement) : WaiterModel(parent) {
        val w = Resource(this, name = "W")
        private val schedule = CapacitySchedule(this, 0.0, name = "Shift").apply {
            addItem(capacity = 0, duration = 5.0)
            addItem(capacity = 1, duration = 1000.0)
        }
        val pool = ResourcePoolWithQ(this, listOf(w), name = "P")

        init {
            w.useSchedule(schedule, CapacityChangeRule.IGNORE)
        }

        inner class Waiter : Entity() {
            val work = process { val a = seize(pool); servedAt = time; delay(1.0); release(a) }
        }

        override fun initialize() {
            super.initialize()
            activate(Waiter().work, 1.0)
        }
    }

    /**
     *  F6: a pool waiter is promised the freed unit, but a request arriving at the same instant takes it first.
     *  This can only happen when the release's priority is lower than the arrival's seize priority, so the
     *  holder releases at `KSLEvent.LOW_PRIORITY`, and the arrival starts after the release at the same instant.
     */
    private class ArrivalTakesPromisedUnit(parent: ModelElement) : WaiterModel(parent) {
        val w = Resource(this, name = "W")
        val pool = ResourcePoolWithQ(this, listOf(w), name = "P")

        inner class Holder : Entity() {
            val work = process {
                val a = seize(pool)
                delay(10.0)
                release(a, releasePriority = KSLEvent.LOW_PRIORITY)
            }
        }

        inner class Waiter : Entity() {
            val work = process { val a = seize(pool); servedAt = time; delay(1.0); release(a) }
        }

        inner class Arrival : Entity() {
            val work = process { val a = seize(pool); delay(1.0); release(a) }
        }

        override fun initialize() {
            super.initialize()
            activate(Holder().work)
            activate(Waiter().work, 1.0)
            // after the holder's release at 10 (a delay end, at the default priority), before the waiter's resume
            activate(Arrival().work, 10.0, KSLEvent.MEDIUM_LOW_PRIORITY)
        }
    }

    @Disabled("Enabled with the R1.8 pool wakeup rework")
    @Test
    @DisplayName("F1: a unit released through a pool reaches the member's own waiter")
    fun releasedThroughPoolReachesMemberWaiter() {
        val m = simulate("F1") { ReleasedThroughPoolWaiterOnMember(it) }
        assertEquals(5.0, m.servedAt, 1e-9)
    }

    @Disabled("Enabled with the R1.8 pool wakeup rework")
    @Test
    @DisplayName("F2: a unit released directly reaches the pool's waiter")
    fun releasedDirectlyReachesPoolWaiter() {
        val m = simulate("F2") { ReleasedDirectlyWaiterOnPool(it) }
        assertEquals(5.0, m.servedAt, 1e-9)
    }

    @Disabled("Enabled with the R1.8 pool wakeup rework")
    @Test
    @DisplayName("F3: a unit released through one pool reaches another pool's waiter")
    fun overlappingPoolsShareTheWakeup() {
        val m = simulate("F3") { OverlappingPools(it) }
        assertEquals(5.0, m.servedAt, 1e-9)
    }

    @Disabled("Enabled with the R1.8 pool wakeup rework")
    @Test
    @DisplayName("F4: a member coming back on shift reaches the pool's waiter")
    fun memberBackOnShiftReachesPoolWaiter() {
        val m = simulate("F4") { MemberBackOnShift(it) }
        assertEquals(5.0, m.servedAt, 1e-9)
    }

    @Disabled("Enabled with the R1.8 pool wakeup rework")
    @Test
    @DisplayName("F6: an arrival at the same instant does not take a unit promised to a waiter")
    fun arrivalDoesNotTakePromisedUnit() {
        var model: ArrivalTakesPromisedUnit? = null
        val error = runCatching { simulate("F6") { ArrivalTakesPromisedUnit(it).also { p -> model = p } } }
            .exceptionOrNull()
        assertNull(error, "the run must not fail: ${error?.message}")
        assertEquals(10.0, model!!.servedAt, 1e-9, "the waiter was promised the unit at 10")
    }
}
