package ksl.modeling.agent

import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** Any kind of agent can send under its own name, and take part in Contract-Net. */
@DisplayName("The sender of an agent message")
class AgentSenderTest {

    private class DeskModel(parent: ModelElement) : AgentModel(parent, "DeskModel") {
        inner class Desk : PermanentAgent("Desk") {
            init {
                statechart {
                    initial("Open")
                    state("Open") {
                        onMessage<AgentMessage.Request<String>> { question ->
                            question.from.mailbox.deliver(AgentMessage.Inform(this@Desk, "answer"))
                        }
                    }
                }
            }
        }

        val desk = Desk()
        var replyFrom: AgentLike? = null

        inner class Customer : Agent("Customer") {
            val p: KSLProcess = process {
                sendMessage(AgentMessage.Request(this@Customer, "question"), desk.mailbox)
                replyFrom = receiveMessageOfType<AgentMessage.Inform<String>, AgentMessage>(this@Customer.mailbox).from
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Customer().p)
        }
    }

    @Test
    @DisplayName("a permanent agent's reply names the permanent agent")
    fun permanentAgentRepliesUnderItsOwnName() {
        val model = Model("AgentSenderDesk", autoCSVReports = false)
        val m = DeskModel(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        assertSame(m.desk, m.replyFrom)
    }

    private class AuctionModel(parent: ModelElement, private val cheapest: String) : AgentModel(parent, "AuctionModel") {
        val results = mutableMapOf<String, String>()

        inner class DeskBidder(private val price: Double) : PermanentAgent("DeskBidder") {
            init {
                statechart {
                    initial("Open")
                    state("Open") {
                        onMessage<AgentMessage.Request<String>> { call ->
                            call.from.mailbox.deliver(AgentMessage.Propose(this@DeskBidder, price, call.conversationId!!))
                        }
                        onMessage<AgentMessage.Accept> { results[this@DeskBidder.name] = "accepted" }
                        onMessage<AgentMessage.Reject> { results[this@DeskBidder.name] = "rejected" }
                    }
                }
            }
        }

        inner class TransientBidder(private val price: Double) : Agent("TransientBidder") {
            val p: KSLProcess = process {
                val call = receiveMessage(mailbox) { it is AgentMessage.Request<*> }
                sendMessage(AgentMessage.Propose(this@TransientBidder, price, call.conversationId!!), call.from.mailbox)
            }
        }

        val desk = DeskBidder(if (cheapest == "desk") 1.0 else 5.0)
        inner class Crane : AgentResource(this@AuctionModel, "Crane") {
            init {
                statechart {
                    initial("Open")
                    state("Open") {
                        onMessage<AgentMessage.Request<String>> { call ->
                            call.from.mailbox.deliver(
                                AgentMessage.Propose(this@Crane, if (cheapest == "crane") 1.0 else 7.0, call.conversationId!!)
                            )
                        }
                    }
                }
            }
        }

        val crane = Crane()
        var outcome: ContractNetOutcome<Double>? = null

        inner class Initiator : Agent("Initiator") {
            val p: KSLProcess = process {
                val walker = TransientBidder(if (cheapest == "transient") 1.0 else 9.0)
                activate(walker.p)
                delay(0.1)
                outcome = contractNet<String, Double>(
                    bidders = listOf(desk, crane, walker),
                    callForProposals = "job",
                    deadline = 1.0,
                    selectBest = { ps -> ps.minByOrNull { it.proposal } },
                )
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Initiator().p)
        }
    }

    private fun auction(cheapest: String): AuctionModel {
        val model = Model("AgentSenderAuction$cheapest", autoCSVReports = false)
        val m = AuctionModel(model, cheapest)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("a permanent agent bids and wins, and is told so")
    fun permanentAgentWins() {
        val m = auction("desk")
        assertSame(m.desk, assertNotNull(m.outcome).winner)
        assertEquals(1.0, m.outcome!!.winningProposal.proposal)
        assertEquals("accepted", m.results["DeskBidder"])
    }

    @Test
    @DisplayName("an agent resource bids and wins")
    fun agentResourceWins() {
        val m = auction("crane")
        assertSame(m.crane, assertNotNull(m.outcome).winner)
    }

    @Test
    @DisplayName("a permanent agent that loses to a transient agent is rejected")
    fun permanentAgentLoses() {
        val m = auction("transient")
        assertEquals("TransientBidder", assertNotNull(m.outcome).winner.name)
        assertEquals("rejected", m.results["DeskBidder"])
    }
}
