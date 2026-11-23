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
 *  Cases of the entity guide's section on interrupting a delay, where a delay is held for several reasons at once:
 *  if any reason asked for a restart it starts again in full when the last ends, whether the restart came from a
 *  failure or a preemption and whatever the other reason was; otherwise it goes on with the time it had left.
 *
 *  In each case J (priority 9) seizes M1 and M2 at 0 and works 10 in a delay named "work".
 */
class EntityInterruptDelayCasesTest {

    private sealed interface Reason {
        val from: Double
        val to: Double
    }

    /** [resource] fails at [from] under [rule] and is repaired at [to]. */
    private data class Failure(val resource: Int, val rule: InterruptRule, override val from: Double, override val to: Double) : Reason

    /** P (priority 1) preempts [resource] at [from] under [rule] and releases it at [to]. */
    private data class Preemption(val resource: Int, val rule: InterruptRule, override val from: Double, override val to: Double) : Reason

    /** A process interrupt of the continue form from [from] to [to]. */
    private data class ContinueInterrupt(override val from: Double, override val to: Double) : Reason

    private fun doneAt(vararg reasons: Reason): List<String> {
        val m = RuleModel("SeveralReasons")
        val res = listOf(ResourceWithQ(m, "M1"), ResourceWithQ(m, "M2"))
        m.plan {
            val j = actor("J", priority = 9) {
                val a = seize(res[0])
                val b = seize(res[1])
                delay(10.0, suspensionName = "work")
                log.add("J done@$time")
                release(a)
                release(b)
            }
            for ((i, reason) in reasons.withIndex()) {
                when (reason) {
                    is Failure -> {
                        at(reason.from) { res[reason.resource].fail(reason.rule) }
                        at(reason.to) { res[reason.resource].repair() }
                    }
                    is Preemption -> actor("P$i", priority = 1) {
                        delay(reason.from)
                        val p = preempt(res[reason.resource], rule = reason.rule)
                        delay(reason.to - reason.from)
                        release(p)
                    }
                    is ContinueInterrupt -> actor("I$i") {
                        delay(reason.from)
                        interruptDelayAndContinue(j.script, "work", reason.to - reason.from)
                    }
                }
            }
        }
        m.simulate(50.0)
        return m.log
    }

    /** A `RESTART` failure of M1 from 2 to 4 and a `RESUME` preemption of M2 from 3 to 6: the work starts in full at 6, ending at 16. */
    @Test
    @DisplayName("a RESTART failure ending first and a RESUME preemption ending last: the delay starts again in full")
    fun restartFailureThenResumePreemption() {
        assertEquals(
            listOf("J done@16.0"),
            doneAt(Failure(0, InterruptRule.RESTART, 2.0, 4.0), Preemption(1, InterruptRule.RESUME, 3.0, 6.0))
        )
    }

    /** A `RESTART` preemption of M1 from 2 to 4 and a `RESUME` failure of M2 from 3 to 6: the work starts in full at 6, ending at 16. */
    @Test
    @DisplayName("a RESTART preemption ending first and a RESUME failure ending last: the delay starts again in full")
    fun restartPreemptionThenResumeFailure() {
        assertEquals(
            listOf("J done@16.0"),
            doneAt(Preemption(0, InterruptRule.RESTART, 2.0, 4.0), Failure(1, InterruptRule.RESUME, 3.0, 6.0))
        )
    }

    /**
     *  A `RESUME` failure of M1 from 2 to 4 and a `RESUME` preemption of M2 from 3 to 6: no reason asked for a
     *  restart, so the work, held at 2 with 8 left, goes on at 6 and ends at 14. The same with the causes swapped.
     */
    @Test
    @DisplayName("a failure and a preemption, neither asking for a restart: the delay goes on with the time it had left")
    fun noRestartFailureAndPreemption() {
        assertEquals(
            listOf("J done@14.0"),
            doneAt(Failure(0, InterruptRule.RESUME, 2.0, 4.0), Preemption(1, InterruptRule.RESUME, 3.0, 6.0))
        )
        assertEquals(
            listOf("J done@14.0"),
            doneAt(Preemption(0, InterruptRule.RESUME, 2.0, 4.0), Failure(1, InterruptRule.RESUME, 3.0, 6.0))
        )
    }

    /**
     *  A process interrupt of the continue form from 2 to 4 and a `RESTART` failure of M1 from 3 to 6: the failure
     *  asked for a restart and ends last, so the work starts in full at 6, ending at 16. The same with a `RESTART`
     *  preemption in place of the failure.
     */
    @Test
    @DisplayName("a continue-form process interrupt ending first and a RESTART failure or preemption ending last: the delay starts again in full")
    fun continueInterruptThenRestartResourceReason() {
        assertEquals(
            listOf("J done@16.0"),
            doneAt(ContinueInterrupt(2.0, 4.0), Failure(0, InterruptRule.RESTART, 3.0, 6.0))
        )
        assertEquals(
            listOf("J done@16.0"),
            doneAt(ContinueInterrupt(2.0, 4.0), Preemption(0, InterruptRule.RESTART, 3.0, 6.0))
        )
    }
}
