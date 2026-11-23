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

import ksl.modeling.entity.BehaviorStop
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Cases of the entity guide's section on failing and repairing a resource: a movement is never held, under
 *  `RESUME` and `RESTART`, whether the entity moves the failed vehicle or moves itself while holding a failed
 *  resource; the time between failures starts at each repair on `BUSY_TIME` with a `FINISH` failure and on `USES`
 *  with a release made during the repair; and the declared failures go on whatever the resource's behavior does.
 */
class EntityFailureCasesTest {

    /** A model whose spatial model has A and B 100 apart, travelled at 10: a trip from A to B takes 10. */
    private class Spatial(name: String) {
        val m = RuleModel(name)
        val locA: LocationIfc
        val locB: LocationIfc

        init {
            val dm = DistancesModel()
            locA = dm.Location("A")
            locB = dm.Location("B")
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = ConstantRV(10.0)
            m.spatialModel = dm
        }
    }

    /**
     *  H seizes Truck at A at 0 and moves it to B, a trip of 10, then works 5. Truck fails at 2 under the rule and is
     *  repaired at 12. The trip is not held: H arrives at 10. The work begins held and starts at the repair, 12,
     *  ending at 17, under either rule.
     */
    @Test
    @DisplayName("a vehicle failing while its holder moves it: the trip finishes and the next delay begins held, under RESUME and RESTART")
    fun vehicleFailsWhileMovedUnderEitherRule() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) {
            val s = Spatial("VehicleFailsMoving")
            val truck = MovableResourceWithQ(s.m, s.locA, ConstantRV(10.0), name = "Truck")
            truck.interruptRule = rule
            s.m.plan {
                actor("H") {
                    entity.currentLocation = s.locA
                    val a = seize(truck)
                    moveWith(truck, s.locB, 10.0)
                    log.add("H arrived@$time")
                    delay(5.0)
                    release(a)
                    log.add("H done@$time")
                }
                at(2.0) { truck.fail() }
                at(12.0) { truck.repair() }
            }
            s.m.simulate(100.0)
            assertEquals(listOf("H arrived@10.0", "H done@17.0"), s.m.log, "rule $rule")
        }
    }

    /**
     *  H seizes R, an ordinary resource, at A at 0, and walks to B, a trip of 10, then works 5. R fails at 2 under the
     *  rule and is repaired at 12. The walk is not held: H arrives at 10, and its work begins held, ending at 17.
     */
    @Test
    @DisplayName("an entity moving itself while a resource it holds fails: the trip finishes and the next delay begins held, under RESUME and RESTART")
    fun selfMoveWhileHeldResourceFails() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) {
            val s = Spatial("SelfMoveFails")
            val r = ResourceWithQ(s.m, "R")
            s.m.plan {
                actor("H") {
                    entity.currentLocation = s.locA
                    val a = seize(r)
                    moveTo(s.locB, 10.0)
                    log.add("H arrived@$time")
                    delay(5.0)
                    release(a)
                    log.add("H done@$time")
                }
                at(2.0) { r.fail(rule) }
                at(12.0) { r.repair() }
            }
            s.m.simulate(100.0)
            assertEquals(listOf("H arrived@10.0", "H done@17.0"), s.m.log, "rule $rule")
        }
    }

    /**
     *  As for the vehicle, but Truck is repaired at 6, during the trip. The trip ends at 10 and the work, begun with
     *  nothing missing, ends at 15.
     */
    @Test
    @DisplayName("a vehicle failed and repaired during the trip: the trip and the next delay go on as if nothing happened")
    fun vehicleRepairedDuringTheTrip() {
        val s = Spatial("VehicleRepairedMoving")
        val truck = MovableResourceWithQ(s.m, s.locA, ConstantRV(10.0), name = "Truck")
        truck.interruptRule = InterruptRule.RESUME
        s.m.plan {
            actor("H") {
                entity.currentLocation = s.locA
                val a = seize(truck)
                moveWith(truck, s.locB, 10.0)
                log.add("H arrived@$time")
                delay(5.0)
                release(a)
                log.add("H done@$time")
            }
            at(2.0) { truck.fail() }
            at(6.0) { truck.repair() }
        }
        s.m.simulate(100.0)
        assertEquals(listOf("H arrived@10.0", "H done@15.0"), s.m.log)
    }

    /**
     *  R has declared failures on `BUSY_TIME` under `FINISH`: 4 of busy time between failures, each lasting 3. J holds
     *  R from 0 throughout, so R is busy all the time and J works on through each failure. The first failure comes at
     *  4 and is repaired at 7. J's work from 4 to 7 is the failure's own repair time and is not counted, so the next
     *  failure comes 4 of busy time after the repair, at 11, not at 8.
     */
    @Test
    @DisplayName("BUSY_TIME with FINISH: work done during a declared failure's repair is not counted toward the next failure")
    fun busyTimeFinishRepairTimeNotCounted() {
        val m = RuleModel("BusyTimeFinishRepair")
        val r = ResourceWithQ(m, "R")
        r.failures(ConstantRV(4.0), ConstantRV(3.0), ResourceFailureBasis.BUSY_TIME, InterruptRule.FINISH)
        m.plan {
            actor("J") {
                val a = seize(r)
                delay(100.0)
                release(a)
            }
            for (t in listOf(3.5, 4.5, 7.5, 8.5, 10.5, 11.5)) at(t) { log.add("t$t failed=${r.isFailed}") }
        }
        m.simulate(12.0)
        assertEquals(
            listOf(
                "t3.5 failed=false", "t4.5 failed=true", "t7.5 failed=false",
                "t8.5 failed=false", "t10.5 failed=false", "t11.5 failed=true"
            ),
            m.log
        )
    }

    /**
     *  R has 2 units and declared failures on `USES` under `FINISH`: every 2 releases, each failure lasting 4. A and B
     *  seize at 0; A releases at 1 (one use) and C, waiting since 0.5, takes its unit until 3. B's release at 2
     *  completes two uses, and R fails at 2; C works on and releases at 3, during the repair. The count starts at the
     *  repair, 6, so C's release is not counted: D's release at 8 is one use and E's at 10 is the second, failing R
     *  at 10, not at 8.
     */
    @Test
    @DisplayName("USES with FINISH: a release made during a declared failure's repair is not counted toward the next failure")
    fun usesReleaseDuringRepairNotCounted() {
        val m = RuleModel("UsesFinishRepair")
        val r = ResourceWithQ(m, "R", capacity = 2)
        r.failures(ConstantRV(2.0), ConstantRV(4.0), ResourceFailureBasis.USES, InterruptRule.FINISH)
        m.plan {
            for ((tag, from, hold) in listOf(
                Triple("A", 0.0, 1.0), Triple("B", 0.0, 2.0), Triple("C", 0.5, 2.0),
                Triple("D", 7.0, 1.0), Triple("E", 9.0, 1.0)
            )) {
                actor(tag) {
                    delay(from)
                    val a = seize(r)
                    delay(hold)
                    release(a)
                    log.add("$tag released@$time failed=${r.isFailed}")
                }
            }
            for (t in listOf(5.5, 6.5, 8.5, 10.5)) at(t) { log.add("t$t failed=${r.isFailed}") }
        }
        m.simulate(11.0)
        assertEquals(
            listOf(
                "A released@1.0 failed=false", "B released@2.0 failed=true", "C released@3.0 failed=true",
                "t5.5 failed=true", "t6.5 failed=false", "D released@8.0 failed=false", "t8.5 failed=false",
                "E released@10.0 failed=true", "t10.5 failed=true"
            ),
            m.log
        )
    }

    /**
     *  R fails every 10 of calendar time for 2, and has a cyclic behavior that waits 3. The behavior is stopped at
     *  once at 1 and started again at 15. The failures are the resource's own entity's, not the behavior's: they come
     *  at 10 and 22 whatever the behavior does, and a fresh start of the behavior does not restart their clock.
     *  The same holds when the behavior is never started (`behaviorActiveAtStart` false).
     */
    @Test
    @DisplayName("declared failures go on whatever the resource's behavior does: stopped, started again, or never started")
    fun failuresSeparateFromTheBehavior() {
        for (activeAtStart in listOf(true, false)) {
            val m = RuleModel("FailuresAndBehavior")
            val r = ResourceWithQ(m, "R")
            r.failures(ConstantRV(10.0), ConstantRV(2.0))
            r.cyclicBehavior { delay(3.0) }
            r.behaviorActiveAtStart = activeAtStart
            m.plan {
                at(1.0) { if (r.isBehaviorActive) r.stopBehavior(BehaviorStop.NOW) }
                at(15.0) { r.startBehavior() }
                for (t in listOf(10.5, 12.5, 21.5, 22.5, 24.5)) at(t) { log.add("t$t failed=${r.isFailed}") }
            }
            m.simulate(30.0, reps = 2)
            assertEquals(
                listOf("t10.5 failed=true", "t12.5 failed=false", "t21.5 failed=false", "t22.5 failed=true", "t24.5 failed=false"),
                m.log,
                "behaviorActiveAtStart $activeAtStart"
            )
        }
    }
}
