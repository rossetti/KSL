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

package ksl.modeling.entity

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.emitters.ResourceAnimationEmitter
import ksl.modeling.queue.Queue
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Preemption: taking a resource's units from holders of lower priority, under each interrupt rule; the
 *  choice of victims; the loans and their return; nested loans; lenders and borrowers that release or
 *  end while units are lent; and preemption meeting failures, ranked queues, timed seizes and capacity
 *  changes.
 */
class PreemptionTest {

    private class PModel(parent: ModelElement) : ProcessModel(parent, "preemption") {
        val r = ResourceWithQ(this, "R")
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val r3 = ResourceWithQ(this, "R3", capacity = 3)
        val ranked = ResourceWithQ(this, "Ranked", queue = RequestQ(this, "RankedQ", Queue.Discipline.RANKED))
        val ranked2 = ResourceWithQ(this, "Ranked2", capacity = 2, queue = RequestQ(this, "Ranked2Q", Queue.Discipline.RANKED))
        val otherQ = RequestQ(this, "OtherQ")
        val backup = ResourceWithQ(this, "Backup")

        val log = mutableListOf<String>()
        val setups = mutableListOf<PModel.() -> Unit>()

        inner class Actor(val tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }

            /** When set, the entity moves its waiting request there when the resource it waits for goes inactive. */
            var movesTo: ResourceWithQ? = null

            override fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(
                queue: RequestQ, resource: Resource, request: Request
            ) {
                val target = movesTo ?: return
                log.add("$tag moves to ${target.name}@$time")
                moveRequestToResource(request as ResourceRequest, queue, target, RESUME_PRIORITY)
            }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) = actor("at$t") { delay(t); action() }

        /** Seizes [n] units of [res] at [from], works for [hold], releases, and logs when it got and finished. */
        fun job(tag: String, res: ResourceWithQ, from: Double, hold: Double, priority: Int = 5, n: Int = 1) =
            actor(tag, priority) {
                delay(from)
                val a = seize(res, n)
                log.add("$tag got@$time")
                delay(hold, suspensionName = "work")
                release(a)
                log.add("$tag done@$time")
            }

        /** Preempts [n] units of [res] at [from] under [rule], holds them for [hold], and logs. */
        fun urgent(tag: String, res: ResourceWithQ, from: Double, hold: Double, priority: Int = 1, n: Int = 1,
                   rule: InterruptRule = InterruptRule.RESUME) =
            actor(tag, priority) {
                delay(from)
                val a = preempt(res, n, rule = rule)
                log.add("$tag got@$time")
                delay(hold, suspensionName = "urgent work")
                release(a)
                log.add("$tag done@$time")
            }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }

        override fun replicationEnded() {
            LoansAndFreezesCheck.check(r, r2, r3, ranked, ranked2, backup)
        }
    }

    private fun run(reps: Int = 1, length: Double = 100.0, sink: AnimationSink? = null,
                    configure: PModel.() -> Unit = {}, setup: PModel.() -> Unit): Pair<Model, PModel> {
        val model = Model("Preemption${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = PModel(model)
        m.configure()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return model to m
    }

    // ---------- the basics ----------

    @Test
    @DisplayName("free units are taken first, and no one is preempted")
    fun freeUnitsFirst() {
        val (_, m) = run {
            job("L", r2, 0.0, 10.0, priority = 9)
            urgent("P", r2, 1.0, 2.0)
        }
        assertEquals(listOf("L got@0.0", "P got@1.0", "P done@3.0", "L done@10.0"), m.log)
        assertEquals(0, m.r2.numTimesPreempted)
    }

    @Test
    @DisplayName("one victim under each rule: the end of its delay, the units back to it before the queue")
    fun oneVictimEachRule() {
        val expected = mapOf(
            InterruptRule.RESUME to listOf("L got@0.0", "P got@3.0", "P done@7.0", "L done@14.0", "N got@14.0", "N done@15.0"),
            InterruptRule.RESTART to listOf("L got@0.0", "P got@3.0", "P done@7.0", "L done@17.0", "N got@17.0", "N done@18.0"),
            InterruptRule.FINISH to listOf("L got@0.0", "L done@10.0", "P got@10.0", "P done@14.0", "N got@14.0", "N done@15.0")
        )
        for ((rule, log) in expected) {
            val (_, m) = run {
                job("L", r, 0.0, 10.0, priority = 9)
                job("N", r, 1.0, 1.0, priority = 5)
                urgent("P", r, 3.0, 4.0, rule = rule)
                at(3.5) { LoansAndFreezesCheck.check(r) }
            }
            assertEquals(log, m.log, "rule $rule")
            assertEquals(1, m.r.numTimesPreempted, "rule $rule")
        }
    }

    @Test
    @DisplayName("equal priority never preempts, and no lower-priority holder means it waits like a seize")
    fun noVictimWaitsLikeSeize() {
        for (holderPriority in listOf(1, 3)) {
            val (_, m) = run {
                job("L", r, 0.0, 10.0, priority = holderPriority)
                urgent("P", r, 2.0, 1.0, priority = 3)
            }
            assertEquals(listOf("L got@0.0", "L done@10.0", "P got@10.0", "P done@11.0"), m.log, "holder priority $holderPriority")
            assertEquals(0, m.r.numTimesPreempted)
        }
    }

    @Test
    @DisplayName("victim order: the lowest priority first, then the most recently allocated")
    fun victimOrder() {
        val (_, m) = run {
            job("A", r3, 0.0, 20.0, priority = 5)
            job("B", r3, 1.0, 20.0, priority = 7)
            job("C", r3, 2.0, 20.0, priority = 7)
            urgent("P", r3, 5.0, 3.0)
            urgent("Q", r3, 30.0, 0.0)
        }
        // C (priority 7, allocated last) lends from 5 to 8
        assertTrue("C done@25.0" in m.log, m.log.toString())
        assertTrue("B done@21.0" in m.log, m.log.toString())
        assertTrue("A done@20.0" in m.log, m.log.toString())
    }

    @Test
    @DisplayName("only the units needed are taken; a victim with part of its units lent is frozen")
    fun onlyTheUnitsNeeded() {
        val (_, m) = run {
            job("V", r3, 0.0, 10.0, priority = 9, n = 3)
            urgent("P", r3, 2.0, 3.0)
            at(3.0) {
                val v = r3.allocations().first { it.amount == 3 }
                log.add("at 3 busy=${r3.numBusy} V missing=${v.missingByLoan}")
                LoansAndFreezesCheck.check(r3)
            }
        }
        assertEquals(listOf("V got@0.0", "P got@2.0", "at 3 busy=3 V missing=1", "P done@5.0", "V done@13.0"), m.log)
    }

    @Test
    @DisplayName("nested: three priority levels, and the units go back along the chain")
    fun nested() {
        val (_, m) = run {
            job("A", r, 0.0, 10.0, priority = 9)
            urgent("B", r, 2.0, 4.0, priority = 5)
            urgent("C", r, 3.0, 2.0, priority = 1)
            at(4.0) { LoansAndFreezesCheck.check(r); log.add("at 4 busy=${r.numBusy} loans=${r.loans.size}") }
        }
        // C takes B's borrowed unit 3 to 5; B, frozen 3 to 5, ends at 8; A, frozen 2 to 8, ends at 16
        assertEquals(listOf("A got@0.0", "B got@2.0", "C got@3.0", "at 4 busy=1 loans=2", "C done@5.0", "B done@8.0", "A done@16.0"), m.log)
        assertEquals(2, m.r.numTimesPreempted)
    }

    // ---------- a waiting preemptor and a loan that ends ----------

    @Test
    @DisplayName("a loan that ends passes to a waiting preemptor that outranks its holder; the holder resumes after it")
    fun loanPassesToAWaitingPreemptor() {
        for (res in listOf("ranked", "fifo")) {
            val (_, m) = run {
                val q = if (res == "ranked") ranked else r
                job("R", q, 0.0, 10.0, priority = 9)
                urgent("P1", q, 1.0, 5.0)
                job("N", q, 1.5, 1.0, priority = 5)
                urgent("P2", q, 2.0, 5.0)
                at(7.0) { LoansAndFreezesCheck.check(q); log.add("at 7 busy=${q.numBusy} loans=${q.loans.size}") }
            }
            // P2 waits from 2: nothing it can take. At 6 P1's loan of R's unit ends and passes to P2; R, held
            // from 1 with 9 left, resumes at 11 and ends at 20; N, a plain seize, waits for a release
            assertEquals(
                listOf("R got@0.0", "P1 got@1.0", "P1 done@6.0", "P2 got@6.0", "at 7 busy=1 loans=1", "P2 done@11.0",
                    "R done@20.0", "N got@20.0", "N done@21.0"), m.log, res
            )
            assertEquals(2, (if (res == "ranked") m.ranked else m.r).numTimesPreempted, res)
        }
    }

    @Test
    @DisplayName("a passed loan under RESTART: the holder starts again in full after the second preemptor")
    fun loanPassesUnderRestart() {
        val (_, m) = run {
            job("R", ranked, 0.0, 10.0, priority = 9)
            urgent("P1", ranked, 1.0, 5.0, rule = InterruptRule.RESTART)
            urgent("P2", ranked, 2.0, 5.0, rule = InterruptRule.RESTART)
        }
        assertEquals(listOf("R got@0.0", "P1 got@1.0", "P1 done@6.0", "P2 got@6.0", "P2 done@11.0", "R done@21.0"), m.log)
    }

    @Test
    @DisplayName("a loan that ends goes back to its holder when no waiting preemptor outranks it, or the waiter is a plain seize")
    fun loanGoesBackWithoutAnOutrankingPreemptor() {
        // a plain seize of the highest priority: a seize never takes units from a holder
        val (_, plain) = run {
            job("R", ranked, 0.0, 10.0, priority = 9)
            urgent("P1", ranked, 1.0, 5.0)
            job("S", ranked, 2.0, 1.0, priority = 1)
        }
        assertEquals(listOf("R got@0.0", "P1 got@1.0", "P1 done@6.0", "R done@15.0", "S got@15.0", "S done@16.0"), plain.log)
        // a waiting preemptor of the holder's own priority cannot take its units
        val (_, equal) = run {
            job("R", ranked, 0.0, 10.0, priority = 9)
            urgent("P1", ranked, 1.0, 5.0)
            urgent("P2", ranked, 2.0, 1.0, priority = 9)
        }
        assertEquals(listOf("R got@0.0", "P1 got@1.0", "P1 done@6.0", "R done@15.0", "P2 got@15.0", "P2 done@16.0"), equal.log)
    }

    @Test
    @DisplayName("a waiting preemptor that needs more than the loan returns takes the rest from the holder, as it would on arrival")
    fun passedLoanAndMoreFromTheHolder() {
        val (_, m) = run {
            job("R", r2, 0.0, 10.0, priority = 9, n = 2)
            urgent("P1", r2, 1.0, 5.0)
            urgent("P2", r2, 2.0, 5.0, n = 2)
            at(7.0) { LoansAndFreezesCheck.check(r2); log.add("at 7 busy=${r2.numBusy} loans=${r2.loans.size}") }
        }
        // at 2, P2 can take only R's one unit not lent: too few, so it waits. At 6 it takes both of R's units
        assertEquals(listOf("R got@0.0", "P1 got@1.0", "P1 done@6.0", "P2 got@6.0", "at 7 busy=2 loans=1", "P2 done@11.0", "R done@20.0"), m.log)
    }

    @Test
    @DisplayName("a preemptor terminated after a loan passed to it: the units go back to their holder")
    fun passedLoanBorrowerTerminated() {
        val (_, m) = run {
            job("R", ranked, 0.0, 10.0, priority = 9)
            urgent("P1", ranked, 1.0, 5.0)
            val p2 = urgent("P2", ranked, 2.0, 5.0)
            at(8.0) { p2.terminateProcess(); LoansAndFreezesCheck.check(ranked); log.add("at 8 busy=${ranked.numBusy} loans=${ranked.loans.size}") }
        }
        // R, held from 1 with 9 left, resumes at 8 and ends at 17
        assertEquals(listOf("R got@0.0", "P1 got@1.0", "P1 done@6.0", "P2 got@6.0", "at 8 busy=1 loans=0", "R done@17.0"), m.log)
    }

    // ---------- lenders and borrowers that end ----------

    @Test
    @DisplayName("the victim terminated while its units are lent: they go to the queue at the borrower's release")
    fun victimTerminatedWhileLent() {
        val (_, m) = run {
            val a = job("A", r, 0.0, 10.0, priority = 9)
            job("N", r, 1.0, 1.0, priority = 5)
            urgent("P", r, 2.0, 5.0)
            at(4.0) { a.terminateProcess(); LoansAndFreezesCheck.check(r); log.add("at 4 busy=${r.numBusy}") }
        }
        assertEquals(listOf("A got@0.0", "P got@2.0", "at 4 busy=1", "P done@7.0", "N got@7.0", "N done@8.0"), m.log)
    }

    @Test
    @DisplayName("the victim releasing while its units are lent: the loan ends and the units go to the queue at the borrower's release")
    fun victimReleasesWhileLent() {
        val (_, m) = run {
            actor("A", 9) {
                val a = seize(r)
                // its work does not depend on the unit, so it is not frozen
                delay(10.0, uses = emptyList())
                release(a)
                log.add("A done@$time busy=${r.numBusy}")
            }
            job("N", r, 1.0, 1.0, priority = 5)
            urgent("P", r, 2.0, 10.0)
        }
        assertEquals(listOf("P got@2.0", "A done@10.0 busy=1", "P done@12.0", "N got@12.0", "N done@13.0"), m.log)
    }

    @Test
    @DisplayName("the borrower terminated, and lenders and borrowers at the end of a replication: nothing outstanding in the next")
    fun borrowerTerminatedAndReplicationEnd() {
        val (_, m) = run(reps = 3, length = 20.0) {
            job("A", r, 0.0, 10.0, priority = 9)
            val p = urgent("P", r, 2.0, 50.0)
            at(4.0) { p.terminateProcess(); LoansAndFreezesCheck.check(r); log.add("at 4 busy=${r.numBusy} loans=${r.loans.size}") }
            job("X", r2, 0.0, 30.0, priority = 9)
            job("Y", r2, 0.0, 30.0, priority = 9)
            urgent("Z", r2, 5.0, 30.0)
        }
        // A's unit comes back at 4, so it ends at 12; at the end at 20 X or Y lends to Z and both are in service
        assertEquals(listOf("A got@0.0", "X got@0.0", "Y got@0.0", "P got@2.0", "at 4 busy=1 loans=0", "Z got@5.0", "A done@12.0"), m.log)
    }

    @Test
    @DisplayName("the middle of a chain terminated while it borrows and lends: the units it lent on are not given back twice")
    fun middleOfAChainTerminated() {
        val (_, m) = run {
            job("A", r, 0.0, 10.0, priority = 9)
            val b = urgent("B", r, 2.0, 20.0, priority = 5)
            urgent("C", r, 3.0, 5.0, priority = 1)
            at(4.0) {
                b.terminateProcess()
                log.add("at 4 busy=${r.numBusy} cap=${r.capacity}")
                LoansAndFreezesCheck.check(r)
            }
        }
        // C holds the one unit until 8; A, frozen from 2, must stay frozen until then and end at 16
        assertEquals(listOf("A got@0.0", "B got@2.0", "C got@3.0", "at 4 busy=1 cap=1", "C done@8.0", "A done@16.0"), m.log)
    }

    // ---------- with failures ----------

    @Test
    @DisplayName("preempting a failed resource waits like a seize")
    fun preemptingAFailedResource() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 9)
            at(1.0) { r.fail(InterruptRule.FINISH) }
            urgent("P", r, 2.0, 1.0)
            at(5.0) { r.repair() }
        }
        // failed at 2, P waits; at the repair it plans again and takes L's unit: L, with 9 left at 1 under
        // FINISH, has worked on through the failure and goes on at 6 with 6 left
        assertEquals(listOf("L got@0.0", "P got@5.0", "P done@6.0", "L done@11.0"), m.log)
        assertEquals(1, m.r.numTimesPreempted)
    }

    @Test
    @DisplayName("a failure while units are lent interrupts the borrower; the lender stays frozen until its loan returns")
    fun failureWhileLent() {
        val (_, m) = run {
            job("A", r, 0.0, 10.0, priority = 9)
            urgent("P", r, 2.0, 5.0)
            at(3.0) { r.fail(InterruptRule.RESUME); LoansAndFreezesCheck.check(r) }
            at(6.0) { r.repair(); LoansAndFreezesCheck.check(r) }
        }
        // P frozen 3 to 6 ends at 10; A, lent from 2, ends at 10 + 8
        assertEquals(listOf("A got@0.0", "P got@2.0", "P done@10.0", "A done@18.0"), m.log)
    }

    // ---------- queues and timed seizes ----------

    @Test
    @DisplayName("a RANKED queue: a waiting preemptor is served first at the next release")
    fun rankedQueue() {
        val (_, m) = run {
            job("L", ranked, 0.0, 10.0, priority = 1)
            job("N", ranked, 1.0, 1.0, priority = 5)
            urgent("P", ranked, 2.0, 1.0, priority = 1)
        }
        assertEquals(listOf("L got@0.0", "L done@10.0", "P got@10.0", "P done@11.0", "N got@11.0", "N done@12.0"), m.log)
    }

    @Test
    @DisplayName("a FINISH preemption's reservation takes the units of a capacity increase and a repair ahead of the queue")
    fun reservationClaimPoints() {
        // a capacity increase
        val (_, m1) = run {
            job("L", r, 0.0, 10.0, priority = 9)
            job("N", r, 1.0, 1.0, priority = 5)
            urgent("P", r, 2.0, 1.0, rule = InterruptRule.FINISH)
            at(4.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
        }
        assertEquals(listOf("L got@0.0", "P got@4.0", "P done@5.0", "N got@5.0", "N done@6.0", "L done@10.0"), m1.log)
        // a repair
        val (_, m2) = run {
            job("L", r2, 0.0, 10.0, priority = 9, n = 2)
            job("N", r2, 0.5, 1.0, priority = 5)
            at(1.0) { r2.fail(InterruptRule.FINISH) }
            urgent("P", r2, 2.0, 1.0, rule = InterruptRule.FINISH)
            at(4.0) { r2.repair() }
        }
        // failed at 2: nothing is planned and P waits; at the repair it reserves L's unit, ahead of N
        assertEquals(listOf("L got@0.0", "L done@10.0", "P got@10.0", "N got@10.0", "P done@11.0", "N done@11.0"), m2.log)
    }

    @Test
    @DisplayName("a loan ending with a unit freed by the same release: a waiting preemptor needing both takes both")
    fun loanReturnAndFreeUnitsTogether() {
        val (_, m) = run {
            job("low", r2, 0.0, 10.0, priority = 9)
            urgent("high", r2, 1.0, 3.0, priority = 1, n = 2)
            urgent("middle", r2, 2.0, 1.0, priority = 3, n = 2)
        }
        // high took the free unit and low's; at 4 the free unit and the loan pass to middle, low stays held
        // with 9 left, and resumes at 5
        assertEquals(
            listOf("low got@0.0", "high got@1.0", "high done@4.0", "middle got@4.0", "middle done@5.0", "low done@14.0"),
            m.log
        )
    }

    @Test
    @DisplayName("a loan ending into a pending WAIT decrease: the freed unit goes to the decrease, and the loan back to its holder")
    fun loanReturnIntoAPendingDecrease() {
        val (_, m) = run(configure = { r2.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("low", r2, 0.0, 10.0, priority = 9)
            urgent("high", r2, 1.0, 3.0, priority = 1, n = 2)
            urgent("middle", r2, 2.0, 1.0, priority = 3, n = 2)
            at(3.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 1)) }
        }
        // at 4 the freed unit completes the cut to 1, so middle cannot have two: low resumes with 9 left
        assertTrue("low done@13.0" in m.log, m.log.toString())
        assertTrue(m.log.none { it.startsWith("middle got") }, m.log.toString())
        assertEquals(1, m.r2.capacity)
    }

    @Test
    @DisplayName("a loan ending at the release that fails the resource on USES: nothing passes on, and the loan goes back down")
    fun loanReturnAtAFailingRelease() {
        val (_, m) = run(configure = {
            r2.interruptRule = InterruptRule.RESUME
            r2.failures(ksl.utilities.random.rvariable.ConstantRV(1.0), ksl.utilities.random.rvariable.ConstantRV(2.0), ResourceFailureBasis.USES)
        }) {
            job("low", r2, 0.0, 10.0, priority = 9)
            urgent("high", r2, 1.0, 3.0, priority = 1, n = 2)
            urgent("middle", r2, 2.0, 1.0, priority = 3, n = 2)
        }
        // high's release at 4 is the first use: the resource fails, middle is passed nothing, and low's unit
        // comes back down. At the repair at 6 middle takes it and the free unit; its release at 7 is the next
        // use, repaired at 9, when low goes on with 9 left
        assertEquals(listOf("low got@0.0", "high got@1.0", "high done@4.0", "middle got@6.0", "middle done@7.0", "low done@18.0"), m.log)
    }

    @Test
    @DisplayName("a failure begun by a release listener as a loan ends: the units go back down, and the lender waits for the repair")
    fun failureBegunByAReleaseListener() {
        for ((rule, done) in listOf(InterruptRule.RESUME to "L done@29.0", InterruptRule.RESTART to "L done@30.0")) {
            val (_, m) = run(configure = {
                r.addAllocationListener(object : AllocationListenerIfc {
                    override fun allocate(allocation: Allocation) {}
                    override fun deallocate(allocation: Allocation) {
                        if (time == 5.0 && !r.isFailed) r.fail(rule)
                    }
                })
            }) {
                job("L", r, 0.0, 10.0, priority = 9)
                urgent("P", r, 1.0, 4.0)
                at(20.0) { r.repair() }
            }
            // L, held by the loan from 1 with 9 left, is held by the failure from 5 until the repair at 20
            assertTrue(done in m.log, "rule $rule: ${m.log}")
        }
    }

    @Test
    @DisplayName("a loan ending with a freed unit: a seize as urgent as the waiting preemptor, or more, takes the freed unit first")
    fun passOnKeepsBehindAsUrgentRequests() {
        for (res in listOf("ranked", "fifo")) for (seizePriority in listOf(1, 2, 5)) {
            val (_, m) = run {
                val q = if (res == "ranked") ranked2 else r2
                job("lender", q, 0.0, 20.0, priority = 10)
                urgent("borrower", q, 1.0, 4.0, priority = 0, n = 2)
                job("seize", q, 2.0, 1.0, priority = seizePriority)
                urgent("waiting", q, 3.0, 3.0, priority = 2, n = 2)
            }
            val expected = if (seizePriority <= 2) {
                // the seize takes the freed unit at 5 and the loan goes back; at 6 the preemptor takes the freed
                // unit and the lender's
                listOf("lender got@0.0", "borrower got@1.0", "borrower done@5.0", "seize got@5.0", "seize done@6.0",
                    "waiting got@6.0", "waiting done@9.0", "lender done@27.0")
            } else {
                // a less urgent seize does not hold the preemptor back: it takes the freed unit and the loan
                listOf("lender got@0.0", "borrower got@1.0", "borrower done@5.0", "waiting got@5.0", "waiting done@8.0",
                    "seize got@8.0", "seize done@9.0", "lender done@27.0")
            }
            assertEquals(expected, m.log, "$res, seize priority $seizePriority")
        }
    }

    @Test
    @DisplayName("holders allocated at the same instant: the later allocation is the more recent, and is taken from first")
    fun victimAmongHoldersAllocatedAtOneInstant() {
        val (_, m) = run {
            job("first", r2, 0.0, 10.0, priority = 9)
            job("second", r2, 0.0, 20.0, priority = 9)
            urgent("P", r2, 1.0, 4.0)
        }
        // second, allocated after first at 0, is held 1 to 5 and ends at 24; first is not touched
        assertEquals(listOf("first got@0.0", "second got@0.0", "P got@1.0", "P done@5.0", "first done@10.0", "second done@24.0"), m.log)
    }

    @Test
    @DisplayName("a waiting preemptor beside a request served as its resource fails is given one allocation, on one unit or two")
    fun preemptorBesideARequestServedAtAFailure() {
        for (two in listOf(true, false)) {
            val (_, m) = run {
                val q = if (two) r2 else r
                val n = if (two) 2 else 1
                actor("H") {
                    val a = seize(q, n)
                    delay(10.0)
                    // the release serves S, and the failure in the same step meets S as a holder
                    release(a)
                    q.fail(InterruptRule.FINISH)
                    delay(5.0)
                    q.repair()
                }
                val s = actor("S") { delay(1.0); val a = seize(q); log.add("S got@$time"); delay(1.0); release(a) }
                lateinit var p: PModel.Actor
                p = actor("P") {
                    delay(2.0)
                    val a = preempt(q, rule = InterruptRule.RESUME)
                    log.add("P got@$time")
                    delay(1.0)
                    release(a)
                    log.add("P holds ${p.numberOfAllocations(q)}, busy ${q.numBusy}")
                }
                actor("K") { delay(12.0); s.terminateProcess() }
            }
            // S is served at 10, as H releases, and under FINISH works through the failure. With one unit, S's release at
            // 11 is kept from the queue until the repair at 15, where P is given the unit. With two, P takes at 10 the
            // unit the offer leaves free once it has served S. Either way P ends holding nothing.
            val got = if (two) "P got@10.0" else "P got@15.0"
            assertEquals(listOf("S got@10.0", got, "P holds 0, busy 0"), m.log, if (two) "two units" else "one unit")
        }
    }

    @Test
    @DisplayName("a FINISH preemptor that reserved on arrival and is moved stays a preemptor at the resource it moves to")
    fun reservedFinishPreemptorMoved() {
        val (_, m) = run(length = 100.0) {
            job("H", r, 0.0, 10.0)
            job("B", backup, 0.0, 20.0)
            job("S", backup, 0.5, 1.0)
            val p = actor("P", 1) {
                delay(1.0)
                val a = preempt(r, rule = InterruptRule.FINISH)
                log.add("P got@$time")
                delay(1.0)
                release(a)
            }
            p.movesTo = backup
            at(2.0) { r.goOffShift() }
        }
        // P reserved H's unit at 1; R is off shift from 2 and, inactive at H's release at 10, P moves to Backup,
        // where it is served ahead of S, which waited from 0.5
        assertEquals(listOf("P got@20.0", "S got@21.0"), m.log.filter { it.startsWith("P got") || it.startsWith("S got") })
    }

    @Test
    @DisplayName("two queues: a freed unit goes to a waiting preemptor ahead of the request it outranks that would be served next")
    fun waitingPreemptorWithASecondQueue() {
        val (_, m) = run(length = 100.0) {
            job("H", r, 0.0, 10.0)
            actor("L", 9) { delay(1.0); val a = seize(r, queue = otherQ); log.add("L got@$time"); delay(5.0); release(a) }
            job("S", r, 2.0, 5.0, priority = 0)
            urgent("W", r, 3.0, 5.0, priority = 5)
        }
        // at 10 the default rule would serve L, which entered first; W outranks L and takes the unit; then L and S
        // in order of entry
        assertEquals(listOf("W got@10.0", "L got@15.0", "S got@20.0"), m.log.filter { "got" in it && !it.startsWith("H") })
    }

    @Test
    @DisplayName("two queues: a waiting preemptor needing more units than the request served next goes ahead of it, though a more urgent request waits behind it")
    fun waitingPreemptorTakingMoreThanTheNextRequest() {
        val (_, m) = run(length = 100.0) {
            job("H", r2, 0.0, 4.0, priority = 1, n = 2)
            job("A", r2, 1.0, 5.0, priority = 5)
            actor("B", 1) { delay(2.0); val a = seize(r2, queue = otherQ); log.add("B got@$time"); delay(5.0); release(a) }
            urgent("P", r2, 3.0, 1.0, priority = 2, n = 2)
        }
        // at 4 H frees both units: the default rule would serve A, which entered first; P outranks A and takes both.
        // B, more urgent than P, is not next in line, so it does not hold P back; A and B follow at P's release
        assertEquals(listOf("P got@4.0", "A got@5.0", "B got@5.0"), m.log.filter { "got" in it && !it.startsWith("H") })
    }

    @Test
    @DisplayName("a waiting preemptor handed its allocation is not offered a move in that instant: it keeps the allocation it was given")
    fun preemptorWithAPassedAllocationIsNotMoved() {
        // A request is offered a move only when its resource becomes inactive, and a resource does so only once no
        // unit is busy. The allocation passed to P is a busy unit, so R going off shift in the same step does not
        // make it inactive, and P is not offered the move.
        val (_, m) = run(length = 100.0) {
            actor("H", 1) {
                val a = seize(r)
                log.add("H got@$time")
                delay(10.0)
                // the release hands P its allocation; off shift in the same step, R tells the requests in its queue
                release(a)
                r.goOffShift()
            }
            val p = actor("P", 2) {
                delay(1.0)
                val a = preempt(r, rule = InterruptRule.RESUME)
                log.add("P got ${a.resource.name}@$time")
                delay(1.0)
                release(a)
            }
            p.movesTo = backup
        }
        assertEquals(listOf("H got@0.0", "P got R@10.0"), m.log)
    }

    @Test
    @DisplayName("off shift under the WAIT capacity rule, a preemption takes nothing: the holder finishes and the preemptor starts back on shift")
    fun nothingTakenOffShiftUnderWait() {
        val (_, m) = run(configure = { r.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("L", r, 0.0, 10.0, priority = 9)
            at(1.0) { r.goOffShift() }
            urgent("P", r, 2.0, 2.0)
            at(20.0) { r.goOnShift() }
        }
        assertEquals(listOf("L got@0.0", "L done@10.0", "P got@20.0", "P done@22.0"), m.log)
        assertEquals(0, m.r.numTimesPreempted)
    }

    @Test
    @DisplayName("back on shift under either capacity rule, a waiting preemptor takes the unit from the lower-priority holder")
    fun waitingPreemptorBackOnShiftUnderEitherRule() {
        for (rule in listOf(CapacityChangeRule.IGNORE, CapacityChangeRule.WAIT)) {
            // the cut is still waiting for L's unit when the shift returns
            val (_, pending) = run(configure = { r.capacityChangeRule = rule }) {
                job("L", r, 0.0, 10.0, priority = 9)
                at(1.0) { r.goOffShift() }
                urgent("P", r, 2.0, 1.0)
                at(5.0) { r.goOnShift() }
            }
            assertEquals(listOf("L got@0.0", "P got@5.0", "P done@6.0", "L done@11.0"), pending.log, "cut pending, $rule")
            // a borrower's release completes the cut off shift and the unit goes back to its lender, L
            val (_, completed) = run(configure = { r.capacityChangeRule = rule }) {
                job("L", r, 0.0, 10.0, priority = 9)
                urgent("B", r, 1.0, 2.0, priority = 3)
                at(2.0) { r.goOffShift() }
                urgent("P", r, 4.0, 1.0)
                at(5.0) { r.goOnShift() }
            }
            assertEquals(
                listOf("L got@0.0", "B got@1.0", "B done@3.0", "P got@5.0", "P done@6.0", "L done@13.0"),
                completed.log, "cut completed, $rule"
            )
        }
    }

    @Test
    @DisplayName("off shift, a preemption takes nothing under any rule, and a loan ending passes to no one")
    fun nothingTakenOffShift() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART, InterruptRule.FINISH)) {
            val (_, m) = run {
                job("L", r, 0.0, 10.0, priority = 9)
                at(1.0) { r.goOffShift() }
                urgent("P", r, 2.0, 1.0, rule = rule)
                at(5.0) { r.goOnShift() }
            }
            assertTrue(m.log.none { it.startsWith("P got") && it.removePrefix("P got@").toDouble() < 5.0 }, "rule $rule: ${m.log}")
        }
        val (_, m) = run {
            job("L", r, 0.0, 20.0, priority = 9)
            urgent("P1", r, 1.0, 5.0)
            urgent("P2", r, 2.0, 5.0)
            at(3.0) { r.goOffShift() }
            at(8.0) { r.goOnShift() }
        }
        // P1's loan ends at 6, off shift: it goes back to L, not to P2
        assertTrue(m.log.none { it == "P2 got@6.0" }, m.log.toString())
    }

    @Test
    @DisplayName("back on shift, a waiting preemptor takes the unit from the lower-priority holder, which resumes after it")
    fun waitingPreemptorBackOnShift() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 9)
            at(1.0) { r.goOffShift() }
            urgent("P", r, 2.0, 1.0)
            at(5.0) { r.goOnShift() }
        }
        // L works on off shift under IGNORE, 5 done at 5; held 5 to 6; 5 left from 6
        assertEquals(listOf("L got@0.0", "P got@5.0", "P done@6.0", "L done@11.0"), m.log)
        assertEquals(1, m.r.numTimesPreempted)
    }

    @Test
    @DisplayName("at a repair, a waiting preemptor takes the unit before its holder resumes")
    fun waitingPreemptorAtARepair() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 9)
            at(1.0) { r.fail(InterruptRule.RESUME) }
            urgent("P", r, 2.0, 1.0)
            at(4.0) { r.repair() }
        }
        // L held by the failure from 1 with 9 left, then by P from 4 to 5
        assertEquals(listOf("L got@0.0", "P got@4.0", "P done@5.0", "L done@14.0"), m.log)
    }

    @Test
    @DisplayName("after a capacity increase, a preemptor that needed more than the capacity takes what it needs")
    fun waitingPreemptorAfterACapacityIncrease() {
        val (_, m) = run {
            job("A", r2, 0.0, 10.0, priority = 9)
            job("B", r2, 0.0, 10.0, priority = 9)
            at(1.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 1)) }
            urgent("P", r2, 2.0, 1.0, n = 2)
            at(5.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 2)) }
        }
        assertTrue("P got@5.0" in m.log && "P done@6.0" in m.log, m.log.toString())
    }

    @Test
    @DisplayName("a release that frees a unit lets a waiting preemptor take it with a lower-priority holder's")
    fun waitingPreemptorAtARelease() {
        val (_, m) = run {
            job("E", r2, 0.0, 5.0, priority = 1)
            job("L", r2, 0.0, 20.0, priority = 9)
            urgent("P", r2, 1.0, 1.0, priority = 1, n = 2)
        }
        // at 1, E's unit cannot be taken (equal priority) and L's alone is too few; at 5 E's unit comes free
        assertEquals(listOf("E got@0.0", "L got@0.0", "E done@5.0", "P got@5.0", "P done@6.0", "L done@21.0"), m.log)
    }

    @Test
    @DisplayName("under FINISH, a preemptor that waited through a failure reserves at the repair, ahead of earlier seizes")
    fun waitingFinishPreemptorReservesAtARepair() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 9)
            at(1.0) { r.fail(InterruptRule.FINISH) }
            job("N", r, 1.5, 1.0, priority = 5)
            urgent("P", r, 2.0, 1.0, rule = InterruptRule.FINISH)
            at(4.0) { r.repair() }
        }
        assertEquals(listOf("L got@0.0", "L done@10.0", "P got@10.0", "P done@11.0", "N got@11.0", "N done@12.0"), m.log)
    }

    @Test
    @DisplayName("an IGNORE decrease below the busy units: a preemption within the new capacity still takes a unit")
    fun preemptionWithinAReducedCapacity() {
        val (_, m) = run {
            job("A", r2, 0.0, 10.0, priority = 9)
            job("B", r2, 0.0, 10.0, priority = 9)
            at(1.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 1)) }
            urgent("P", r2, 2.0, 1.0)
        }
        assertTrue("P got@2.0" in m.log, m.log.toString())
    }

    @Test
    @DisplayName("FINISH reservations are served most urgent first, oldest first among equals, on either queue")
    fun reservationsByPriority() {
        for (res in listOf("ranked", "fifo")) {
            val (_, m) = run {
                val q = if (res == "ranked") ranked else r
                job("R", q, 0.0, 10.0, priority = 9)
                urgent("A", q, 1.0, 5.0, priority = 2, rule = InterruptRule.FINISH)
                urgent("U", q, 2.0, 5.0, priority = 1, rule = InterruptRule.FINISH)
                urgent("A2", q, 2.5, 1.0, priority = 2, rule = InterruptRule.FINISH)
                job("N", q, 3.0, 1.0, priority = 5)
            }
            // U reserved after A but outranks it, so R's release goes to U; A, reserved before A2, goes next
            assertEquals(
                listOf("R got@0.0", "R done@10.0", "U got@10.0", "U done@15.0", "A got@15.0", "A done@20.0",
                    "A2 got@20.0", "A2 done@21.0", "N got@21.0", "N done@22.0"), m.log, res
            )
        }
    }

    @Test
    @DisplayName("a terminated FINISH preemptor drops its reservation and its promise goes to the queue")
    fun terminatedReservedPreemptor() {
        val (_, m) = run {
            job("L", r2, 0.0, 10.0, priority = 9)
            job("L2", r2, 0.0, 10.0, priority = 9)
            val p = urgent("P", r2, 2.0, 1.0, n = 2, rule = InterruptRule.FINISH)
            job("N", r2, 3.0, 1.0, priority = 5)
            at(5.0) { p.terminateProcess() }
        }
        // the first release at 10 would have gone to P; with P gone, N takes it
        assertTrue("N got@10.0" in m.log, m.log.toString())
        assertTrue(m.log.none { it.startsWith("P got") }, m.log.toString())
    }

    @Test
    @DisplayName("a timed seize reneging while a preempt waits: the preempt does not preempt later")
    fun timedSeizeRenegesWhilePreemptWaits() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 3)
            actor("T", 9) {
                delay(1.0)
                val res = seizeOrTimeout(r, timeout = 2.0)
                log.add("T ${if (res is WaitResult.TimedOut) "timed out" else "got"}@$time")
            }
            urgent("P", r, 2.0, 1.0, priority = 3)
        }
        assertEquals(listOf("L got@0.0", "T timed out@3.0", "L done@10.0", "P got@10.0", "P done@11.0"), m.log)
    }

    // ---------- a waiting preemptor's place among the others waiting ----------

    @Test
    @DisplayName("a waiting preemptor does not take a freed unit ahead of an equally urgent request that waited first")
    fun waitingPreemptorBehindAnEqualRequest() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 5)
            job("N", r, 1.0, 1.0, priority = 5)
            urgent("P", r, 2.0, 1.0, priority = 5)
        }
        assertEquals(listOf("L got@0.0", "L done@10.0", "N got@10.0", "N done@11.0", "P got@11.0", "P done@12.0"), m.log)
        assertEquals(0, m.r.numTimesPreempted)
    }

    @Test
    @DisplayName("a waiting preemptor does not take a freed unit ahead of a more urgent request")
    fun waitingPreemptorBehindAMoreUrgentRequest() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 1)
            job("N", r, 1.0, 1.0, priority = 1)
            urgent("P", r, 2.0, 1.0, priority = 5)
        }
        assertEquals(listOf("L got@0.0", "L done@10.0", "N got@10.0", "N done@11.0", "P got@11.0", "P done@12.0"), m.log)
    }

    @Test
    @DisplayName("a waiting preemptor does not take a freed unit ahead of a more urgent FINISH reservation")
    fun waitingPreemptorBehindAReservation() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 5)
            urgent("F", r, 1.0, 1.0, priority = 1, rule = InterruptRule.FINISH)
            urgent("P", r, 2.0, 1.0, priority = 5)
        }
        assertEquals(listOf("L got@0.0", "L done@10.0", "F got@10.0", "F done@11.0", "P got@11.0", "P done@12.0"), m.log)
    }

    @Test
    @DisplayName("under FINISH, a preemptor that arrives during another's service waits behind an earlier, equally urgent reservation")
    fun waitingFinishPreemptorBehindAnEqualReservation() {
        val (_, m) = run {
            job("R", r, 0.0, 10.0, priority = 2)
            urgent("A", r, 1.0, 5.0, rule = InterruptRule.FINISH)
            urgent("B", r, 2.0, 1.0, rule = InterruptRule.FINISH)
            urgent("W", r, 11.0, 1.0, rule = InterruptRule.FINISH)
        }
        // A and B reserve R's unit; W finds A, its equal, in service and waits. At 15 the unit goes to B
        assertEquals(
            listOf("R got@0.0", "R done@10.0", "A got@10.0", "A done@15.0", "B got@15.0", "B done@16.0", "W got@16.0", "W done@17.0"),
            m.log
        )
    }

    @Test
    @DisplayName("a waiting preemptor that needs a freed unit and a holder's leaves the freed unit to a more urgent request")
    fun waitingPreemptorTakingFreeAndHeldUnitsBehindAMoreUrgentRequest() {
        val (_, m) = run {
            job("E", r2, 0.0, 5.0, priority = 1)
            job("L", r2, 0.0, 20.0, priority = 9)
            job("N", r2, 1.0, 1.0, priority = 1)
            urgent("P", r2, 2.0, 1.0, priority = 3, n = 2)
        }
        // at 5 E's unit comes free: N, more urgent and waiting first, takes it; at 6 P takes it with L's
        assertEquals(
            listOf("E got@0.0", "L got@0.0", "E done@5.0", "N got@5.0", "N done@6.0", "P got@6.0", "P done@7.0", "L done@21.0"),
            m.log
        )
    }

    @Test
    @DisplayName("a waiting preemptor still takes a freed unit ahead of a less urgent request that waited first")
    fun waitingPreemptorAheadOfALessUrgentRequest() {
        val (_, m) = run {
            job("L", r, 0.0, 10.0, priority = 1)
            job("N", r, 1.0, 1.0, priority = 5)
            urgent("P", r, 2.0, 1.0, priority = 3)
        }
        assertEquals(listOf("L got@0.0", "L done@10.0", "P got@10.0", "P done@11.0", "N got@11.0", "N done@12.0"), m.log)
    }

    @Test
    @DisplayName("a waiting preemptor that moves its request is served by the resource it moved to, not the one it left")
    fun movedWaitingPreemptor() {
        val (_, m) = run(length = 200.0) {
            job("B", backup, 0.0, 100.0, priority = 1)
            job("H", r, 0.0, 3.0, priority = 1)
            val p = actor("P", 5) {
                delay(1.0)
                val a = preempt(r)
                log.add("P got ${a.resource.name}@$time")
                delay(1.0)
                release(a)
            }
            p.movesTo = backup
            at(2.0) { r.goOffShift() }
            at(5.0) { r.goOnShift() }
            at(6.0) { log.add("at 6 R busy=${r.numBusy} Backup waiting=${backup.waitingQ.size}") }
        }
        assertTrue("at 6 R busy=0 Backup waiting=1" in m.log, m.log.toString())
        assertTrue("P got Backup@100.0" in m.log, m.log.toString())
        assertTrue(m.log.none { it.startsWith("P got R") }, m.log.toString())
    }

    // ---------- capacity changes ----------

    @Test
    @DisplayName("preemption during a pending WAIT decrease: the loan is made from what is held, and the decrease takes units returned to the queue, not to lenders")
    fun preemptionDuringAPendingDecrease() {
        val (_, m) = run(configure = { r2.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("A", r2, 0.0, 10.0, priority = 9)
            job("B", r2, 0.0, 10.0, priority = 9)
            at(1.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 1)) }
            urgent("P", r2, 2.0, 3.0)
            at(6.0) { log.add("at 6 cap=${r2.capacity} busy=${r2.numBusy}") }
        }
        // the unit P borrowed goes back to its lender at 5, so the capacity is still 2 at 6
        assertTrue("at 6 cap=2 busy=2" in m.log, m.log.toString())
        assertTrue("P done@5.0" in m.log, m.log.toString())
        assertEquals(1, m.r2.capacity)
    }

    // ---------- counting and the trace ----------

    @Test
    @DisplayName("numTimesPreempted counts per replication and preemption counts one seize")
    fun numTimesPreemptedAcrossReplications() {
        val (_, m) = run(reps = 3) {
            job("L", r, 0.0, 10.0, priority = 9)
            urgent("P", r, 2.0, 1.0)
            at(50.0) { log.add("preempted=${r.numTimesPreempted} seized=${r.numTimesSeized} released=${r.numTimesReleased}") }
        }
        assertTrue("preempted=1 seized=2 released=2" in m.log, m.log.toString())
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("the trace of a preemption: the borrower's allocation, and nothing for the lender")
    fun preemptionInTheTrace() {
        val sink = CollectingSink()
        lateinit var lender: PModel.Actor
        lateinit var borrower: PModel.Actor
        run(sink = sink, configure = { r.addAllocationListener(ResourceAnimationEmitter(r)) }) {
            lender = job("L", r, 0.0, 10.0, priority = 9)
            borrower = urgent("P", r, 2.0, 1.0)
        }
        val seen = sink.events.mapNotNull {
            when (it) {
                is AnimationEvent.SeizeAllocated -> if (it.resourceName == "R") "${it.simTime} allocated to ${if (it.entityId == lender.id) "L" else "P"}" else null
                is AnimationEvent.Released -> if (it.resourceName == "R") "${it.simTime} released by ${if (it.entityId == lender.id) "L" else "P"}" else null
                else -> null
            }
        }
        assertEquals(listOf("0.0 allocated to L", "2.0 allocated to P", "3.0 released by P", "11.0 released by L"), seen)
        assertNotNull(borrower)
    }

    @Test
    @DisplayName("a movable resource cannot be preempted")
    fun movableRefused() {
        var error: String? = null
        val model = Model("PreemptMovable${System.nanoTime()}", autoCSVReports = false)
        object : ProcessModel(model, "movable") {
            val dm = ksl.modeling.spatial.DistancesModel()
            val a = dm.Location("A")
            init { spatialModel = dm; dm.addDistance(a, dm.Location("B"), 10.0, symmetric = true) }
            val truck = ksl.modeling.spatial.MovableResourceWithQ(this, a, ksl.utilities.random.rvariable.ConstantRV(1.0), name = "Truck")
            inner class E : Entity() {
                val p = process {
                    try { preempt(truck, queue = truck.myWaitingQ) } catch (e: IllegalArgumentException) { error = e.message }
                }
            }
            override fun initialize() { activate(E().p) }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        assertTrue(error?.contains("movable") == true, error.toString())
    }
}
