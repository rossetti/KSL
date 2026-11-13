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

        val log = mutableListOf<String>()
        val setups = mutableListOf<PModel.() -> Unit>()

        inner class Actor(val tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
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
            LoansAndFreezesCheck.check(r, r2, r3, ranked)
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
        assertEquals(listOf("L got@0.0", "L done@10.0", "P got@10.0", "P done@11.0"), m.log)
        assertEquals(0, m.r.numTimesPreempted)
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
        // failed: nothing is planned, so P waits like a seize behind N
        assertEquals(listOf("L got@0.0", "L done@10.0", "N got@10.0", "P got@10.0", "N done@11.0", "P done@11.0"), m2.log)
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
