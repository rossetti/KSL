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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A rule of the entity guide's section on capacity schedules: under `CapacityChangeRule.WAIT`, a decrease takes
 *  the idle units at once and the busy ones as their jobs finish, and the item lasts its full duration from when
 *  it has all of them.
 */
class EntityCapacityScheduleRulesTest {

    /**
     *  R has 2 units and a schedule of three items: capacity 2 for 10, capacity 0 for 10, capacity 2 after. J holds
     *  one unit from 0 to 15; W asks for one at 11. At 10 the idle unit is taken at once (capacity 1); J's unit is
     *  taken when J releases at 15 (capacity 0), not given to W. The off item lasts 10 from 15, so the capacity is
     *  2 again at 25, and W is served then.
     */
    @Test
    @DisplayName("under WAIT a decrease takes the idle unit at once, the busy one at its release, and lasts its full duration from then")
    fun waitDecreaseTakesIdleThenBusyAndLastsFromWhenComplete() {
        val m = RuleModel("WaitSchedule")
        val r = ResourceWithQ(m, "R", capacity = 2)
        val schedule = CapacitySchedule(m, startTime = 0.0, autoStartOption = true, repeatable = false)
        schedule.addItem(capacity = 2, duration = 10.0)
        schedule.addItem(capacity = 0, duration = 10.0)
        schedule.addItem(capacity = 2, duration = 1000.0)
        r.useSchedule(schedule, CapacityChangeRule.WAIT)
        m.plan {
            actor("J") {
                val a = seize(r)
                delay(15.0)
                release(a)
            }
            actor("W") {
                delay(11.0)
                val a = seize(r)
                log.add("W got@$time")
                delay(0.5)
                release(a)
            }
            for (t in listOf(9.0, 12.0, 16.0, 24.0, 26.0)) at(t) { log.add("t$t cap=${r.capacity} busy=${r.numBusy}") }
        }
        m.simulate(100.0)
        assertEquals(
            listOf(
                "t9.0 cap=2 busy=1",
                "t12.0 cap=1 busy=1",
                "t16.0 cap=0 busy=0",
                "t24.0 cap=0 busy=0",
                "W got@25.0",
                "t26.0 cap=2 busy=0",
            ),
            m.log
        )
    }
}
