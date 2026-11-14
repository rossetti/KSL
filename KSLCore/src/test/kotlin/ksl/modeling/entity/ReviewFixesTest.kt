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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Failures and preemption where they meet each other, capacity changes and the end of a replication:
 *  each test reproduces a fault in those meetings.
 */
class ReviewFixesTest {

    private class FModel(parent: ModelElement) : ProcessModel(parent, "fixes") {
        val r = ResourceWithQ(this, "R")
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val p1 = ResourceWithQ(this, "P1")
        val p2 = ResourceWithQ(this, "P2")
        val pool = ResourcePoolWithQ(this, listOf(p1, p2), name = "Pool")

        val log = mutableListOf<String>()
        val setups = mutableListOf<FModel.() -> Unit>()

        inner class Actor(val tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) = actor("at$t") { delay(t); action() }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }

        override fun replicationEnded() {
            LoansAndFreezesCheck.check(r, r2, p1, p2)
        }
    }

    private fun run(reps: Int = 1, length: Double = 100.0, configure: FModel.() -> Unit = {}, setup: FModel.() -> Unit): FModel {
        val model = Model("Fixes${System.nanoTime()}", autoCSVReports = false)
        val m = FModel(model)
        m.configure()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    // ---------- the end of a replication ----------

    @Test
    @DisplayName("waits on a lender's restored trigger, with its borrower terminated at the replication's end: the run completes")
    fun restoredWaitsAtTheEnd() {
        val m = run(reps = 50, length = 20.0) {
            lateinit var v: Allocation
            actor("V", 9) { v = seize(r); delay(500.0); release(v) }
            actor("B", 1) { delay(2.0); val a = preempt(r, rule = InterruptRule.RESUME); delay(500.0); release(a) }
            for (k in 1..6) actor("W$k") { delay(3.0); waitForFirst(v.restoredTrigger()); log.add("W$k restored@$time") }
        }
        // nobody is restored within the replication; the end terminates them all without error
        assertEquals(emptyList(), m.log)
    }

    private class Customers(parent: ModelElement) : ProcessModel(parent, "customers") {
        lateinit var resource: ResourceWithQ
        var served = 0

        inner class Customer : Entity() {
            val run: KSLProcess = process("customer") { val a = seize(resource); delay(1_000.0); release(a); served++ }
        }

        override fun initialize() {
            served = 0
            activate(Customer().run)
        }
    }

    @Test
    @DisplayName("a USES failure whose count a release completes at the replication's end: the run completes")
    fun usesCountedAtTheEnd() {
        val model = Model("FixesUses${System.nanoTime()}", autoCSVReports = false)
        val customers = Customers(model)
        // created after the model whose customer holds it, so it ends its replication after that customer is terminated
        val resource = ResourceWithQ(model, "Sibling")
        resource.failures(ConstantRV(1.0), ConstantRV(1.0), ResourceFailureBasis.USES)
        customers.resource = resource
        model.numberOfReplications = 3
        model.lengthOfReplication = 10.0
        model.simulate()
        assertEquals(0, customers.served)
    }

    private class DecreaseCustomers(parent: ModelElement) : ProcessModel(parent, "decreaseCustomers") {
        lateinit var resource: ResourceWithQ
        var served = 0

        inner class Customer : Entity() {
            val run: KSLProcess = process("customer") { val a = seize(resource); delay(1_000.0); release(a); served++ }
        }

        override fun initialize() {
            served = 0
            activate(Customer().run)
            schedule({ _: ksl.simulation.KSLEvent<Nothing> ->
                resource.changeCapacity(resource.CapacityChangeNotice(capacity = 0, duration = 5.0))
            }, 2.0)
        }
    }

    @Test
    @DisplayName("a WAIT decrease pending at the replication's end, completed by a terminated holder's release: the run completes")
    fun pendingDecreaseAtTheEnd() {
        val model = Model("FixesDecrease${System.nanoTime()}", autoCSVReports = false)
        val customers = DecreaseCustomers(model)
        // created after the model whose customer holds it, so its pending decrease is still there at the termination
        val resource = ResourceWithQ(model, "Sibling")
        resource.capacityChangeRule = CapacityChangeRule.WAIT
        customers.resource = resource
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        model.simulate()
        assertEquals(0, customers.served)
        assertEquals(1, resource.capacity)
    }

    // ---------- capacity changes during a failure ----------

    @Test
    @DisplayName("going off shift while failed takes the idle unit at once, and the repair serves no one until back on shift")
    fun offShiftWhileFailed() {
        for (rule in listOf(CapacityChangeRule.WAIT, CapacityChangeRule.IGNORE)) {
            val m = run(configure = { r.capacityChangeRule = rule }) {
                at(1.0) { r.fail() }
                at(2.0) { r.goOffShift(); log.add("off cap=${r.capacity} pending=${r.isPendingCapacityChange}") }
                actor("N") { delay(3.0); val a = seize(r); log.add("N got@$time"); delay(1.0); release(a) }
                at(5.0) { r.repair(); log.add("repaired ${r.state.name}") }
                at(50.0) { r.goOnShift() }
            }
            assertEquals(listOf("off cap=0 pending=false", "repaired R_Inactive", "N got@50.0"), m.log, "rule $rule")
        }
    }

    /** The capacity at [times], with or without a failure from [failAt] to [repairAt], as the changes [setup] makes it. */
    private fun capacityTrace(
        rule: CapacityChangeRule, times: List<Double>, failAt: Double?, repairAt: Double?,
        configure: FModel.() -> Unit = {}, setup: FModel.() -> Unit = {}
    ): List<String> = run(configure = { r2.capacityChangeRule = rule; configure() }) {
        setup()
        if (failAt != null) at(failAt) { r2.fail() }
        if (repairAt != null) at(repairAt) { r2.repair() }
        for (t in times) at(t) { log.add("$t cap=${r2.capacity} pending=${r2.isPendingCapacityChange}") }
    }.log

    @Test
    @DisplayName("a capacity schedule's changes apply underneath a failure as they would without it")
    fun scheduleUnderAFailure() {
        for (rule in listOf(CapacityChangeRule.WAIT, CapacityChangeRule.IGNORE)) {
            fun trace(withFailure: Boolean) = capacityTrace(
                rule, listOf(5.0, 15.0, 25.0, 35.0),
                failAt = if (withFailure) 4.0 else null, repairAt = if (withFailure) 26.0 else null,
                configure = {
                    val schedule = CapacitySchedule(this, startTime = 0.0)
                    schedule.addItem(capacity = 2, duration = 10.0)
                    schedule.addItem(capacity = 1, duration = 10.0)
                    schedule.addItem(capacity = 2, duration = 30.0)
                    r2.useSchedule(schedule, rule)
                }
            )
            val without = trace(false)
            assertEquals(listOf("5.0 cap=2 pending=false", "15.0 cap=1 pending=false", "25.0 cap=2 pending=false", "35.0 cap=2 pending=false"), without, "rule $rule")
            assertEquals(without, trace(true), "rule $rule")
        }
    }

    @Test
    @DisplayName("two overlapping finite decreases during a failure behave as they do without it")
    fun overlappingDecreasesUnderAFailure() {
        for (rule in listOf(CapacityChangeRule.IGNORE, CapacityChangeRule.WAIT)) {
            fun trace(withFailure: Boolean) = capacityTrace(
                rule, listOf(3.0, 5.0, 10.0, 15.0),
                failAt = if (withFailure) 1.0 else null, repairAt = if (withFailure) 30.0 else null
            ) {
                at(2.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 1, duration = 10.0)) }
                at(4.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 0, duration = 5.0)) }
            }
            assertEquals(trace(false), trace(true), "rule $rule")
        }
    }

    // ---------- a failure carried back through a loan ----------

    /**
     *  V holds R and works 10 from 0; P preempts at 2 under RESUME; R fails at 3 under [failRule] and is
     *  repaired at 30. [borrower] decides how P lets go of the unit while R is failed.
     */
    private fun lenderThroughAFailure(failRule: InterruptRule, borrower: suspend KSLProcessBuilder.(Allocation) -> String?): Pair<FModel, Allocation> {
        lateinit var v: Allocation
        val m = run {
            actor("V", 9) { v = seize(r); delay(10.0); log.add("V done@$time"); release(v) }
            actor("P", 1) {
                delay(2.0)
                val a = preempt(r, rule = InterruptRule.RESUME)
                val note = borrower(a)
                if (note != null) log.add("$note@$time")
                release(a)
            }
            at(3.0) { r.fail(failRule); LoansAndFreezesCheck.check(r) }
            at(4.0) { log.add("at 4 V missing=${v.missingUnits} byFailure=${v.missingByFailure}"); LoansAndFreezesCheck.check(r) }
            at(30.0) { r.repair(); LoansAndFreezesCheck.check(r) }
        }
        return m to v
    }

    @Test
    @DisplayName("a borrower told of a failure releases while failed: the unit goes back to the lender still down, and the lender waits for the repair")
    fun borrowerReleasesWhileFailed() {
        val (m, v) = lenderThroughAFailure(InterruptRule.RESUME) {
            if (delayOrInterrupt(10.0) is DelayResult.Interrupted) "P told" else "P done"
        }
        // V: 2 of its 10 done when lent at 2; held until the repair at 30, then the 8 left
        assertEquals(listOf("P told@3.0", "at 4 V missing=1 byFailure=1", "V done@38.0"), m.log)
        assertEquals(1, v.timesInterrupted)
        assertEquals(28.0, v.timeInterrupted, 1e-12)
    }

    @Test
    @DisplayName("a borrower terminated while failed: the lender waits for the repair")
    fun borrowerTerminatedWhileFailed() {
        lateinit var v: Allocation
        val m = run {
            actor("V", 9) { v = seize(r); delay(10.0); log.add("V done@$time"); release(v) }
            val p = actor("P", 1) { delay(2.0); val a = preempt(r, rule = InterruptRule.RESUME); delay(10.0); release(a) }
            at(3.0) { r.fail(InterruptRule.RESUME) }
            at(5.0) { p.terminateProcess(); log.add("at 5 V missing=${v.missingUnits}"); LoansAndFreezesCheck.check(r) }
            at(30.0) { r.repair() }
        }
        assertEquals(listOf("at 5 V missing=1", "V done@38.0"), m.log)
    }

    @Test
    @DisplayName("the lender follows the failure's rule: under RESTART it starts its work again at the repair")
    fun lenderFollowsTheFailuresRule() {
        val (m, _) = lenderThroughAFailure(InterruptRule.RESTART) {
            delayOrInterrupt(10.0)
            null
        }
        assertEquals(listOf("at 4 V missing=1 byFailure=1", "V done@40.0"), m.log)
    }

    // ---------- ties at a delay's end ----------

    @Test
    @DisplayName("a failure at the instant a delay's end is due, before its end runs: the delay completes, under either rule")
    fun failureAtTheEndInstant() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) {
            val m = run {
                actor("W") { val a = seize(r); delay(10.0); log.add("W done@$time"); release(a) }
                actor("F") { delay(10.0, delayPriority = ProcessModel.DELAY_PRIORITY - 1); r.fail(rule) }
                at(20.0) { r.repair() }
            }
            assertEquals(listOf("W done@10.0"), m.log, "rule $rule")
        }
    }

    @Test
    @DisplayName("a preemption at the instant a delay's end is due: the delay completes")
    fun preemptionAtTheEndInstant() {
        val m = run {
            actor("L", 9) { val a = seize(r); delay(10.0); log.add("L done@$time"); release(a) }
            // the preempt's yield runs before L's end, so it takes the unit at the instant L's work is done
            actor("P", 1) {
                delay(10.0, delayPriority = ProcessModel.DELAY_PRIORITY - 2)
                val a = preempt(r, seizePriority = ProcessModel.DELAY_PRIORITY - 1, rule = InterruptRule.RESUME)
                log.add("P got@$time")
                delay(1.0)
                release(a)
            }
        }
        assertEquals(listOf("P got@10.0", "L done@10.0"), m.log)
    }

    @Test
    @DisplayName("a process interrupt at the instant a delay's end is due still holds it, as before")
    fun processInterruptAtTheEndInstantUnchanged() {
        lateinit var w: FModel.Actor
        val m = run {
            w = actor("W") { delay(10.0, suspensionName = "work"); log.add("W done@$time") }
            actor("I") { delay(10.0, delayPriority = ProcessModel.DELAY_PRIORITY - 1); interruptDelay(w.p, "work", 3.0, postInterruptDelayTime = 1.0) }
        }
        assertEquals(listOf("W done@14.0"), m.log)
    }

    @Test
    @DisplayName("with constant times, where events of different jobs fall on the same instants, reacting still imitates freezing")
    fun reactingImitatesFreezingWithTies() {
        val reacting = ReactingMetamorphicModels.Work { _, duration ->
            var res = delayOrInterrupt(duration)
            while (res is DelayResult.Interrupted) {
                waitForFirst(res.by.restoredTrigger())
                res = delayOrInterrupt(if (res.rule == InterruptRule.RESTART) res.duration else res.timeRemaining)
            }
        }
        for (variant in listOf("constant failures RESUME", "constant failures RESTART", "constant preemption RESUME", "constant preemption RESTART")) {
            assertEquals(
                ReactingMetamorphicModels.results(variant, ReactingMetamorphicModels.plainDelay),
                ReactingMetamorphicModels.results(variant, reacting),
                variant
            )
        }
    }

    // ---------- a reservation and a broken promise ----------

    @Test
    @DisplayName("a FINISH preemption whose reservation is complete, but whose promise breaks in that instant, keeps its place ahead of the queue")
    fun reservationKeptThroughABrokenPromise() {
        val m = run {
            actor("L", 9) { val a = seize(r); delay(10.0); release(a); r.fail() }
            actor("N", 5) { delay(1.0); val a = seize(r); log.add("N got@$time"); delay(1.0); release(a) }
            actor("U", 1) { delay(2.0); val a = preempt(r, rule = InterruptRule.FINISH); log.add("U got@$time"); delay(1.0); release(a) }
            at(20.0) { r.repair() }
        }
        assertEquals(listOf("U got@20.0", "N got@21.0"), m.log)
    }

    // ---------- declared failures and hand repairs ----------

    /** Logs each failure and repair of [res]. */
    private fun FModel.watch(res: Resource) {
        res.addAllocationListener(object : AllocationListenerIfc {
            override fun allocate(allocation: Allocation) {}
            override fun deallocate(allocation: Allocation) {}
            override fun failureStateChanged(resource: Resource) {
                log.add("${if (resource.isFailed) "fail" else "repair"}@$time")
            }
        })
    }

    @Test
    @DisplayName("a hand repair and a hand failure inside a declared repair time: the declared failure does not end the hand one")
    fun declaredFailureLeavesAHandFailureAlone() {
        val m = run(length = 40.0, configure = { r.failures(ConstantRV(10.0), ConstantRV(5.0)); watch(r) }) {
            at(12.0) { r.repair() }
            at(13.0) { r.fail() }
            at(16.0) { log.add("at 16 failed=${r.isFailed}") }
            at(20.0) { r.repair() }
        }
        // declared failure at 10; hand repair 12; hand failure 13, still failed at 16, repaired by hand at 20;
        // the next declared failure counts from the hand repair at 12, so it comes due at 22 and waits for nothing
        assertEquals(listOf("fail@10.0", "repair@12.0", "fail@13.0", "at 16 failed=true", "repair@20.0", "fail@22.0", "repair@27.0", "fail@37.0"), m.log)
    }

    @Test
    @DisplayName("after a hand repair of a declared failure, the time to the next failure counts from that repair")
    fun nextFailureFromTheHandRepair() {
        val m = run(length = 40.0, configure = { r.failures(ConstantRV(10.0), ConstantRV(5.0)); watch(r) }) {
            at(12.0) { r.repair() }
        }
        assertEquals(listOf("fail@10.0", "repair@12.0", "fail@22.0", "repair@27.0", "fail@37.0"), m.log)
    }

    // ---------- the inactive and active hooks around a failure ----------

    private class CountingResource(parent: ModelElement, name: String) : ResourceWithQ(parent, name) {
        var becameActive = 0
        override fun resourceBecameActive() {
            becameActive++
        }
    }

    private class HookModel(parent: ModelElement) : ProcessModel(parent, "hooks") {
        val res = CountingResource(this, "Counted")
        var toldInactive = 0
        val log = mutableListOf<String>()
        var plan: HookModel.() -> Unit = {}

        inner class Waiter : Entity("waiter") {
            val run: KSLProcess = process("waiter") { delay(1.0); val a = seize(res); log.add("served@$time"); release(a) }
            override fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(queue: RequestQ, resource: Resource, request: Request) {
                toldInactive++
            }
        }

        inner class Holder : Entity("holder") {
            val run: KSLProcess = process("holder") { val a = seize(res); delay(2.0); release(a) }
        }

        fun at(t: Double, action: () -> Unit) = schedule({ _: ksl.simulation.KSLEvent<Nothing> -> action() }, t)

        override fun initialize() {
            log.clear()
            toldInactive = 0
            res.becameActive = 0
            activate(Waiter().run)
            plan()
        }
    }

    private fun hooks(plan: HookModel.() -> Unit): HookModel {
        val model = Model("Hooks${System.nanoTime()}", autoCSVReports = false)
        val m = HookModel(model)
        m.plan = plan
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("off shift, fail, repair, back on shift: the waiting entity is told once that the resource became inactive, and it becomes active once")
    fun hooksAroundAFailureWhileOffShift() {
        val m = hooks {
            // the holder keeps the unit until 2, so the waiter, from 1, is queued when the shift takes it
            activate(Holder().run)
            at(1.5) { res.goOffShift() }
            at(3.0) { res.fail() }
            at(5.0) { res.repair() }
            at(8.0) { res.goOnShift() }
        }
        assertEquals(1, m.toldInactive, "told inactive")
        assertEquals(1, m.res.becameActive, "became active")
        assertEquals(listOf("served@8.0"), m.log)
    }

    @Test
    @DisplayName("fail, then off shift, then repair: the resource becomes inactive at the repair, and the waiter is told then")
    fun inactiveFirstAtTheRepair() {
        val m = hooks {
            at(0.5) { res.fail() }
            at(2.0) { res.goOffShift() }
            at(5.0) { log.add("before repair told=$toldInactive"); res.repair(); log.add("after repair told=$toldInactive") }
            at(8.0) { res.goOnShift() }
        }
        assertEquals(listOf("before repair told=0", "after repair told=1", "served@8.0"), m.log)
        assertEquals(1, m.res.becameActive)
    }

    @Test
    @DisplayName("reading the inactive state's statistics while inactive tells no one anything")
    fun readingTheInactiveStateIsQuiet() {
        val m = hooks {
            activate(Holder().run)
            at(1.5) { res.goOffShift() }
            at(10.0) {
                log.add("before read told=$toldInactive active=${res.becameActive}")
                res.inactiveState.totalTimeInState
                log.add("after read told=$toldInactive active=${res.becameActive}")
            }
        }
        assertEquals(listOf("before read told=1 active=0", "after read told=1 active=0"), m.log)
    }

    // ---------- failures and preemption together ----------

    @Test
    @DisplayName("with failures and preemption together, reacting imitates freezing, with random and with constant times")
    fun reactingImitatesFreezingWithBoth() {
        val reacting = ReactingMetamorphicModels.Work { _, duration ->
            var res = delayOrInterrupt(duration)
            while (res is DelayResult.Interrupted) {
                waitForFirst(res.by.restoredTrigger())
                res = delayOrInterrupt(if (res.rule == InterruptRule.RESTART) res.duration else res.timeRemaining)
            }
        }
        for (variant in listOf("both RESUME", "both RESTART", "constant both RESUME", "constant both RESTART")) {
            assertEquals(
                ReactingMetamorphicModels.results(variant, ReactingMetamorphicModels.plainDelay),
                ReactingMetamorphicModels.results(variant, reacting),
                variant
            )
        }
    }

    // ---------- pools and preemption ----------

    @Test
    @DisplayName("a pool member preempted from a holder through the pool: the holder is held for the loan, and its release frees both members")
    fun poolMemberPreempted() {
        val m = run {
            actor("L", 9) {
                val pa = seize(pool, 2)
                log.add("L got@$time")
                delay(10.0)
                release(pa)
                log.add("L done@$time busy=${p1.numBusy + p2.numBusy}")
            }
            actor("U", 1) { delay(2.0); val a = preempt(p1, rule = InterruptRule.RESUME); log.add("U got@$time"); delay(3.0); release(a); log.add("U done@$time") }
            at(3.0) { log.add("at 3 loans=${p1.loans.size}"); LoansAndFreezesCheck.check(p1, p2) }
            actor("N", 5) { delay(3.0); val pa = seize(pool); log.add("N got ${pa.allocations.single().myResource.name}@$time"); release(pa) }
        }
        // L is held 2 to 5 while P1 is lent, so it ends at 13; N, waiting for the pool from 3, then gets a member
        assertEquals(listOf("L got@0.0", "U got@2.0", "at 3 loans=1", "U done@5.0", "L done@13.0 busy=0", "N got P1@13.0"), m.log)
    }

    @Test
    @DisplayName("a FINISH preemption of a pool member is served by the pool's release, ahead of the member's own queue")
    fun reservationServedByAPoolRelease() {
        val m = run {
            actor("L", 9) { val pa = seize(pool, 2); delay(10.0); release(pa) }
            actor("N", 5) { delay(1.0); val a = seize(p1); log.add("N got@$time"); delay(1.0); release(a) }
            actor("U", 1) { delay(2.0); val a = preempt(p1, rule = InterruptRule.FINISH); log.add("U got@$time"); delay(1.0); release(a) }
        }
        assertEquals(listOf("U got@10.0", "N got@11.0"), m.log)
    }
}
