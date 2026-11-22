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
import ksl.modeling.entity.Signal
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNull

/**
 *  An agent signal, a message or a process-view signal sent from `replicationEnded()`, with a statechart subscribed
 *  and a process waiting: the replication has ended, so nothing reacts, and the run ends normally.
 */
class EndOfReplicationStatechartTest {

    private class Shop(parent: ModelElement, val withChart: Boolean, val sendAtEnd: Shop.() -> Unit) : AgentModel(parent, "Shop") {
        val pulse = AgentSignal("pulse")
        val sig = Signal(this, "S")

        inner class Walker : Agent("walker") {
            init {
                if (withChart) statechart {
                    initial("Idle")
                    state("Idle") {
                        onSignal(pulse) { transitionTo("Active") }
                        onMessage<AgentMessage.Inform<*>>({ true }) { _ -> transitionTo("Active") }
                    }
                    state("Active") {}
                }
            }
        }

        val walker = Walker()

        inner class Waiter : Entity("W") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                waitForFirst(pulse.trigger(), sig.trigger(), walker.mailbox.trigger())
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Waiter().p)
        }

        override fun replicationEnded() {
            super.replicationEnded()
            sendAtEnd()
        }
    }

    private fun run(withChart: Boolean = true, sendAtEnd: Shop.() -> Unit): Throwable? {
        val model = Model("EndOfRun${System.nanoTime()}", autoCSVReports = false)
        Shop(model, withChart, sendAtEnd)
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        return try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
    }

    @Test
    @DisplayName("an agent signal fired in replicationEnded reaches no statechart, and the run ends normally")
    fun agentSignalFiredFromReplicationEnded() {
        val error = run { pulse.fire() }
        assertNull(error, "the run stopped: $error")
    }

    @Test
    @DisplayName("an agent signal fired in replicationEnded with only a process waiting ends the run normally")
    fun agentSignalFiredFromReplicationEndedWithoutAStatechart() {
        val error = run(withChart = false) { pulse.fire() }
        assertNull(error, "the run stopped: $error")
    }

    @Test
    @DisplayName("a message sent in replicationEnded to a statechart's agent ends the run normally")
    fun messageSentFromReplicationEnded() {
        val error = run { walker.mailbox.deliver(AgentMessage.Inform(walker, "m")) }
        assertNull(error, "the run stopped: $error")
    }

    @Test
    @DisplayName("a process-view signal sent in replicationEnded ends the run normally")
    fun processSignalFromReplicationEnded() {
        val error = run { sig.signalAll() }
        assertNull(error, "the run stopped: $error")
    }
}
