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
}
