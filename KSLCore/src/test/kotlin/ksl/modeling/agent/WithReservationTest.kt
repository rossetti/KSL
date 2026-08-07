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
import kotlin.test.assertTrue

/**
 *  Phase B4 — `withReservation`, the scoped form of a mailbox reservation.
 *
 *  Delivery on a mailbox is competitive: a message goes first to any matching
 *  reservation, then to a suspended `receiveMessage` waiter, and only then to the
 *  pending queue and the statechart arrival listeners. An agent that runs a
 *  multi-message protocol *and* carries a statechart matching the same traffic has
 *  no guarantee which sees a given message — unless the protocol reserves it.
 *
 *  `AgentMailbox.reserve` stays internal. It is exposed only through this scoped
 *  builder, because the failure mode of the raw form is nasty and silent: a
 *  reservation that is never released keeps swallowing every matching message for
 *  the rest of the replication, with no error. The scoped form releases on every
 *  exit path, so that cannot happen.
 */
class WithReservationTest {

    private class ProtocolModel(
        parent: ModelElement,
        val throwInsideBlock: Boolean = false,
    ) : AgentModel(parent, "reservation") {

        val statechartSaw = mutableListOf<String>()
        val collected = mutableListOf<String>()
        var blockThrew: Boolean = false

        /** Owns the mailbox, and carries a statechart competing for the same traffic. */
        inner class Hub(aName: String) : Agent(aName) {
            init {
                statechart {
                    initial("listening")
                    state("listening") {
                        onMessage<AgentMessage.Inform<String>> { msg ->
                            statechartSaw.add(msg.payload)
                        }
                    }
                }
            }
        }

        val hub = Hub("hub")

        /** Runs the reserved conversation, then reports what it captured. */
        inner class Protocol(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                try {
                    val got = withReservation(
                        hub.mailbox,
                        { m -> m is AgentMessage.Inform<*> && (m.payload as? String)?.startsWith("bid") == true },
                    ) { reservation ->
                        delay(3.0)   // traffic arrives while we are suspended
                        if (throwInsideBlock) error("boom")
                        reservation.collected().map { (it as AgentMessage.Inform<*>).payload as String }
                    }
                    collected.addAll(got)
                } catch (e: IllegalStateException) {
                    blockThrew = true
                }
                // After the reservation is released, matching traffic must flow
                // normally to the statechart again.
                delay(1.0)
                sendMessage(AgentMessage.Inform(hub, "bid-after-release"), hub.mailbox)
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Protocol("protocol").script)
            // Two reserved messages and one unreserved, all while the block is suspended.
            schedule(EventActionIfc<Nothing> {
                hub.mailbox.deliver(AgentMessage.Inform(hub, "bid-1"))
            }, 1.0)
            schedule(EventActionIfc<Nothing> {
                hub.mailbox.deliver(AgentMessage.Inform(hub, "chatter"))
            }, 1.5)
            schedule(EventActionIfc<Nothing> {
                hub.mailbox.deliver(AgentMessage.Inform(hub, "bid-2"))
            }, 2.0)
        }
    }

    private fun run(m: ProtocolModel): ProtocolModel {
        m.model.numberOfReplications = 1
        m.model.lengthOfReplication = 10.0
        m.model.simulate()
        return m
    }

    @Test
    @DisplayName("B4: a reservation captures matching traffic away from a competing statechart")
    fun reservationIsolatesMatchingMessages() {
        val m = run(ProtocolModel(Model("isolate")))
        assertEquals(listOf("bid-1", "bid-2"), m.collected, "both bids should be captured")
        assertTrue(
            "bid-1" !in m.statechartSaw && "bid-2" !in m.statechartSaw,
            "reserved messages must not reach the statechart; saw ${m.statechartSaw}",
        )
    }

    @Test
    @DisplayName("B4: non-matching traffic is unaffected by the reservation")
    fun nonMatchingTrafficFlowsNormally() {
        val m = run(ProtocolModel(Model("passthrough")))
        assertTrue("chatter" in m.statechartSaw, "unreserved traffic should reach the statechart")
    }

    @Test
    @DisplayName("B4: the reservation is released when the block ends, so later traffic flows")
    fun releaseRestoresNormalDelivery() {
        val m = run(ProtocolModel(Model("release")))
        assertTrue(
            "bid-after-release" in m.statechartSaw,
            "after release, matching traffic must reach the statechart again; saw ${m.statechartSaw}",
        )
    }

    /**
     *  The reason the raw `reserve` stays internal: a reservation leaked by an
     *  exception would silently swallow every matching message for the rest of the
     *  replication. The scoped form releases in a `finally`, so a throwing block
     *  still restores normal delivery.
     */
    @Test
    @DisplayName("B4: a throwing block still releases the reservation")
    fun throwingBlockStillReleases() {
        val m = run(ProtocolModel(Model("throwing"), throwInsideBlock = true))
        assertTrue(m.blockThrew, "the block should have thrown")
        assertTrue(m.collected.isEmpty(), "nothing was collected")
        assertTrue(
            "bid-after-release" in m.statechartSaw,
            "delivery must be restored even though the block threw; saw ${m.statechartSaw}",
        )
    }
}
