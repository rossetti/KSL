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
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Who is served when units come free or come back: one order for every claimant (waiting preemptors, FINISH
 *  reservations, queued requests), the first that can be served taking what it can, the resumes at the priority of
 *  the event that made the units available, and a failure due at a release applied before a loan's units go back.
 */
class OfferOrderTest {

    /**
     *  R has 2 units. L (priority 5) holds 1 from 0. B (priority 0) preempts 2 under `RESUME` at 1, taking the free
     *  unit and L's, and releases at 3 with release priority 50000. S (priority 9) seizes 1 at 2 and waits. An event at
     *  3 with priority 20000 records X. S, served by B's release, resumes at the release's priority: after X.
     */
    @Test
    @DisplayName("an offer made as a release ends a loan schedules its resumes at the release's priority")
    fun resumesAtTheReleasePriority() {
        val m = RuleModel("ReleasePriority")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            actor("L", 5) { val a = seize(r); delay(100.0); release(a) }
            actor("B", 0) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); delay(2.0); release(a, releasePriority = 50000) }
            actor("S", 9) { delay(2.0); val a = seize(r); log.add("S got@$time"); delay(1.0); release(a) }
            at(3.0, priority = 20000) { log.add("X@$time") }
        }
        m.simulate(50.0)
        assertEquals(listOf("X@3.0", "S got@3.0"), m.log.take(2))
    }

    /**
     *  R has 3 units and a RANKED queue. L1 (priority 5) holds 2 from 0. B (priority 0) preempts 3 under `RESUME` at
     *  0.1 (the free unit and L1's 2) and releases at 3. W (priority 2, `FINISH`, 2 units) waits from 1. N (priority
     *  1) and L2 (priority 7) wait from 1.5 and 1.6 and take the 2 units a capacity increase to 5 brings at 2. N2
     *  (priority 1) seizes 1 at 2.5 and waits. At 3 the freed unit goes to N2, more urgent than W; W then takes 2 of
     *  L1's returning units (it outranks L1, and they are all it needs), and L1 stays held.
     */
    @Test
    @DisplayName("a FINISH waiter that gave way to a more urgent request takes the units going back from a loan")
    fun finishWaiterTakesReturningUnitsAfterGivingWay() {
        val m = RuleModel("LateFinish")
        val q = RequestQ(m, "RQ", Queue.Discipline.RANKED)
        val r = ResourceWithQ(m, "R", capacity = 3, queue = q)
        m.plan {
            actor("L1", 5) { val a = seize(r, 2); delay(100.0); release(a) }
            actor("B", 0) { delay(0.1); val a = preempt(r, 3, rule = InterruptRule.RESUME); delay(2.9); release(a) }
            actor("W", 2) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); log.add("W got@$time"); delay(1.0); release(a) }
            actor("N", 1) { delay(1.5); val a = seize(r); log.add("N got@$time"); delay(50.0); release(a) }
            actor("L2", 7) { delay(1.6); val a = seize(r); log.add("L2 got@$time"); delay(50.0); release(a) }
            at(2.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 5)) }
            actor("N2", 1) { delay(2.5); val a = seize(r); log.add("N2 got@$time"); delay(50.0); release(a) }
        }
        m.simulate(200.0)
        assertTrue("N2 got@3.0" in m.log, m.log.toString())
        assertTrue("W got@3.0" in m.log, m.log.toString())
    }

    /**
     *  R has 3 units. H (priority 6) holds 2 from 0, L (priority 8) holds 1 from 0. B (priority 0) preempts 1 under
     *  `RESUME` at 0.5, taking L's, and releases at 3, freeing nothing. V2 (priority 2, `FINISH`, 1 unit) reserves
     *  one of H's units at 2. V1 (priority 1, `FINISH`, 3 units) waits from 2.5. At 3 L's unit goes back: V1, more
     *  urgent, cannot use it to complete what it needs, so it holds nothing back, and V2 completes from it; L stays
     *  held.
     */
    @Test
    @DisplayName("a more urgent claimant that cannot use units going back from a loan does not keep them from one that can")
    fun shortReservationDoesNotBlock() {
        val m = RuleModel("ShortReservation")
        val r = ResourceWithQ(m, "R", capacity = 3)
        m.plan {
            actor("H", 6) { val a = seize(r, 2); delay(100.0); release(a) }
            actor("L", 8) { val a = seize(r); delay(100.0); release(a) }
            actor("B", 0) { delay(0.5); val a = preempt(r, 1, rule = InterruptRule.RESUME); delay(2.5); release(a) }
            actor("V2", 2) { delay(2.0); val a = preempt(r, 1, rule = InterruptRule.FINISH); log.add("V2 got@$time"); delay(1.0); release(a) }
            actor("V1", 1) { delay(2.5); val a = preempt(r, 3, rule = InterruptRule.FINISH); log.add("V1 got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        assertEquals("V2 got@3.0", m.log.first { it.startsWith("V2 got") }, m.log.toString())
    }

    /**
     *  R has 2 units. H (priority 8) and X (priority 0) hold 1 each from 0; X releases at 3. V2 (priority 3, `FINISH`,
     *  1 unit) reserves H's unit at 1. N (priority 1) seizes 1 at 1.5 and waits; W (priority 2, `RESUME`, 2 units)
     *  waits from 2. At 3 the freed unit goes to N, the most urgent claimant, ahead of W and of V2's reservation (a
     *  reservation is ranked against queued requests by priority). W cannot then take 2, and V2 still waits for H.
     */
    @Test
    @DisplayName("freed units go to the most urgent claimant, whether a queued request, a waiting preemptor or a reservation")
    fun mostUrgentClaimantFirst() {
        val m = RuleModel("MostUrgent")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            actor("H", 8) { val a = seize(r); delay(100.0); release(a) }
            actor("X", 0) { val a = seize(r); delay(3.0); release(a) }
            actor("V2", 3) { delay(1.0); val a = preempt(r, 1, rule = InterruptRule.FINISH); log.add("V2 got@$time"); delay(1.0); release(a) }
            actor("N", 1) { delay(1.5); val a = seize(r); log.add("N got@$time"); delay(10.0); release(a) }
            actor("W", 2) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("W got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        assertTrue("N got@3.0" in m.log, m.log.toString())
        assertFalse("W got@3.0" in m.log, m.log.toString())
        assertFalse("V2 got@3.0" in m.log, m.log.toString())
    }

    /**
     *  R has 2 units, `RESUME`, and fails at every release (`USES`, 1), repaired after 5. L (priority 5) holds 1 from
     *  0. B (priority 0) preempts 2 at 1, taking the free unit and L's, and releases at 3: the failure is due at that
     *  release. L's unit goes back down in the failure, so L stays held until the repair at 8: one interruption, 7 long.
     */
    @Test
    @DisplayName("a failure due at a release that ends a loan: the lender's unit goes back down, and it is interrupted once")
    fun failureAtTheReleaseInterruptsTheLenderOnce() {
        val m = RuleModel("FailureAtRelease")
        val r = ResourceWithQ(m, "R", capacity = 2)
        r.interruptRule = InterruptRule.RESUME
        r.failures(ConstantRV(1.0), ConstantRV(5.0), ResourceFailureBasis.USES, InterruptRule.RESUME)
        var held: Allocation? = null
        m.plan {
            actor("L", 5) { val a = seize(r); held = a; delay(100.0); release(a) }
            actor("B", 0) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); delay(2.0); release(a) }
        }
        m.simulate(50.0)
        assertEquals(1, held!!.timesInterrupted)
        assertEquals(7.0, held!!.timeInterrupted, 1e-9)
    }

    /**
     *  R has 4 units, `RESUME`. A and B (priority 9) hold 1 each, H (priority 5) holds 2 until 10.2. M waits for the
     *  first of B's interruption and a seize trigger on R. F (priority 1, `FINISH`) reserves 1 at 1; W (priority 2)
     *  preempts 3 at 2 and W2 (priority 3) preempts 1 at 3, both waiting while F collects. At H's release F completes
     *  from a free unit and W takes the other with B's and A's. Taking B's unit wakes M, whose seize trigger leaves R's
     *  queue; that asks for an offer while W is still being given its units. The offer is made after W has them all:
     *  no unit is lent twice, and W2, outranked by every holder left, waits for F's release.
     */
    @Test
    @DisplayName("an offer asked for while a claimant is being served is made once it has all its units")
    fun offerAskedForDuringAServeWaitsForIt() {
        val m = RuleModel("OfferDuringServe")
        val r = ResourceWithQ(m, "R", capacity = 4)
        r.interruptRule = InterruptRule.RESUME
        var bAllocation: Allocation? = null
        m.plan {
            actor("A", 9) { val a = seize(r); delay(100.0); release(a) }
            actor("B", 9) { delay(0.1); val a = seize(r); bAllocation = a; delay(100.0); release(a) }
            actor("H", 5) { delay(0.2); val a = seize(r, 2); delay(10.0); release(a) }
            actor("M", 9) {
                delay(0.5)
                waitForFirst(bAllocation!!.interruptedTrigger(), r.seizeTrigger(), timeout = 1000.0)
                log.add("M woke@$time")
            }
            actor("F", 1) { delay(1.0); val a = preempt(r, 1, rule = InterruptRule.FINISH); log.add("F got@$time"); delay(1.0); release(a) }
            actor("W", 2) { delay(2.0); val a = preempt(r, 3, rule = InterruptRule.RESUME); log.add("W got@$time"); delay(5.0); release(a) }
            actor("W2", 3) { delay(3.0); val a = preempt(r, 1, rule = InterruptRule.RESUME); log.add("W2 got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        assertTrue("F got@10.2" in m.log, m.log.toString())
        assertTrue("W got@10.2" in m.log, m.log.toString())
        assertTrue("M woke@10.2" in m.log, m.log.toString())
        assertTrue("W2 got@11.2" in m.log, m.log.toString())
    }

    /**
     *  R has 2 units. L and X (priority 9) hold 1 each; B (priority 4, `RESUME`) preempts X's unit at 1 and releases at
     *  4. F (priority 2, `FINISH`) reserves 2 at 2 and is still short at 4. W (priority 3, `RESUME`) waits for 1 from
     *  3. At B's release X's unit goes back; F cannot complete with it, and W outranks X and needs only that unit, so
     *  W takes it: a collecting reservation keeps a less urgent preemptor from units in service, not from units going
     *  back from a loan.
     */
    @Test
    @DisplayName("a RESUME preemptor takes units going back from a loan that a short, more urgent reservation cannot use")
    fun resumePreemptorTakesReturningUnitsAShortReservationCannotUse() {
        val m = RuleModel("ReturningPastAShortReservation")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            actor("L", 9) { val a = seize(r); delay(50.0); release(a) }
            actor("X", 9) { delay(0.5); val a = seize(r); delay(50.0); release(a) }
            actor("B", 4) { delay(1.0); val a = preempt(r, 1, rule = InterruptRule.RESUME); delay(3.0); release(a) }
            actor("F", 2) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); log.add("F got@$time"); delay(1.0); release(a) }
            actor("W", 3) { delay(3.0); val a = preempt(r, 1, rule = InterruptRule.RESUME); log.add("W got@$time"); delay(1.0); release(a) }
        }
        m.simulate(200.0)
        assertTrue("W got@4.0" in m.log, m.log.toString())
        assertTrue(m.log.any { it.startsWith("F got@") }, m.log.toString())
    }

    /**
     *  R has 3 units, `RESUME`. A and B (priority 9) and X (priority 1) hold 1 each. M waits for the first of B's
     *  interruption and a seize trigger on R. W (priority 1) preempts 3 at 1 and waits: it does not outrank X. N
     *  (priority 3) preempts 2 at 2 and takes B's and A's units on arrival. Taking B's unit wakes M, whose seize trigger
     *  leaves R's queue and asks for an offer while N is still taking A's; it is made once N has both. No unit is lent
     *  twice, and W, which could take only N's 2 units, still waits.
     */
    @Test
    @DisplayName("an offer asked for while an arriving preemptor takes its units is made once it has them")
    fun offerAskedForDuringAnArrivingPreemptionWaitsForIt() {
        val m = RuleModel("OfferDuringArrivingPreemption")
        val r = ResourceWithQ(m, "R", capacity = 3)
        r.interruptRule = InterruptRule.RESUME
        var bAllocation: Allocation? = null
        m.plan {
            actor("A", 9) { val a = seize(r); delay(100.0); release(a) }
            actor("B", 9) { delay(0.1); val a = seize(r); bAllocation = a; delay(100.0); release(a) }
            actor("X", 1) { delay(0.2); val a = seize(r); delay(100.0); release(a) }
            actor("M", 9) {
                delay(0.5)
                waitForFirst(bAllocation!!.interruptedTrigger(), r.seizeTrigger(), timeout = 1000.0)
                log.add("M woke@$time")
            }
            actor("W", 1) { delay(1.0); val a = preempt(r, 3, rule = InterruptRule.RESUME); log.add("W got@$time"); delay(1.0); release(a) }
            actor("N", 3) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("N got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        assertTrue("N got@2.0" in m.log, m.log.toString())
        assertTrue("M woke@2.0" in m.log, m.log.toString())
        assertFalse(m.log.any { it.startsWith("W got@2.") }, m.log.toString())
    }
}
