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
 *  Pins today's behaviour of a resource's request queue in the situations a seize with a time limit
 *  will change or must leave alone: a request selection rule choosing among waiting requests, an
 *  external resume of an entity waiting in a seize, and `removeAndTerminate` on a request that was
 *  woken or that a rule had chosen.
 *
 *  Each entity is created at a given time, seizes an amount of the one resource, logs when it is
 *  served, holds the units for a while, and releases them.
 */
class SeizeQueueCharacterizationTest {

    private class QModel(parent: ModelElement, capacity: Int) : ProcessModel(parent, "seizeQ") {
        val r = ResourceWithQ(this, "R", capacity)
        val q: RequestQ get() = r.myWaitingQ
        val log = mutableListOf<String>()
        val setups = mutableListOf<QModel.() -> Unit>()
        val customers = mutableMapOf<String, Customer>()

        inner class Customer(
            val tag: String,
            val arrive: Double,
            val amount: Int,
            val hold: Double,
            val afterRelease: (() -> Unit)? = null
        ) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") {
                delay(arrive)
                val a = seize(r, amount)
                log.add("$tag@$time")
                delay(hold)
                release(a)
                afterRelease?.invoke()
            }
        }

        fun customer(tag: String, arrive: Double, amount: Int = 1, hold: Double = 1.0, afterRelease: (() -> Unit)? = null) {
            val c = Customer(tag, arrive, amount, hold, afterRelease)
            customers[tag] = c
            activate(c.p)
        }

        fun requestOf(tag: String): ProcessModel.Entity.Request = q.toList().first { it.entity === customers[tag] }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t)
        }

        val state: String get() = "available=${r.numAvailableUnits}, waiting=${q.size}"

        override fun initialize() {
            log.clear()
            customers.clear()
            for (s in setups) s()
        }
    }

    private fun run(capacity: Int = 1, setup: QModel.() -> Unit): QModel {
        val model = Model("SeizeQ${System.nanoTime()}", autoCSVReports = false)
        val m = QModel(model, capacity)
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    // ── a request selection rule ────────────────────────────────────────

    @Test
    @DisplayName("a request selection rule decides which waiting request a release serves")
    fun requestSelectionRule() {
        val withoutRule = run {
            customer("X", 0.0, hold = 5.0)
            customer("B1", 1.0)
            customer("B2", 2.0)
        }
        assertEquals(listOf("X@0.0", "B1@5.0", "B2@6.0"), withoutRule.log)

        val newestFirst = run {
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, queue ->
                queue.toList().filter { it.resource == resource }.takeLast(1)
            }
            customer("X", 0.0, hold = 5.0)
            customer("B1", 1.0)
            customer("B2", 2.0)
        }
        assertEquals(listOf("X@0.0", "B2@5.0", "B1@6.0"), newestFirst.log)
    }

    // ── an external resume of an entity waiting in a seize ──────────────

    @Test
    @DisplayName("resumeProcess() with no units free: the entity looks, finds nothing, and waits on in the queue")
    fun externalResumeNoUnits() {
        var seen = ""
        val m = run {
            customer("X", 0.0, hold = 10.0)
            customer("A", 1.0)
            at(3.0) { customers["A"]!!.resumeProcess() }
            at(4.0) { seen = state }
        }
        assertEquals("available=0, waiting=1", seen)
        assertEquals(listOf("X@0.0", "A@10.0"), m.log)
    }

    @Test
    @DisplayName("resumeProcess() with a unit free that no release offered it: the entity takes it")
    fun externalResumeUnitsFree() {
        var before = ""
        val m = run {
            // the rule offers freed units to nobody, so the freed unit stays free beside A
            q.requestSelectionRule = RequestSelectionRuleIfc { _, _ -> emptyList() }
            customer("X", 0.0, hold = 5.0)
            customer("A", 1.0)
            at(6.0) { before = state }
            at(7.0) { customers["A"]!!.resumeProcess() }
        }
        assertEquals("available=1, waiting=1", before)
        assertEquals(listOf("X@0.0", "A@7.0"), m.log)
    }

    // ── removeAndTerminate on a woken request ───────────────────────────

    @Test
    @DisplayName("removeAndTerminate on a request woken in the same instant offers its unit to the next waiter")
    fun removeAndTerminateWokenRequest() {
        var seen = ""
        val m = run {
            customer("X", 0.0, hold = 5.0, afterRelease = {
                // the release has just woken A; A has not yet resumed
                q.removeAndTerminate(requestOf("A"))
            })
            customer("A", 1.0)
            customer("B", 2.0)
            at(5.5) { seen = state }
        }
        assertEquals("available=0, waiting=0", seen, "the unit promised to A goes to B")
        assertEquals(listOf("X@0.0", "B@5.0"), m.log)
    }

    // ── removeAndTerminate under a rule that limits selection ───────────

    @Test
    @DisplayName("removeAndTerminate on the request a rule had chosen lets the rule choose again at once")
    fun removeAndTerminateUnderRule() {
        var seen = ""
        val m = run(capacity = 2) {
            // only the head of the queue may be served
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, queue ->
                listOfNotNull(queue.toList().firstOrNull { it.resource == resource })
            }
            customer("X1", 0.0, hold = 5.0)
            customer("X2", 0.0, hold = 10.0)
            customer("H", 1.0, amount = 2)
            customer("B", 2.0)
            at(6.0) { q.removeAndTerminate(requestOf("H")) }
            at(6.5) { seen = state }
        }
        assertEquals("available=0, waiting=0", seen, "B is now the head and fits, and is offered the free unit")
        assertEquals(listOf("X1@0.0", "X2@0.0", "B@6.0"), m.log)
    }
}
