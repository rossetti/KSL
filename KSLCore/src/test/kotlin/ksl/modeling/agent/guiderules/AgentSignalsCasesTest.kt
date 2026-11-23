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

package ksl.modeling.agent.guiderules

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentSignal
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A rule of the agent guide's account of `AgentSignal` (`ksl-agent.md`): the next `fire()` ends the wait of every
 *  process waiting for it, as it reaches every subscribed state. This class checks one fire reaching, together, a
 *  process waiting for the signal alone with a time limit, a process waiting for it with another trigger, and a
 *  statechart state subscribed to it; and that a process that begins to wait after a fire waits for the next one.
 */
class AgentSignalsCasesTest {

    private class Shop(parent: ModelElement) : AgentModel(parent, "Shop") {
        val call = AgentSignal("Call")
        val other = ksl.modeling.entity.Signal(this, "Other")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        /** A permanent agent whose statechart leaves Idle, logging, at the first fire it hears. */
        inner class Listener : PermanentAgent("Listener") {
            init {
                statechart {
                    initial("Idle")
                    state("Idle") {
                        onSignal(call) {
                            log.add("chart@$time")
                            transitionTo("Heard")
                        }
                    }
                    state("Heard") {}
                }
            }
        }

        val listener = Listener()

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    @Test
    @DisplayName("one fire ends a wait for the signal alone, a wait for it beside another trigger, and reaches a subscribed state")
    fun oneFireReachesEveryKindOfWaiter() {
        val model = Model("AgentSignalCases${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.setups.add {
            actor("A") {
                val c = call.trigger()
                val r = waitForFirst(c, timeout = 10.0)
                log.add("A ${if (r.firedBy(c)) "call" else "timeout"}@$time")
            }
            actor("B") {
                val c = call.trigger()
                val r = waitForFirst(other.trigger(), c)
                log.add("B ${if (r.firedBy(c)) "call" else "other"}@$time")
            }
            // C begins to wait after the first fire: only the next one reaches it
            actor("C") {
                delay(5.0)
                val c = call.trigger()
                val r = waitForFirst(c, timeout = 10.0)
                log.add("C ${if (r.firedBy(c)) "call" else "timeout"}@$time")
            }
            at(4.0) { call.fire() }
            at(8.0) { call.fire() }
        }
        model.numberOfReplications = 2
        model.lengthOfReplication = 30.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        // the log is that of the second replication: the same happens in every replication
        assertEquals(listOf("chart@4.0", "A call@4.0", "B call@4.0", "C call@8.0"), shop.log)
    }
}
