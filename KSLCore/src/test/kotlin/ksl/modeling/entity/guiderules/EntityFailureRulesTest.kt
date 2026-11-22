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

import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A rule of the entity guide's section on failing and repairing a resource: `FINISH` is the default interrupt
 *  rule. With no rule named, a failure lets the holders complete their work, and the units they release are kept
 *  from the queue until the repair.
 *
 *  In each case R has one unit. H holds it from 0 to 10; W asks for it at 1. R fails at 2 and is repaired at 20.
 *  Under FINISH, H is not held and releases at 10, and W is served only at the repair, at 20.
 */
class EntityFailureRulesTest {

    private fun run(configure: RuleModel.(ResourceWithQ) -> Unit, plan: RuleModel.(ResourceWithQ) -> Unit = {}): List<String> {
        val m = RuleModel("FailureDefault")
        val r = ResourceWithQ(m, "R", capacity = 1)
        m.configure(r)
        m.plan {
            actor("H") {
                val a = seize(r)
                delay(10.0)
                release(a)
                log.add("H done@$time")
            }
            actor("W") {
                delay(1.0)
                val a = seize(r)
                log.add("W got@$time")
                delay(1.0)
                release(a)
            }
            plan(r)
        }
        return m.simulate(21.5).log
    }

    @Test
    @DisplayName("a resource's interrupt rule is FINISH unless set")
    fun interruptRuleDefaultsToFinish() {
        val m = RuleModel("FailureDefaultRule")
        val r = ResourceWithQ(m, "R")
        assertEquals(InterruptRule.FINISH, r.interruptRule)
    }

    @Test
    @DisplayName("fail() with no rule: the holder finishes, and its released unit waits for the repair")
    fun failByHandDefaultsToFinish() {
        val log = run(configure = {}) { r ->
            at(2.0) { r.fail() }
            at(20.0) { r.repair() }
        }
        assertEquals(listOf("H done@10.0", "W got@20.0"), log)
    }

    @Test
    @DisplayName("failures(...) with no rule: the holder finishes, and its released unit waits for the repair")
    fun declaredFailuresDefaultToFinish() {
        val log = run(configure = { r ->
            r.failures(ConstantRV(2.0), ConstantRV(18.0), ResourceFailureBasis.CALENDAR_TIME)
        })
        assertEquals(listOf("H done@10.0", "W got@20.0"), log)
    }
}
