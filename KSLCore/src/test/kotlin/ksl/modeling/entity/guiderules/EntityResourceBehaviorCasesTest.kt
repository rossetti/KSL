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
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Cases of the entity guide's section on giving a resource its own behavior: the cyclic form starting after every
 *  element has initialized; `behaviorActiveAtStart` deciding each replication afresh, whatever the one before did;
 *  captured state carrying over in the cyclic form; and, on a resource with a capacity schedule, the behavior's own
 *  `goOffShift` and capacity change refused.
 */
class EntityResourceBehaviorCasesTest {

    /**
     *  R declares a cyclic behavior that records each cycle and waits 100. An element made after R records its
     *  initialization. In each of two replications the element's initialization comes first, then the first cycle
     *  at 0.
     */
    @Test
    @DisplayName("a cyclic behavior starts at time 0 after every element has initialized, in every replication")
    fun cyclicBehaviorStartsAfterEveryElementInitialized() {
        val events = mutableListOf<String>()
        val m = RuleModel("CyclicStart")
        val r = ResourceWithQ(m, "R")
        r.cyclicBehavior {
            events.add("cycle@${m.time}")
            delay(100.0)
        }
        object : ModelElement(m, "InitializedAfterR") {
            override fun initialize() {
                events.add("element initialized")
            }
        }
        m.simulate(10.0, reps = 2)
        assertEquals(listOf("element initialized", "cycle@0.0", "element initialized", "cycle@0.0"), events)
    }

    /**
     *  R's behavior is off at the start. In replication 1 the model starts it by hand at 5, so it runs from 5. In
     *  replication 2 nothing starts it: the setting, not what replication 1 did, decides, and it does not run.
     */
    @Test
    @DisplayName("behaviorActiveAtStart false: a behavior started by hand in one replication is not started in the next")
    fun activeAtStartFalseDecidesEachReplication() {
        val seen = mutableListOf<String>()
        val m = RuleModel("ActiveAtStartEachRep")
        val r = ResourceWithQ(m, "R")
        r.cyclicBehavior {
            seen.add("rep ${m.model.currentReplicationNumber} cycle@${m.time}")
            delay(100.0)
        }
        r.behaviorActiveAtStart = false
        m.plan {
            at(5.0) { if (model.currentReplicationNumber == 1) r.startBehavior() }
            at(6.0) { seen.add("rep ${model.currentReplicationNumber} active=${r.isBehaviorActive}") }
        }
        m.simulate(20.0, reps = 2)
        assertEquals(listOf("rep 1 cycle@5.0", "rep 1 active=true", "rep 2 active=false"), seen)
    }

    /**
     *  R's cyclic behavior adds one to a counter it captures and waits 10, so each replication of length 15 runs two
     *  cycles, at 0 and 10. The counter is not reset between the two replications.
     */
    @Test
    @DisplayName("a cyclic behavior's captured state carries over between replications")
    fun cyclicCapturedStateCarriesOver() {
        val seen = mutableListOf<String>()
        var count = 0
        val m = RuleModel("CyclicCaptured")
        val r = ResourceWithQ(m, "R")
        r.cyclicBehavior {
            var local = 0
            local++
            count++
            seen.add("local=$local count=$count")
            delay(10.0)
        }
        m.simulate(15.0, reps = 2)
        assertEquals(listOf("local=1 count=1", "local=1 count=2", "local=1 count=3", "local=1 count=4"), seen)
    }

    /**
     *  R takes its capacity, 1, from a schedule. Its behavior, at 1, calls `goOffShift` and then asks for a capacity
     *  of 0 directly. Both are refused, and R stays on shift at capacity 1; the behavior goes on after the refusals.
     */
    @Test
    @DisplayName("on a resource with a capacity schedule, the behavior's goOffShift and its own capacity change are both refused")
    fun behaviorChangesRefusedUnderASchedule() {
        val m = RuleModel("BehaviorScheduleRefusals")
        val r = ResourceWithQ(m, "R")
        val schedule = CapacitySchedule(m, startTime = 0.0, autoStartOption = true, repeatable = false)
        schedule.addItem(capacity = 1, duration = 1000.0)
        r.useSchedule(schedule, CapacityChangeRule.IGNORE)
        r.behavior {
            delay(1.0)
            val off = runCatching { r.goOffShift() }
            m.log.add("goOffShift refused=${off.isFailure} off=${r.isOffShift} cap=${r.capacity}")
            val cut = runCatching { r.changeCapacity(r.CapacityChangeNotice(capacity = 0)) }
            m.log.add("changeCapacity refused=${cut.isFailure} cap=${r.capacity}")
            delay(1.0)
            m.log.add("behavior went on@${m.time}")
        }
        m.simulate(10.0)
        assertEquals(
            listOf("goOffShift refused=true off=false cap=1", "changeCapacity refused=true cap=1", "behavior went on@2.0"),
            m.log
        )
    }
}
