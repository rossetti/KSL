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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 *  Batch 2 audit fixes for the Contract-Net helper:
 *   - H5: a competing statechart `onMessage` handler on the initiator
 *     can no longer steal bids — the conversation is captured via a
 *     mailbox reservation in isolation.
 *   - M1: a proposal whose sender is not an [AgentModel.Agent] is
 *     ignored instead of crashing with a ClassCastException.
 */
class ContractNetHardeningTest {

    // ── H5: competing statechart must not steal proposals ─────────────────────

    private class CompetingStatechartScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        data class Bid(val price: Double)

        var outcome: ContractNetOutcome<Bid>? = null
        var seenByStatechart = 0

        inner class Bidder(aName: String, val quote: Double) : Agent(aName) {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                val cfp = receiveMessage(mailbox) { it is AgentMessage.Request<*> }
                sendMessage(
                    AgentMessage.Propose(this@Bidder, Bid(quote), conversationId = cfp.conversationId!!),
                    (cfp.from as Agent).mailbox,
                )
                receiveMessage(mailbox) {
                    it.conversationId == cfp.conversationId &&
                        (it is AgentMessage.Accept || it is AgentMessage.Reject)
                }
            }
        }

        // The initiator has a statechart that *also* listens for Propose
        // on its own mailbox — a competing consumer.
        inner class Initiator : Agent("initiator") {
            init {
                statechart {
                    initial("watch")
                    state("watch") {
                        onMessage<AgentMessage.Propose<Bid>> { seenByStatechart++ }
                    }
                }
            }

            val behavior: KSLProcess = process(isDefaultProcess = true) {
                delay(0.1)
                outcome = contractNet<String, Bid>(
                    bidders = listOf(a, b),
                    callForProposals = "task",
                    deadline = 1.0,
                    selectBest = { ps -> ps.minByOrNull { it.proposal.price } },
                )
            }
        }

        val a = Bidder("a", 100.0)
        val b = Bidder("b", 75.0)
        val initiator = Initiator()

        override fun initialize() {
            super.initialize()
            activate(a.behavior)
            activate(b.behavior)
            activate(initiator.behavior)
        }
    }

    @Test
    fun competingStatechartDoesNotStealBids() {
        val model = Model("CnpStealTest")
        val s = CompetingStatechartScenario(model, "steal")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        // The reservation isolates the conversation: the statechart on the
        // initiator never saw the reserved proposals.
        assertEquals(0, s.seenByStatechart, "statechart must not consume reserved proposals")
        // And the negotiation still completed with the cheapest bid.
        assertNotNull(s.outcome)
        assertEquals(s.b, s.outcome!!.winner)
        assertEquals(75.0, s.outcome!!.winningProposal.proposal.price, 1e-9)
    }

    // ── M1: a non-Agent sender's proposal is ignored, not a CCE ───────────────

    private class NonAgentSenderScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        data class Bid(val price: Double)

        var outcome: ContractNetOutcome<Bid>? = null
        var completed = false

        /** A plain process-view entity — NOT an AgentModel.Agent. */
        inner class Ghost : Entity("ghost")

        inner class Bidder(aName: String) : Agent(aName) {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                val cfp = receiveMessage(mailbox) { it is AgentMessage.Request<*> }
                val initiatorBox = (cfp.from as Agent).mailbox
                // Forge a cheaper proposal whose sender is a non-Agent
                // entity. It must be ignored, not crash the initiator.
                sendMessage(
                    AgentMessage.Propose(Ghost(), Bid(1.0), conversationId = cfp.conversationId!!),
                    initiatorBox,
                )
                // The bidder's own (legitimate) proposal.
                sendMessage(
                    AgentMessage.Propose(this@Bidder, Bid(100.0), conversationId = cfp.conversationId!!),
                    initiatorBox,
                )
                receiveMessage(mailbox) {
                    it.conversationId == cfp.conversationId &&
                        (it is AgentMessage.Accept || it is AgentMessage.Reject)
                }
            }
        }

        inner class Initiator : Agent("initiator") {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                delay(0.1)
                outcome = contractNet<String, Bid>(
                    bidders = listOf(a),
                    callForProposals = "task",
                    deadline = 1.0,
                    selectBest = { ps -> ps.minByOrNull { it.proposal.price } },
                )
                completed = true
            }
        }

        val a = Bidder("a")
        val initiator = Initiator()

        override fun initialize() {
            super.initialize()
            activate(a.behavior)
            activate(initiator.behavior)
        }
    }

    @Test
    fun nonAgentSenderProposalIsIgnored() {
        val model = Model("CnpNonAgentTest")
        val s = NonAgentSenderScenario(model, "nonagent")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        // The initiator completed (no ClassCastException from the ghost's
        // proposal), and selected the only Agent-sent proposal even though
        // the ghost's was cheaper.
        assertEquals(true, s.completed, "initiator must finish without crashing")
        assertNotNull(s.outcome)
        assertEquals(s.a, s.outcome!!.winner)
        assertEquals(100.0, s.outcome!!.winningProposal.proposal.price, 1e-9)
    }
}
