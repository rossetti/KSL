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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Under the IGNORE rule a decrease that arrives while another is pending waits, with its end scheduled when it
 *  arrives. It must end after the pending change, but nothing makes it end after the decreases already waiting.
 *  Three overlapping decreases: A (ends at 11) is pending, B (ends at 22) waits, C (ends at 13) waits behind B.
 *  C's end passes while it waits. When B ends at 22, C becomes pending short of idle units, and must not stay
 *  pending past its own end, nor report an end already past.
 */
class IgnoreWaitingDecreaseEndPassedTest {

    private class Cuts(parent: ModelElement) : ProcessModel(parent, "cuts") {
        val r = ResourceWithQ(this, "R", capacity = 4)
        val pendingAt25 = mutableListOf<Boolean>()
        val endTimeAt25 = mutableListOf<Double>()
        val capacityAt40 = mutableListOf<Int>()
        val pendingAt40 = mutableListOf<Boolean>()

        inner class Holder : Entity() {
            val p: KSLProcess = process("holder") {
                val a = seize(r, 1)
                delay(100.0)
                release(a)
            }
        }

        inner class Driver : Entity() {
            val p: KSLProcess = process("driver") {
                delay(1.0)  // t = 1: A, ends at 11
                r.changeCapacity(r.CapacityChangeNotice(capacity = 3, duration = 10.0))
                delay(1.0)  // t = 2: B, ends at 22
                r.changeCapacity(r.CapacityChangeNotice(capacity = 2, duration = 20.0))
                delay(1.0)  // t = 3: C, ends at 13, after A's end so it is accepted
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = 10.0))
                delay(22.0) // t = 25
                pendingAt25.add(r.isPendingCapacityChange)
                endTimeAt25.add(r.pendingCapacityChangeEndTime)
                delay(5.0)  // t = 30: D, ends at 35
                r.changeCapacity(r.CapacityChangeNotice(capacity = 0, duration = 5.0))
                delay(10.0) // t = 40
                capacityAt40.add(r.capacity)
                pendingAt40.add(r.isPendingCapacityChange)
            }
        }

        override fun initialize() {
            repeat(4) { activate(Holder().p) }
            activate(Driver().p)
        }
    }

    @Test
    @DisplayName("IGNORE: a waiting decrease whose end passed while it waited does not stay pending past its end")
    fun waitingDecreaseWhoseEndPassed() {
        val model = Model("IgnoreEndPassed${System.nanoTime()}", autoCSVReports = false)
        val m = Cuts(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        model.simulate()
        // at 25 no pending change may report an end before now
        val end = m.endTimeAt25.single()
        assertTrue(
            !m.pendingAt25.single() || end.isNaN() || end >= 25.0,
            "at 25 pendingCapacityChangeEndTime reports $end, a time already past"
        )
        // D (t = 30, capacity 0) finds no change pending that is still in its duration, so it applies at once
        assertEquals(0, m.capacityAt40.single(), "capacity at 40, after the decrease to 0 made at 30")
        assertFalse(m.pendingAt40.single(), "a decrease is still pending at 40, long after every decrease's end")
    }

    /**
     *  Capacity 3, all busy with holders (priority 10) until 100. At 1 a decrease to 2 (lasting until superseded)
     *  becomes pending; at 2 a decrease to 1 (duration 5, ends at 7) waits behind it, so 1 unit is reachable. At 3
     *  a preemptor (priority 1, RESUME) asks for 2, more than can be reached: it waits. At 7 the waiting decrease
     *  ends unapplied, 2 units are reachable again, and the preemptor takes a holder's units then.
     */
    private class Reach(parent: ModelElement) : ProcessModel(parent, "reach") {
        val r = ResourceWithQ(this, "R", capacity = 3)
        var preemptorStart = Double.NaN

        inner class Holder : Entity() {
            init { priority = 10 }
            val p: KSLProcess = process("holder") {
                val a = seize(r, 1)
                delay(100.0)
                release(a)
            }
        }

        inner class Preemptor : Entity() {
            init { priority = 1 }
            val p: KSLProcess = process("preemptor") {
                delay(3.0)
                val a = preempt(r, amountNeeded = 2, rule = InterruptRule.RESUME)
                preemptorStart = time
                delay(1.0)
                release(a)
            }
        }

        inner class Driver : Entity() {
            val p: KSLProcess = process("driver") {
                delay(1.0)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 2))
                delay(1.0)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = 5.0))
            }
        }

        override fun initialize() {
            repeat(3) { activate(Holder().p) }
            activate(Preemptor().p)
            activate(Driver().p)
        }
    }

    @Test
    @DisplayName("IGNORE: a waiting decrease that ends unapplied gives back the capacity it held out of reach at once")
    fun expiredWaitingDecreaseOffersReachableUnits() {
        val model = Model("IgnoreExpiredReach${System.nanoTime()}", autoCSVReports = false)
        val m = Reach(model)
        model.lengthOfReplication = 200.0
        model.simulate()
        assertEquals(7.0, m.preemptorStart, "the preemptor took its units at ${m.preemptorStart}")
    }
}
