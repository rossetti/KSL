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
 *  Cases of the entity guide's section on preemption: a holder of equal or higher priority is never preempted,
 *  under `RESTART` and `FINISH` as under `RESUME`, nor is a borrower of equal priority; a holder held by a `RESTART`
 *  failure does not resume ahead of a `RESTART` preemptor waiting at the repair; and a preemptor needing more than
 *  the capacity the resource is heading to holds none and blocks no one, under `RESUME` and under `FINISH`, and is
 *  served, or reserves again, when the capacity returns.
 */
class EntityPreemptionCasesTest {

    private fun RuleModel.job(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, n: Int = 1) =
        actor(tag, priority) {
            delay(from)
            val a = seize(r, n)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

    private fun RuleModel.urgent(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, n: Int, rule: InterruptRule) =
        actor(tag, priority) {
            delay(from)
            val a = preempt(r, n, rule = rule)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

    /**
     *  R has one unit. L holds it from 0 to 10 at priority 1 (higher than P's) or 3 (equal to P's). At 2, P (priority
     *  3) preempts it under `RESTART` or `FINISH`. Nothing is taken or reserved from L: L finishes at 10 and P is
     *  served then.
     */
    @Test
    @DisplayName("a holder of equal or higher priority is never preempted, under RESTART and under FINISH")
    fun equalOrHigherNeverPreemptedUnderRestartAndFinish() {
        for (rule in listOf(InterruptRule.RESTART, InterruptRule.FINISH)) {
            for (holderPriority in listOf(1, 3)) {
                val m = RuleModel("NoVictim")
                val r = ResourceWithQ(m, "R")
                var preemptions = -1
                m.plan {
                    job("L", r, 0.0, 10.0, priority = holderPriority)
                    urgent("P", r, 2.0, 1.0, priority = 3, n = 1, rule = rule)
                    at(20.0) { preemptions = r.numTimesPreempted }
                }
                m.simulate(50.0)
                assertEquals(
                    listOf("L got@0.0", "L done@10.0", "P got@10.0", "P done@11.0"),
                    m.log,
                    "rule $rule, holder priority $holderPriority"
                )
                assertEquals(0, preemptions, "rule $rule, holder priority $holderPriority")
            }
        }
    }

    /**
     *  R has one unit. L (priority 9) holds it from 0 to 10. At 1, B (priority 3) preempts it for 5, so B holds the
     *  unit as a borrower. At 2, P (priority 3) preempts: B, its equal, is not preempted, so P waits. At B's release,
     *  6, the loan would go back to L, but P outranks L and takes it; L, held from 1 with 9 left, resumes at P's
     *  release, 7, and ends at 16.
     */
    @Test
    @DisplayName("a borrower of equal priority is never preempted")
    fun borrowerOfEqualPriorityNotPreempted() {
        val m = RuleModel("EqualBorrower")
        val r = ResourceWithQ(m, "R")
        m.plan {
            job("L", r, 0.0, 10.0, priority = 9)
            urgent("B", r, 1.0, 5.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
            urgent("P", r, 2.0, 1.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
        }
        m.simulate(50.0)
        assertEquals(
            listOf("L got@0.0", "B got@1.0", "B done@6.0", "P got@6.0", "P done@7.0", "L done@16.0"),
            m.log
        )
    }

    /**
     *  R has one unit. L (priority 9) holds it from 0 to 10. At 1 R fails under `RESTART`, holding L's work. At 2, P
     *  (priority 1) preempts under `RESTART` and waits, as R is failed. At the repair, 4, P takes the unit before L
     *  resumes: P works 4 to 5, and L then starts its 10 again in full, ending at 15.
     */
    @Test
    @DisplayName("at a repair, a holder held by a RESTART failure does not resume ahead of a waiting RESTART preemptor that outranks it")
    fun restartFailureAndRestartPreemptorAtARepair() {
        val m = RuleModel("RestartAtRepair")
        val r = ResourceWithQ(m, "R")
        m.plan {
            job("L", r, 0.0, 10.0, priority = 9)
            at(1.0) { r.fail(InterruptRule.RESTART) }
            urgent("P", r, 2.0, 1.0, priority = 1, n = 1, rule = InterruptRule.RESTART)
            at(4.0) { r.repair() }
        }
        m.simulate(50.0)
        assertEquals(listOf("L got@0.0", "P got@4.0", "P done@5.0", "L done@15.0"), m.log)
    }

    /**
     *  R has one unit. L (priority 9) holds it from 0 to 10. At 1, B (priority 3) preempts it under `RESUME` for 5.
     *  At 2, P (priority 1) preempts under `FINISH`: B's unit is in service, so P waits for it. At B's release, 6,
     *  the loan ends; P outranks L, so L does not resume ahead of P: P takes the unit at 6 and works to 7.
     */
    @Test
    @DisplayName("at the end of a loan, the holder does not resume ahead of a waiting FINISH preemptor that outranks it")
    fun loanEndWithAWaitingFinishPreemptor() {
        val m = RuleModel("LoanEndFinish")
        val r = ResourceWithQ(m, "R")
        m.plan {
            job("L", r, 0.0, 10.0, priority = 9)
            urgent("B", r, 1.0, 5.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
            urgent("P", r, 2.0, 1.0, priority = 1, n = 1, rule = InterruptRule.FINISH)
        }
        m.simulate(50.0)
        assertEquals("P got@6.0", m.log.first { it.startsWith("P got") }, m.log.toString())
    }

    /**
     *  R has 2 units. L (priority 9) holds 1 from 0 for 20. At 1, B (priority 3) preempts 2 under `RESUME` for 5,
     *  taking the free unit and L's. At 2, F (priority 1) preempts 2 under `FINISH` and reserves B's two. At 6 B
     *  releases: one unit is freed and one goes back from the loan to L, which F outranks; together they complete
     *  F's reservation. F takes both at 6 and works to 7, and L, held from 1 to 7, ends at 26.
     */
    @Test
    @DisplayName("a release that frees units and ends a loan: the freed and the returning units together complete a FINISH reservation")
    fun freedAndReturningUnitsCompleteAReservation() {
        val m = RuleModel("FreedAndReturning")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            job("L", r, 0.0, 20.0, priority = 9)
            urgent("B", r, 1.0, 5.0, priority = 3, n = 2, rule = InterruptRule.RESUME)
            urgent("F", r, 2.0, 1.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
        }
        m.simulate(100.0)
        assertEquals(listOf("L got@0.0", "B got@1.0", "B done@6.0", "F got@6.0", "F done@7.0", "L done@26.0"), m.log)
    }

    /**
     *  A waiting preemptor takes units going back from an ending loan only when it can then take all it needs. R has
     *  2 units. L1 (priority 9) holds 1 from 0 for 20, L2 (priority 9) 1 from 0.5 for 20. At 1, B (priority 3)
     *  preempts 1 under `RESUME` for 5, taking L2's. At 2, F (priority 1) preempts 2 under `FINISH` and reserves L1's
     *  and B's. At 6 B releases: its unit is one of the two F needs, so it goes back to L2, which resumes (ending at
     *  25.5, held from 1 to 6). F collects L1's unit at 20 and L2's at 25.5.
     */
    @Test
    @DisplayName("units going back from a loan that would only partly complete a FINISH reservation go back to their holder")
    fun returningUnitsPartlyCompletingAReservationGoBack() {
        val m = RuleModel("PartlyCompleting")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            job("L1", r, 0.0, 20.0, priority = 9)
            job("L2", r, 0.5, 20.0, priority = 9)
            urgent("B", r, 1.0, 5.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
            urgent("F", r, 2.0, 1.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
        }
        m.simulate(100.0)
        assertEquals(
            listOf("L1 got@0.0", "L2 got@0.5", "B got@1.0", "B done@6.0", "L1 done@20.0", "L2 done@25.5", "F got@25.5", "F done@26.5"),
            m.log
        )
    }

    /**
     *  R has 3 units. At 1 its capacity is cut to 1. At 2, P (priority 1) preempts 2 units under `RESUME`: more than
     *  the 1 the resource is heading to, so it holds none. At 3, N (priority 5) seizes 1 and takes the free unit at
     *  once: P blocks no one. At 5 the capacity returns to 2, and P takes the free unit and N's: P works 5 to 6, and
     *  N, held for 1, ends at 21.
     */
    @Test
    @DisplayName("under RESUME, a preemptor needing more than the capacity holds none, blocks no one, and is served when the capacity returns")
    fun resumePreemptorBeyondTheCapacityBlocksNoOne() {
        val m = RuleModel("BeyondCapacityResume")
        val r = ResourceWithQ(m, "R", capacity = 3)
        m.plan {
            at(1.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 1)) }
            urgent("P", r, 2.0, 1.0, priority = 1, n = 2, rule = InterruptRule.RESUME)
            job("N", r, 3.0, 17.0, priority = 5)
            at(5.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
        }
        m.simulate(50.0)
        assertEquals(listOf("N got@3.0", "P got@5.0", "P done@6.0", "N done@21.0"), m.log)
    }

    /**
     *  R has 2 units. A (priority 9) holds one from 0 to 10. At 1, P (priority 1) preempts 2 under `FINISH`: it is
     *  promised the free unit and waits for A's. At 2 the capacity is cut to 1: P now needs more than the capacity
     *  and holds none. N (priority 5) asks for one at 3; at A's release, 10, N takes the unit, so P blocks no one. At
     *  15 the capacity returns to 2: P reserves again, promised the free unit and waiting for N's, which N releases
     *  at 30; P then works 30 to 31.
     */
    @Test
    @DisplayName("under FINISH, a preemptor needing more than the capacity holds none, blocks no one, and reserves again when the capacity returns")
    fun finishPreemptorBeyondTheCapacityReservesAgain() {
        val m = RuleModel("BeyondCapacityFinish")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            job("A", r, 0.0, 10.0, priority = 9)
            urgent("P", r, 1.0, 1.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
            at(2.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 1)) }
            job("N", r, 3.0, 20.0, priority = 5)
            at(15.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
        }
        m.simulate(50.0)
        assertEquals(
            listOf("A got@0.0", "A done@10.0", "N got@10.0", "N done@30.0", "P got@30.0", "P done@31.0"),
            m.log
        )
    }
}
