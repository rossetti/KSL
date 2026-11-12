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
 *  and its removal changes nothing for the others.
 */
class RemoveAndTerminateTest {

    private class QModel(parent: ModelElement) : ProcessModel(parent, "removeAndTerminate") {
        val r = ResourceWithQ(this, "R")
        val other = RequestQ(this, "Other")
        val log = mutableListOf<String>()
        val setups = mutableListOf<QModel.() -> Unit>()
        val customers = mutableMapOf<String, Customer>()

        inner class Customer(
            val tag: String,
            val arrive: Double,
            val hold: Double,
            val inOther: Boolean,
            val afterRelease: (() -> Unit)?
        ) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") {
                delay(arrive)
                val a = if (inOther) seize(r, queue = other) else seize(r)
                log.add("$tag@$time")
                delay(hold)
                release(a)
                afterRelease?.invoke()
            }
        }

        fun customer(tag: String, arrive: Double, hold: Double = 1.0, inOther: Boolean = false, afterRelease: (() -> Unit)? = null) {
            val c = Customer(tag, arrive, hold, inOther, afterRelease)
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

    private fun run(setup: QModel.() -> Unit): QModel {
        val model = Model("RemoveAndTerminate${System.nanoTime()}", autoCSVReports = false)
        val m = QModel(model)
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
}
