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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  `RequestQ.removeAndTerminate` and `removeAllAndTerminate` hand on what the removed request leaves
 *  behind: units it had been promised, and, under a request selection rule, the free units the rule
 *  may now give another request. A request with no promise in a queue with no rule leaves nothing,
 *  and its removal changes nothing for the others. A process terminated while it waits hands on the
 *  same things through the termination's own clean-up.
 */
class RemoveAndTerminateTest {

    private class QModel(parent: ModelElement, capacity: Int) : ProcessModel(parent, "removeAndTerminate") {
        val r = ResourceWithQ(this, "R", capacity)
        val other = RequestQ(this, "Other")
        val log = mutableListOf<String>()
        val setups = mutableListOf<QModel.() -> Unit>()
        val customers = mutableMapOf<String, Customer>()

        inner class Customer(
            val tag: String,
            val arrive: Double,
            val hold: Double,
            val amount: Int,
            val inOther: Boolean,
            val afterRelease: (() -> Unit)?
        ) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") {
                delay(arrive)
                val a = if (inOther) seize(r, amount, queue = other) else seize(r, amount)
                log.add("$tag@$time")
                delay(hold)
                release(a)
                afterRelease?.invoke()
            }
        }

        fun customer(
            tag: String, arrive: Double, hold: Double = 1.0, amount: Int = 1, inOther: Boolean = false,
            afterRelease: (() -> Unit)? = null
        ) {
            val c = Customer(tag, arrive, hold, amount, inOther, afterRelease)
            customers[tag] = c
            activate(c.p)
        }

        fun requestOf(tag: String): ProcessModel.Entity.Request =
            r.myWaitingQ.toList().first { it.entity === customers[tag] }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t)
        }

        override fun initialize() {
            log.clear()
            customers.clear()
            for (s in setups) s()
        }
    }

    private fun run(capacity: Int = 1, setup: QModel.() -> Unit): QModel {
        val model = Model("RemoveAndTerminate${System.nanoTime()}", autoCSVReports = false)
        val m = QModel(model, capacity)
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("a waiting request with no promise, in a queue with no rule: removing it changes nothing for the others")
    fun noPromiseNoRule() {
        val m = run {
            customer("X", 0.0, hold = 5.0)
            customer("A", 1.0)
            customer("B", 2.0)
            at(3.0) { r.myWaitingQ.removeAndTerminate(requestOf("A")) }
        }
        assertEquals(listOf("X@0.0", "B@5.0"), m.log)
    }

    @Test
    @DisplayName("removeAllAndTerminate on a queue holding a woken request offers its unit to a request waiting in another queue")
    fun removeAllOffersOnward() {
        val m = run {
            customer("X", 0.0, hold = 5.0, afterRelease = {
                // the release has just woken A, the earliest waiter; A has not yet resumed
                r.myWaitingQ.removeAllAndTerminate()
            })
            customer("A", 1.0)
            customer("B", 2.0, inOther = true)
        }
        assertEquals(listOf("X@0.0", "B@5.0"), m.log)
    }

    @Test
    @DisplayName("terminating a waiting process whose request a rule had chosen lets the rule choose again at once")
    fun terminationUnderRule() {
        var seen = ""
        val m = run(capacity = 2) {
            // only the head of the queue may be served
            r.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { resource, queue ->
                listOfNotNull(queue.toList().firstOrNull { it.resource == resource })
            }
            customer("X1", 0.0, hold = 5.0)
            customer("X2", 0.0, hold = 10.0)
            customer("H", 1.0, amount = 2)
            customer("B", 2.0)
            at(6.0) { customers["H"]!!.terminateProcess() }
            at(6.5) { seen = "available=${r.numAvailableUnits}, waiting=${r.myWaitingQ.size}" }
        }
        assertEquals("available=0, waiting=0", seen, "B is now the head and fits, and is offered the free unit")
        assertEquals(listOf("X1@0.0", "X2@0.0", "B@6.0"), m.log)
    }
}
