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
 *  Cases of the entity guide's section on holding a resource across work that does not use it, with real failures
 *  and preemptions as the cause: by default a delay depends on every resource its entity holds, whichever of them
 *  loses its units, by a failure or a preemption, under `RESUME` or `RESTART`; with `uses` naming one allocation,
 *  only it can hold the delay; with `uses` empty, neither a failure nor a preemption can.
 */
class EntityHoldResourceCasesTest {

    private enum class Source { FAILURE, PREEMPTION }

    /**
     *  W (priority 9) seizes R1 and R2 at 0 and works 10 with `uses` as [uses] gives it from the two allocations.
     *  At 2 R2 (when `takeSecond`) or else R1 loses W's unit by [source] under [rule]: a failure repaired at 5, or a preemption
     *  by P (priority 1) that holds the unit for 3, until 5. Returns W's log.
     */
    private fun scenario(
        source: Source,
        rule: InterruptRule,
        takeSecond: Boolean,
        uses: ((Allocation, Allocation) -> List<Allocation>)?
    ): List<String> {
        val m = RuleModel("HoldAcrossWork")
        val r1 = ResourceWithQ(m, "R1")
        val r2 = ResourceWithQ(m, "R2")
        val taken = if (takeSecond) r2 else r1
        m.plan {
            actor("W", priority = 9) {
                val a = seize(r1)
                val b = seize(r2)
                if (uses == null) delay(10.0) else delay(10.0, uses = uses(a, b))
                log.add("W done@$time")
                release(a)
                release(b)
            }
            when (source) {
                Source.FAILURE -> {
                    at(2.0) { taken.fail(rule) }
                    at(5.0) { taken.repair() }
                }
                Source.PREEMPTION -> actor("P", priority = 1) {
                    delay(2.0)
                    val p = preempt(taken, rule = rule)
                    delay(3.0)
                    release(p)
                }
            }
        }
        m.simulate(50.0)
        return m.log
    }

    /**
     *  With a plain delay, the units of either resource going missing from 2 to 5 hold W's work: under `RESUME` it
     *  goes on at 5 with 8 left, ending at 13; under `RESTART` it starts again at 5, ending at 15. The same for a
     *  failure and a preemption, and whichever resource it is.
     */
    @Test
    @DisplayName("a plain delay is held by either held resource losing units, by a failure or a preemption, under RESUME and RESTART")
    fun plainDelayDependsOnEveryResourceForEachCause() {
        for (source in Source.entries) for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) for (second in listOf(false, true)) {
            val expected = if (rule == InterruptRule.RESUME) "W done@13.0" else "W done@15.0"
            assertEquals(listOf(expected), scenario(source, rule, second, null), "$source, $rule, second resource $second")
        }
    }

    /**
     *  W's work names only R1's allocation. R2 losing its unit, by a failure or a preemption, does not hold it, and it
     *  ends at 10; R1 losing its unit holds it, and it ends at 13 under `RESUME`.
     */
    @Test
    @DisplayName("with uses naming one allocation, only it holds the delay, for a failure and for a preemption")
    fun usesNamingOneForEachCause() {
        for (source in Source.entries) {
            assertEquals(listOf("W done@10.0"), scenario(source, InterruptRule.RESUME, true) { a, _ -> listOf(a) }, "$source of R2")
            assertEquals(listOf("W done@13.0"), scenario(source, InterruptRule.RESUME, false) { a, _ -> listOf(a) }, "$source of R1")
        }
    }

    /** W's work has `uses` empty: neither resource losing its unit, by a failure or a preemption, holds it; it ends at 10. */
    @Test
    @DisplayName("with uses empty, neither a failure nor a preemption of either resource holds the delay")
    fun usesEmptyForEachCause() {
        for (source in Source.entries) for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) for (second in listOf(false, true)) {
            assertEquals(
                listOf("W done@10.0"),
                scenario(source, rule, second) { _, _ -> emptyList() },
                "$source, $rule, second resource $second"
            )
        }
    }
}
