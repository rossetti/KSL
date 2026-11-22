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
 *  A rule of the entity guide's section on reneging: a capacity increase that serves a waiting `seizeOrTimeout`
 *  at the instant its time runs out wins over the timer, at the default priorities.
 *
 *  In each case R has one unit, held by H from 0 to 100. W calls `seizeOrTimeout` at 0 with a timeout of 5. At 5
 *  the capacity rises to 2, which can serve W: W seizes at 5 and does not time out.
 */
class EntityRenegeRulesTest {

    private fun run(configure: RuleModel.(ResourceWithQ) -> Unit, plan: RuleModel.(ResourceWithQ) -> Unit = {}): List<String> {
        val m = RuleModel("RenegeIncrease")
        val r = ResourceWithQ(m, "R", capacity = 1)
        m.configure(r)
        m.plan {
            actor("H") {
                val a = seize(r)
                delay(100.0)
                release(a)
            }
            actor("W") {
                val res = seizeOrTimeout(r, timeout = 5.0)
                if (res.timedOut) log.add("W timed out@$time") else {
                    log.add("W seized@$time")
                    release(res.value)
                }
            }
            plan(r)
        }
        return m.simulate(200.0).log
    }

    @Test
    @DisplayName("a capacity change asked for at the deadline, at the default priority: the increase wins over the timer")
    fun changeCapacityAtTheDeadlineWins() {
        val log = run(configure = {}) { r ->
            at(5.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
        }
        assertEquals(listOf("W seized@5.0"), log)
    }

    @Test
    @DisplayName("a capacity schedule's item raising the capacity at the deadline: the increase wins over the timer")
    fun scheduleIncreaseAtTheDeadlineWins() {
        val log = run(configure = { r ->
            val schedule = CapacitySchedule(this, startTime = 0.0, autoStartOption = true, repeatable = false)
            schedule.addItem(capacity = 1, duration = 5.0)
            schedule.addItem(capacity = 2, duration = 1000.0)
            r.useSchedule(schedule, CapacityChangeRule.IGNORE)
        })
        assertEquals(listOf("W seized@5.0"), log)
    }
}
