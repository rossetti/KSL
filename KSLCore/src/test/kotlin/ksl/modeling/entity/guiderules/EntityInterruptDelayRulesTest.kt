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
import ksl.modeling.entity.ResourceWithQ
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A rule of the entity guide's section on interrupting a delay: a delay held for several reasons goes on when the
 *  last ends, and a process interrupt that ends last decides alone. So an `interruptDelayAndContinue` that outlasts
 *  a failure under `RESTART` goes on with the time left when the interrupt began, not in full.
 */
class EntityInterruptDelayRulesTest {

    /**
     *  M holds R and is in a delay of 10 from 0. At 2, I interrupts the delay with `interruptDelayAndContinue` for 6,
     *  so to 8, with 8 left. At 3 R fails under RESTART and at 5 it is repaired: the failure ends first. The interrupt
     *  ends last, at 8, and M goes on with the 8 left, ending at 16 (in full it would end at 18).
     */
    @Test
    @DisplayName("an interruptDelayAndContinue outlasting a RESTART failure goes on with the time left, not in full")
    fun continueInterruptOutlastingRestartFailure() {
        val m = RuleModel("InterruptOutlastsRestart")
        val r = ResourceWithQ(m, "R", capacity = 1)
        m.plan {
            val machine = actor("M") {
                val a = seize(r)
                delay(10.0, suspensionName = "work")
                release(a)
                log.add("M done@$time")
            }
            actor("I") {
                delay(2.0)
                interruptDelayAndContinue(machine.script, "work", 6.0)
                log.add("I done@$time")
            }
            at(3.0) { r.fail(InterruptRule.RESTART) }
            at(5.0) { r.repair() }
        }
        m.simulate(50.0)
        assertEquals(listOf("I done@8.0", "M done@16.0"), m.log)
    }
}
