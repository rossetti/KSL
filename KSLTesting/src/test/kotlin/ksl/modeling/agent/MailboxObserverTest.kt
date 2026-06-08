/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Phase A of the station-integration API asks: [AgentModel.AgentMailbox.addObserver]
 *  is public, so cross-view integration code (or any external observer)
 *  can watch a mailbox's traffic. This test compiles and runs from a
 *  separate module, which on its own proves the member is public —
 *  `internal` would not resolve here.
 */
class MailboxObserverTest {

    private class ObservedModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var delivered = 0
        var consumed = 0

        // A mailbox owner whose statechart consumes an arriving Inform.
        inner class Box : PermanentAgent("box") {
            init {
                statechart {
                    initial("idle")
                    state("idle") {
                        onMessage<AgentMessage.Inform<String>> { /* consumes the message */ }
                    }
                }
            }
        }

        inner class Sender : Agent("sender") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                sendMessage(AgentMessage.Inform(this@Sender, "ping"), box.mailbox)
            }
        }

        val box = Box()
        val sender = Sender()

        // An external (non-stats) observer — the integration use case.
        val observer = object : MailboxObserver<AgentMessage> {
            override fun onMessageDelivered(message: AgentMessage, currentSize: Int) { delivered++ }
            override fun onMessageConsumed(message: AgentMessage, currentSize: Int) { consumed++ }
        }

        init {
            box.mailbox.addObserver(observer)
        }

        override fun initialize() {
            super.initialize()
            activate(sender.script)
        }
    }

    @Test
    fun externalObserverSeesDeliveryAndConsumption() {
        val model = Model("MailboxObserverTest")
        val m = ObservedModel(model, "obs")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(1, m.delivered, "external observer should see the delivery")
        assertEquals(1, m.consumed, "external observer should see the consumption")
        assertEquals(1, m.box.mailbox.observerCount, "one observer attached")

        // removeObserver drops it.
        m.box.mailbox.removeObserver(m.observer)
        assertEquals(0, m.box.mailbox.observerCount, "observer should be removable")
    }
}
