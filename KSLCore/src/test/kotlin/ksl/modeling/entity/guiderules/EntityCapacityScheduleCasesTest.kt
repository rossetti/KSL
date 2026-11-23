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

package ksl.modeling.entity.guiderules

import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Cases of the entity guide's section on shifts and capacity schedules: `goOffShift` and `goOnShift` on a movable
 *  resource with its own queue; every replication starting on shift after one that ended with an off-shift change
 *  still waiting under `WAIT`; and a direct capacity notice of infinite duration superseded while a unit is busy,
 *  under either capacity change rule.
 */
class EntityCapacityScheduleCasesTest {

    /**
     *  Truck, a movable resource with its own queue, is seized by C1 from 0 to 5. At 2 it goes off shift: under the
     *  default `IGNORE` its capacity drops to 0 at once while C1 works on. C2 asks for it at 3 and waits, also past
     *  C1's release. At 10 it goes back on shift, its initial capacity of 1 is restored, and C2 is served.
     */
    @Test
    @DisplayName("goOffShift drops a movable resource with a queue to zero, and goOnShift restores its capacity")
    fun movableResourceWithQOffAndOnShift() {
        val m = RuleModel("MovableShift")
        val dm = DistancesModel()
        val locA = dm.Location("A")
        dm.addDistance(locA, dm.Location("B"), 100.0, symmetric = true)
        dm.defaultVelocity = ConstantRV(10.0)
        m.spatialModel = dm
        val truck = MovableResourceWithQ(m, locA, ConstantRV(10.0), name = "Truck")
        fun state() = "off=${truck.isOffShift} cap=${truck.capacity}"
        m.plan {
            for ((tag, from, hold) in listOf(Triple("C1", 0.0, 5.0), Triple("C2", 3.0, 1.0))) {
                actor(tag) {
                    entity.currentLocation = locA
                    delay(from)
                    val a = seize(truck)
                    log.add("$tag got@$time")
                    delay(hold)
                    release(a)
                }
            }
            at(2.0) { truck.goOffShift(); log.add("t2 ${state()}") }
            at(10.0) { log.add("t10 ${state()} waiting=${truck.waitingQ.size}"); truck.goOnShift() }
            at(11.0) { log.add("t11 ${state()}") }
        }
        m.simulate(50.0)
        assertEquals(
            listOf("C1 got@0.0", "t2 off=true cap=0", "t10 off=true cap=0 waiting=1", "C2 got@10.0", "t11 off=false cap=1"),
            m.log
        )
    }

    /**
     *  R has 2 units under `WAIT`. H holds one from 0 to the end of each replication. At 5 R goes off shift: the
     *  idle unit goes at once and the busy one is still awaited when the replication ends. Each of two replications
     *  nonetheless starts on shift, at capacity 2, with no change pending.
     */
    @Test
    @DisplayName("a replication ending with an off-shift change still waiting under WAIT is followed by one that starts on shift")
    fun startsOnShiftAfterAPendingWaitChange() {
        val m = RuleModel("StartsOnShiftWait")
        val r = ResourceWithQ(m, "R", capacity = 2)
        r.capacityChangeRule = CapacityChangeRule.WAIT
        val seen = mutableListOf<String>()
        fun state() = "off=${r.isOffShift} cap=${r.capacity} pending=${r.isPendingCapacityChange}"
        m.plan {
            actor("H") {
                val a = seize(r)
                delay(1000.0)
                release(a)
            }
            at(1.0) { seen.add("t1 ${state()}") }
            at(5.0) { r.goOffShift() }
            at(6.0) { seen.add("t6 ${state()}") }
        }
        m.simulate(20.0, reps = 2)
        val oneReplication = listOf("t1 off=false cap=2 pending=false", "t6 off=true cap=1 pending=true")
        assertEquals(oneReplication + oneReplication, seen)
    }

    /**
     *  R has 2 units. H holds one from 0 to 10. At 1 a notice of capacity 0, with the default infinite duration, is
     *  given: under `IGNORE` the capacity is 0 at once; under `WAIT` the idle unit goes at once and the busy one is
     *  awaited; either way the change waits for the busy unit, lasting until superseded. W asks for a unit at 2 and
     *  waits. At 3 a notice of capacity 2 supersedes the first, whatever is busy:
     *  the capacity is 2, nothing is pending, and W takes the free unit at 3. After H's release at 10 the capacity
     *  is still 2.
     */
    @Test
    @DisplayName("a direct notice of infinite duration lasts until superseded, and superseding it works while a unit is busy, under either rule")
    fun directNoticeSupersededWhileBusy() {
        for (rule in listOf(CapacityChangeRule.IGNORE, CapacityChangeRule.WAIT)) {
            val m = RuleModel("SupersededNotice")
            val r = ResourceWithQ(m, "R", capacity = 2)
            r.capacityChangeRule = rule
            m.plan {
                actor("H") {
                    val a = seize(r)
                    delay(10.0)
                    release(a)
                }
                actor("W") {
                    delay(2.0)
                    val a = seize(r)
                    log.add("W got@$time")
                    delay(20.0)
                    release(a)
                }
                at(1.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 0)) }
                at(2.5) { log.add("t2.5 cap=${r.capacity} pendingEnd=${r.pendingCapacityChangeEndTime}") }
                at(3.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
                at(4.0) { log.add("t4 cap=${r.capacity} pending=${r.isPendingCapacityChange}") }
                at(11.0) { log.add("t11 cap=${r.capacity} busy=${r.numBusy}") }
            }
            m.simulate(50.0)
            val capAt2 = if (rule == CapacityChangeRule.IGNORE) 0 else 1
            assertEquals(
                listOf("t2.5 cap=$capAt2 pendingEnd=Infinity", "W got@3.0", "t4 cap=2 pending=false", "t11 cap=2 busy=1"),
                m.log,
                "rule $rule"
            )
        }
    }
}
