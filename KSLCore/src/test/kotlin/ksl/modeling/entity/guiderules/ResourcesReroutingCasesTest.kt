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

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceWithQ
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A rule of the resources tutorial's case 4, "Rerouting around a failure" (`ksl-resources-tutorial.md`):
 *  `a.restoredTrigger()` fires when all the units of the allocation `a` are back. Units back from a failure alone and
 *  from a preemption alone are checked elsewhere; this class checks units missing through a loan that a failure then
 *  holds up, so that the repair is not yet the moment they are all back.
 */
class ResourcesReroutingCasesTest {

    /**
     *  A (priority 9) holds `units` units of the machine from 0 for 10 of work. P preempts one unit at 2 for 5 of work,
     *  under RESUME. At 3 the machine fails, under RESUME, holding up P (and, with two units, A's other unit too); it is
     *  repaired at 6. P, held from 3 to 6, finishes at 10, and only then is A's lent unit back. S waits from 2.5 on A's
     *  restored trigger, with a limit of 40.
     */
    private fun lentThenFailed(units: Int): List<String> {
        val m = RuleModel("LentThenFailed")
        val machine = ResourceWithQ(m, "Machine", capacity = units)
        var held: Allocation? = null
        m.plan {
            held = null
            actor("A", priority = 9) {
                val a = seize(machine, units)
                held = a
                delay(10.0)
                release(a)
                log.add("A done@$time")
            }
            actor("P", priority = 1) {
                delay(2.0)
                val a = preempt(machine, 1, rule = InterruptRule.RESUME)
                delay(5.0)
                release(a)
                log.add("P done@$time")
            }
            actor("S") {
                delay(2.5)
                val r = waitForFirst(held!!.restoredTrigger(), timeout = 40.0)
                log.add("S ${if (r.timedOut) "timedOut" else "restored"}@$time")
            }
            at(3.0) { machine.fail(InterruptRule.RESUME) }
            at(6.0) { log.add("repair@$time"); machine.repair() }
        }
        m.simulate(60.0)
        return m.log
    }

    @Test
    @DisplayName("one unit lent, and the borrower held by a failure: the restored trigger fires when the loan returns, not at the repair")
    fun oneUnitLentThenFailed() {
        // A, lent from 2 to 10, goes on with 8 left and ends at 18
        assertEquals(listOf("repair@6.0", "P done@10.0", "S restored@10.0", "A done@18.0"), lentThenFailed(units = 1))
    }

    @Test
    @DisplayName("one unit lent and the other failed: the repair returns one, and the restored trigger fires only when the loan returns")
    fun unitsMissingForBothReasons() {
        // A's delay is held from 2 (its lent unit) to 10, when the loan returns: it goes on with 8 left and ends at 18
        assertEquals(listOf("repair@6.0", "P done@10.0", "S restored@10.0", "A done@18.0"), lentThenFailed(units = 2))
    }
}
