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

package ksl.modeling.agent

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Signal
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A process waits for an agent signal through its trigger in `waitForFirst`, with or without a time limit and with
 *  other triggers, as it waits for an entity signal: the first to happen ends the wait, the others are released, and
 *  a terminated wait leaves nothing subscribed.
 */
class AgentSignalWaitTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val call = AgentSignal("Call")
        val other = Signal(this, "Other")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(setup: Shop.() -> Unit): Pair<Shop, Throwable?> {
        val model = Model("AgentSignalWait${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.setups.add(setup)
        model.numberOfReplications = 2
        model.lengthOfReplication = 50.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return shop to error
    }

    @Test
    @DisplayName("a call before the time limit ends the wait when it is fired; with none, the wait times out")
    fun callOrTimeout() {
        val (shop, error) = run {
            actor("W") {
                val call = call.trigger()
                val first = waitForFirst(call, timeout = 5.0)
                log.add("first ${if (first.firedBy(call)) "call" else "timeout"}@$time")
                val second = waitForFirst(call, timeout = 5.0)
                log.add("second ${if (second.firedBy(call)) "call" else "timeout"}@$time")
            }
            at(3.0) { call.fire() }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("first call@3.0", "second timeout@8.0"), shop.log)
        assertEquals(0, shop.call.numSubscribers, "nothing is left subscribed")
    }

    @Test
    @DisplayName("one fire wakes every process waiting for the signal, and a losing trigger is released")
    fun fireWakesAllAndLosersAreReleased() {
        val (shop, error) = run {
            actor("A") {
                val c = call.trigger()
                val r = waitForFirst(c, other.trigger())
                log.add("A ${if (r.firedBy(c)) "call" else "other"}@$time")
            }
            actor("B") {
                val c = call.trigger()
                val r = waitForFirst(other.trigger(), c)
                log.add("B ${if (r.firedBy(c)) "call" else "other"}@$time")
            }
            val cActor = actor("C") {
                val c = call.trigger()
                val r = waitForFirst(c, other.trigger(), timeout = 20.0)
                log.add("C ${if (r.firedBy(c)) "call" else "other"}@$time")
            }
            at(2.0) { other.signal(cActor) }
            at(4.0) { call.fire() }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("C other@2.0", "A call@4.0", "B call@4.0"), shop.log)
        assertEquals(0, shop.call.numSubscribers, "nothing is left subscribed")
    }

    @Test
    @DisplayName("a process terminated while it waits for the signal leaves nothing subscribed, and a later fire is harmless")
    fun terminationUnsubscribes() {
        val (shop, error) = run {
            val w = actor("W") {
                waitForFirst(call.trigger(), timeout = 30.0)
                log.add("W returned@$time")
            }
            at(2.0) { w.terminateProcess() }
            at(3.0) {
                log.add("subscribers@$time=${call.numSubscribers}")
                call.fire()
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("subscribers@3.0=0"), shop.log)
    }
}
