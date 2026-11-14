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
            LoansAndFreezesCheck.check(r, r2)
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
}
