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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Pins the order in which a delivered message is offered: a reservation first, then a waiting
 *  receiver, then the pending list and the statechart's `onMessage`. Among waiting receivers the
 *  earlier-registered is served first. A new kind of waiter joins this order, so the order is
 *  recorded before it does.
 */
class MailboxDeliveryOrderCharacterizationTest {

    private class OrderModel(parent: ModelElement, val withReservation: Boolean) : AgentModel(parent, "order") {

        val seen = mutableListOf<String>()

        inner class Hub(aName: String) : Agent(aName) {
            init {
                statechart {
                    initial("listening")
                    state("listening") {
                        onMessage<AgentMessage.Inform<String>> { msg -> seen.add("statechart:${msg.payload}@$time") }
                    }
                }
            }
        }

        val hub = Hub("hub")

        inner class Receiver(aName: String, val prefix: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val msg = receiveMessage(hub.mailbox) { m ->
                    m is AgentMessage.Inform<*> && (m.payload as String).startsWith(prefix)
                }
                seen.add("${this@Receiver.name}:${(msg as AgentMessage.Inform<*>).payload}@$time")
            }
        }

        inner class Reserver(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val got = withReservation(
                    hub.mailbox,
                    { m -> m is AgentMessage.Inform<*> && (m.payload as String).startsWith("r") },
                ) { reservation ->
                    delay(10.0)
                    reservation.collected().map { (it as AgentMessage.Inform<*>).payload as String }
                }
                for (p in got) seen.add("${this@Reserver.name}:$p@$time")
            }
        }

        override fun initialize() {
            super.initialize()
            seen.clear()
            if (withReservation) activate(Reserver("reserver").script)
            activate(Receiver("first", "r").script)
            activate(Receiver("second", "r").script)
            schedule(EventActionIfc<Nothing> {
                hub.mailbox.deliver(AgentMessage.Inform(hub, "r1"))
                hub.mailbox.deliver(AgentMessage.Inform(hub, "r2"))
                hub.mailbox.deliver(AgentMessage.Inform(hub, "x1"))
            }, 5.0)
        }
    }

    private fun run(withReservation: Boolean): List<String> {
        val m = OrderModel(Model("order${System.nanoTime()}", autoCSVReports = false), withReservation)
        m.model.numberOfReplications = 1
        m.model.lengthOfReplication = 20.0
        m.model.simulate()
        return m.seen
    }

    @Test
    @DisplayName("waiting receivers are served in registration order; an unmatched message goes to the statechart")
    fun receiversThenStatechart() {
        val seen = run(withReservation = false)
        assertEquals(listOf("first:r1@5.0", "second:r2@5.0"), seen.filter { !it.startsWith("statechart") })
        assertEquals(listOf("statechart:x1@5.0"), seen.filter { it.startsWith("statechart") })
    }

    @Test
    @DisplayName("a reservation is offered a message before any waiting receiver")
    fun reservationBeforeReceivers() {
        val seen = run(withReservation = true)
        assertEquals(listOf("reserver:r1@10.0", "reserver:r2@10.0"), seen.filter { it.startsWith("reserver") })
        assertEquals(emptyList(), seen.filter { it.startsWith("first") || it.startsWith("second") })
        assertEquals(listOf("statechart:x1@5.0"), seen.filter { it.startsWith("statechart") })
    }
}
