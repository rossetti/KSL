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
import ksl.modeling.entity.AllocationListenerIfc
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  When a release ends a loan, the units freed and the units going back to their lenders go to the waiting
 *  preemptors and reservations in the order of any release: the most urgent waiting preemptor plans first and
 *  takes free units only when nothing as urgent comes before it, then the reservations, most urgent first. A lender
 *  gets its units back only when no outranking claimant can take what it needs.
 */
class LoanEndDecisionTest {


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
     *  R has 3 units. L (priority 5) holds 2. B (priority 0) preempts 3 at 1 under `RESUME` (the free unit and L's 2)
     *  and releases at 4. R (priority 9) seizes 1 at 1.5. C (priority 2) preempts 3 at 2 and W (priority 1) preempts
     *  1 at 3, both under `RESUME`, and wait. At 4 one unit is freed and L's 2 go back: W, the most urgent, takes the
     *  freed unit; C cannot take 3, so L's units go back and L resumes. C is served at W's release, 5.
     */
    @Test
    @DisplayName("a more urgent waiter whose need the free units cover takes them first at a loan's end")
    fun moreUrgentWaiterNeedingOnlyFreeUnitsIsIgnored() {
        val m = RuleModel("S1")
        val r = ResourceWithQ(m, "R", capacity = 3)
        m.plan {
            job("L", r, 0.0, 100.0, priority = 5, n = 2)
            urgent("B", r, 1.0, 3.0, priority = 0, n = 3, rule = InterruptRule.RESUME)
            job("R", r, 1.5, 1.0, priority = 9)
            urgent("C", r, 2.0, 5.0, priority = 2, n = 3, rule = InterruptRule.RESUME)
            urgent("W", r, 3.0, 1.0, priority = 1, n = 1, rule = InterruptRule.RESUME)
        }
        m.simulate(300.0)
        // expected: W takes the freed unit at 4, C (needing 3, only L's 2 lendable) waits, L resumes; C is served at W's release, 5
        assertEquals(listOf("L got@0.0", "B got@1.0", "B done@4.0", "W got@4.0", "W done@5.0", "C got@5.0"), m.log.take(6))
    }

    /**
     *  R has 4 units. L (priority 5) holds 2, H (priority 3) holds 1. B (priority 0) preempts 3 at 0.2 under `RESUME`
     *  (the free unit and L's 2) and releases at 3. W (priority 2, `FINISH`, 3 units) waits from 1 with nothing it
     *  can reserve; V (priority 2, `FINISH`, 1 unit) reserves H's unit at 2. At 3, as at any release, W may not take
     *  the freed unit ahead of V's equally urgent reservation, which takes it; L's units go back, and L, held from
     *  0.2 to 3, ends at 102.8.
     */
    @Test
    @DisplayName("at a loan's end an equally urgent reservation takes the freed unit ahead of a FINISH waiter, as at any release")
    fun finishWaiterBlocksEquallyUrgentReservation() {
        val m = RuleModel("S2")
        val r = ResourceWithQ(m, "R", capacity = 4)
        m.plan {
            job("L", r, 0.0, 100.0, priority = 5, n = 2)
            job("H", r, 0.1, 100.0, priority = 3, n = 1)
            urgent("B", r, 0.2, 2.8, priority = 0, n = 3, rule = InterruptRule.RESUME)
            urgent("W", r, 1.0, 1.0, priority = 2, n = 3, rule = InterruptRule.FINISH)
            urgent("V", r, 2.0, 10.0, priority = 2, n = 1, rule = InterruptRule.FINISH)
        }
        m.simulate(300.0)
        assertEquals("V got@3.0", m.log.first { it.startsWith("V got") }, m.log.toString())
        assertEquals("L done@102.8", m.log.first { it.startsWith("L done") }, m.log.toString())
    }

    /**
     *  R has 3 units. L (priority 5) holds 2; B (priority 0) preempts 3 at 0.2 under `RESUME` (the free unit and L's
     *  2) and releases at 3. At 2, W (priority 2, `FINISH`) and then C (priority 2, `RESUME`) preempt 2 each and
     *  wait, W first. At 3 one unit is freed and L's 2 go back: W, the first of the two to begin waiting, takes the
     *  freed unit and one of L's returning units (it outranks L, and they are all it needs), and L stays held. C
     *  cannot take what it needs, so L's other unit goes back. At W's release, 4, C takes the freed unit and L's
     *  returning one; L, held from 0.2 to 5, ends at 104.8.
     */
    @Test
    @DisplayName("at a loan's end, of two equally urgent waiters that began in the same instant, the first to begin is served first")
    fun equalTimeTieGoesToTheFirstWaiter() {
        val m = RuleModel("S5")
        val r = ResourceWithQ(m, "R", capacity = 3)
        m.plan {
            job("L", r, 0.0, 100.0, priority = 5, n = 2)
            urgent("B", r, 0.2, 2.8, priority = 0, n = 3, rule = InterruptRule.RESUME)
            urgent("W", r, 2.0, 1.0, priority = 2, n = 2, rule = InterruptRule.FINISH)
            urgent("C", r, 2.0, 1.0, priority = 2, n = 2, rule = InterruptRule.RESUME)
        }
        m.simulate(300.0)
        assertEquals(
            listOf("L got@0.0", "B got@0.2", "B done@3.0", "W got@3.0", "W done@4.0", "C got@4.0", "C done@5.0", "L done@104.8"),
            m.log
        )
    }



    /**
     *  R has 3 units. L (priority 5) holds 2; B (priority 0) preempts 3 at 0.2 under `RESUME` (the free unit and L's
     *  2) and releases at 4. W, C and N all have priority 2: W preempts 2 at 1 in a LIFO queue Q2 of R; C preempts 2
     *  at 2 in R's own queue; N seizes 1 at 3 in Q2, ahead of W by Q2's discipline. At 4, W may not take the freed
     *  unit (N comes before it), so it holds no one back: C takes the freed unit and one of L's returning units, L
     *  stays held, and only L's other unit goes back.
     */
    @Test
    @DisplayName("a waiter that may not take the free units holds no one back at a loan's end")
    fun lenderResumesAheadOfOutrankingPreemptorThatCanTakeWhatItNeeds() {
        val m = RuleModel("S6")
        val r = ResourceWithQ(m, "R", capacity = 3)
        val q2 = RequestQ(m, "Q2", discipline = Queue.Discipline.LIFO)
        r.registerCapacityChangeQueue(q2)
        val events = mutableListOf<String>()
        r.addAllocationListener(object : AllocationListenerIfc {
            override fun allocate(allocation: Allocation) {}
            override fun deallocate(allocation: Allocation) {}
            override fun unitsLent(lender: Allocation, borrower: Allocation, amount: Int) {
                events.add("lent ${lender.myEntity.name}->${borrower.myEntity.name} $amount @${m.time}")
            }
            override fun unitsReturned(lender: Allocation, amount: Int) {
                events.add("returned ${lender.myEntity.name} $amount @${m.time}")
            }
        })
        m.plan {
            events.clear()
            actor("L", 5) { delay(0.0); val a = seize(r, 2); log.add("L got@$time"); delay(100.0); release(a); log.add("L done@$time") }
            actor("B", 0) { delay(0.2); val a = preempt(r, 3, rule = InterruptRule.RESUME); log.add("B got@$time"); delay(3.8); release(a); log.add("B done@$time") }
            actor("W", 2) { delay(1.0); val a = preempt(r, 2, queue = q2, rule = InterruptRule.RESUME); log.add("W got@$time"); delay(1.0); release(a) }
            actor("C", 2) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("C got@$time"); delay(1.0); release(a) }
            actor("N", 2) { delay(3.0); val a = seize(r, 1, queue = q2); log.add("N got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        val at4 = events.filter { it.endsWith("@4.0") }
        assertEquals(listOf("returned L 1 @4.0"), at4, "log=${m.log}, events=$events")
    }

    private fun loanEvents(m: RuleModel, r: ResourceWithQ): MutableList<String> {
        val events = mutableListOf<String>()
        r.addAllocationListener(object : AllocationListenerIfc {
            override fun allocate(allocation: Allocation) {}
            override fun deallocate(allocation: Allocation) {}
            override fun unitsLent(lender: Allocation, borrower: Allocation, amount: Int) {
                events.add("lent ${lender.myEntity.name}->${borrower.myEntity.name} $amount @${m.time}")
            }
            override fun unitsReturned(lender: Allocation, amount: Int) {
                events.add("returned ${lender.myEntity.name} $amount @${m.time}")
            }
        })
        return events
    }

    /**
     *  R has 4 units. L (priority 5) holds 2, H (priority 3) holds 1. B (priority 0) preempts 3 under `RESUME` at 0.2
     *  (the free unit and L's 2) and releases at 3. V (priority 2, `FINISH`, 1 unit) reserves H's unit at 1. W
     *  (priority 2, `RESUME`, 2 units) waits from 2, behind V's reservation. At 3 the freed unit completes V, and then
     *  W takes L's 2 returning units: L stays held, its units passing straight from B to W, and is interrupted once.
     */
    @Test
    @DisplayName("a waiting preemptor that gave way to a reservation at a loan's end then takes the returning units, the lender staying held")
    fun returningUnitsAfterAReservationIsServed() {
        val m = RuleModel("F1")
        val r = ResourceWithQ(m, "R", capacity = 4)
        val ev = loanEvents(m, r)
        var held: Allocation? = null
        m.plan {
            ev.clear()
            actor("L", 5) { val a = seize(r, 2); held = a; delay(100.0); release(a); log.add("L done@$time") }
            actor("H", 3) { delay(0.1); val a = seize(r, 1); delay(100.0); release(a) }
            actor("B", 0) { delay(0.2); val a = preempt(r, 3, rule = InterruptRule.RESUME); delay(2.8); release(a) }
            actor("V", 2) { delay(1.0); val a = preempt(r, 1, rule = InterruptRule.FINISH); log.add("V got@$time"); delay(10.0); release(a) }
            actor("W", 2) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("W got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        assertEquals(listOf("lent L->B 2 @0.2", "returned L 2 @4.0"), ev.filter { !it.contains("->W") }, "events=$ev")
        assertEquals(1, held!!.timesInterrupted)
    }

    /**
     *  As above with a request N (priority 1, 1 unit) in place of the reservation. R has 3 units; L holds 2; B
     *  preempts 3 at 0.2 and releases at 3. At 3 N, more urgent, takes the freed unit, and W then takes L's returning
     *  units: L is interrupted once.
     */
    @Test
    @DisplayName("a waiting preemptor that gave way to a more urgent request at a loan's end then takes the returning units")
    fun returningUnitsAfterAMoreUrgentRequestIsServed() {
        val m = RuleModel("F2")
        val r = ResourceWithQ(m, "R", capacity = 3)
        val ev = loanEvents(m, r)
        var held: Allocation? = null
        m.plan {
            ev.clear()
            actor("L", 5) { val a = seize(r, 2); held = a; delay(100.0); release(a) }
            actor("B", 0) { delay(0.2); val a = preempt(r, 3, rule = InterruptRule.RESUME); delay(2.8); release(a) }
            actor("N", 1) { delay(1.0); val a = seize(r, 1); log.add("N got@$time"); delay(10.0); release(a) }
            actor("W", 2) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("W got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        assertEquals(listOf("N got@3.0", "W got@3.0"), m.log.take(2), "events=$ev")
        assertEquals(1, held!!.timesInterrupted, "events=$ev")
    }

    /**
     *  R has 4 units. L (priority 5) holds 2, H (priority 2) holds 2. B (priority 0) preempts 2 under `RESUME` at 0.2
     *  (L's 2) and releases at 3, freeing nothing. V (priority 1, `FINISH`, 2 units) reserves H's 2 at 1; W (priority 3,
     *  `FINISH`, 2 units) waits from 2. At 3 L's 2 go back: V, more urgent, completes from them, and W waits.
     */
    @Test
    @DisplayName("a FINISH waiter does not take returning units ahead of a more urgent FINISH reservation")
    fun moreUrgentReservationTakesTheReturningUnits() {
        val m = RuleModel("F3")
        val r = ResourceWithQ(m, "R", capacity = 4)
        m.plan {
            actor("L", 5) { val a = seize(r, 2); delay(100.0); release(a) }
            actor("H", 2) { delay(0.1); val a = seize(r, 2); delay(100.0); release(a) }
            actor("B", 0) { delay(0.2); val a = preempt(r, 2, rule = InterruptRule.RESUME); delay(2.8); release(a) }
            actor("V", 1) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); log.add("V got@$time"); delay(1.0); release(a) }
            actor("W", 3) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); log.add("W got@$time"); delay(1.0); release(a) }
        }
        m.simulate(300.0)
        assertEquals("V got@3.0", m.log.first { it.startsWith("V got") }, "log=${m.log}")
    }
}
