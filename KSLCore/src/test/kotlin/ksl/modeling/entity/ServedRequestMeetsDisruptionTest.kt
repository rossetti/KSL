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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A request the library serves is allocated its units at that moment, so a failure, a capacity cut or a shift's end
 *  in the same instant, before its entity resumes, meets it as a holder: it is served, and what happens to its work is
 *  what happens to any holder's. Each case: the holder H has the only unit (or the pool's member) until 5; N waits
 *  from 1; at 5 H releases, which serves N, and in the same step makes the disruption; N then works for 1.
 */
class ServedRequestMeetsDisruptionTest {

    private var myInvariantsWereEnabled = false

    @BeforeEach
    fun enableInvariants() {
        myInvariantsWereEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = true
    }

    @AfterEach
    fun restoreInvariants() {
        ResourceInvariants.enabled = myInvariantsWereEnabled
    }

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val r = ResourceWithQ(this, "R")
        val a = ResourceWithQ(this, "A")
        val b = ResourceWithQ(this, "B")
        val pool = ResourcePoolWithQ(this, listOf(a, b), name = "Pool")
        val signal = Signal(this, "Signal")
        val log = mutableListOf<String>()
        val plans = mutableListOf<Shop.() -> Unit>()

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = KSLEvent.DEFAULT_PRIORITY + 1)
        }

        override fun initialize() {
            log.clear()
            for (p in plans) p()
        }
    }

    private fun run(plan: Shop.() -> Unit): Shop {
        val model = Model("Served${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.plans.add(plan)
        model.lengthOfReplication = 50.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return shop
    }

    /** H holds R to 5, releases it and at once runs [disrupt]; N seizes R at 1 and works 1. */
    private fun Shop.releaseThen(disrupt: Shop.() -> Unit) {
        actor("H") { val x = seize(r); delay(5.0); release(x); disrupt() }
        actor("N") { delay(1.0); val x = seize(r); log.add("N got@$time"); delay(1.0); release(x); log.add("N done@$time") }
    }

    @Test
    @DisplayName("a failure in the instant a request is served meets it as a holder, under each interrupt rule")
    fun failure() {
        for ((rule, done) in listOf(InterruptRule.FINISH to 6.0, InterruptRule.RESUME to 9.0, InterruptRule.RESTART to 9.0)) {
            val shop = run {
                releaseThen { r.fail(rule) }
                at(8.0) { r.repair() }
            }
            // FINISH: the work goes on through the failure; RESUME and RESTART: it is held until the repair at 8 and
            // then takes its 1 (in full under RESTART, since none was done)
            assertEquals(listOf("N got@5.0", "N done@$done"), shop.log, "$rule")
        }
    }

    @Test
    @DisplayName("a capacity cut in the instant a request is served meets it as a holder, under IGNORE and under WAIT")
    fun capacityCut() {
        for (rule in CapacityChangeRule.entries) {
            val seen = mutableListOf<String>()
            val shop = run {
                r.capacityChangeRule = rule
                releaseThen { r.changeCapacity(r.CapacityChangeNotice(capacity = 0)) }
                at(5.5) { seen.add("capacity=${r.capacity} pending=${r.isPendingCapacityChange}") }
                at(6.5) { seen.add("capacity=${r.capacity} pending=${r.isPendingCapacityChange}") }
            }
            assertEquals(listOf("N got@5.0", "N done@6.0"), shop.log, "$rule")
            // IGNORE cuts at once and waits for N's unit; WAIT keeps the capacity until N releases it
            val expected = if (rule == CapacityChangeRule.IGNORE) {
                listOf("capacity=0 pending=true", "capacity=0 pending=false")
            } else {
                listOf("capacity=1 pending=true", "capacity=0 pending=false")
            }
            assertEquals(expected, seen, "$rule")
        }
    }

    @Test
    @DisplayName("a shift's end in the instant a request is served meets it as a holder")
    fun shiftEnd() {
        val shop = run { releaseThen { r.goOffShift() } }
        assertEquals(listOf("N got@5.0", "N done@6.0"), shop.log)
    }

    @Test
    @DisplayName("a pool member cut in the instant a pool request is served is the member the request holds")
    fun poolMemberCut() {
        val shop = run {
            actor("HA") { val x = seize(a); delay(5.0); release(x); a.changeCapacity(a.CapacityChangeNotice(capacity = 0)) }
            actor("HB") { val x = seize(b); delay(40.0); release(x) }
            actor("N") {
                delay(1.0)
                val x = seize(pool)
                log.add("N got ${x.allocations.single().myResource.name}@$time")
                delay(1.0)
                release(x)
                log.add("N done@$time")
            }
        }
        assertEquals(listOf("N got A@5.0", "N done@6.0"), shop.log)
    }

    @Test
    @DisplayName("a seize trigger served in the instant its resource fails wins its wait and holds the unit; the other triggers are withdrawn")
    fun seizeTriggerAndFailure() {
        val shop = run {
            actor("H") { val x = seize(r); delay(5.0); release(x); r.fail(InterruptRule.RESUME) }
            actor("N") {
                delay(1.0)
                val seized = r.seizeTrigger()
                val signalled = signal.trigger()
                val result = waitForFirst(seized, signalled, timeout = 20.0)
                log.add("N won ${if (result.firedBy(seized)) "seize" else "other"}@$time")
                result.valueOrNull(seized)?.let { x -> delay(1.0); release(x) }
                log.add("N done@$time")
            }
            at(6.0) { log.add("signal reaches ${signal.waitingQ.size}"); signal.signalAll() }
            at(8.0) { r.repair() }
        }
        // the signal at 6 finds no one waiting; N's work is held by the failure until the repair at 8
        assertEquals(listOf("N won seize@5.0", "signal reaches 0", "N done@9.0"), shop.log)
    }
}
