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

import ksl.examples.general.agent.JobShopExample
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Regression tests for the Phase 1c Contract-Net helper plus a smoke
 *  test of the JobShopExample worked example.
 */
class ContractNetTest {

    /**
     *  A small Contract-Net scenario with one initiator and three
     *  bidders. Each bidder quotes a distinct fixed price; the lowest
     *  price wins. After the round, we verify:
     *    - all bidders received the CFP
     *    - the initiator collected three proposals
     *    - the cheapest bidder received Accept; the other two Reject
     *    - the outcome contains the winning bidder + proposal
     */
    private class CnpScenario(parent: ModelElement, name: String? = null) : AgentModel(parent, name) {

        data class CallForBids(val task: String)
        data class Bid(val price: Double)

        var receivedOutcome: ContractNetOutcome<Bid>? = null
        var initiatorReturnedAt: Double = Double.NaN

        // Per-bidder bookkeeping
        val cfpsReceivedBy: MutableMap<String, Int> = mutableMapOf("a" to 0, "b" to 0, "c" to 0)
        val decisionsReceivedBy: MutableMap<String, MutableList<String>> = mutableMapOf(
            "a" to mutableListOf(), "b" to mutableListOf(), "c" to mutableListOf()
        )

        inner class Bidder(aName: String, val quote: Double) : Agent(aName) {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                val cfp = receiveMessageOfType<AgentMessage.Request<CallForBids>, AgentMessage>(mailbox)
                cfpsReceivedBy.merge(this@Bidder.name, 1) { a, b -> a + b }

                sendMessage(
                    AgentMessage.Propose(
                        from = this@Bidder,
                        proposal = Bid(quote),
                        conversationId = cfp.conversationId!!,
                    ),
                    (cfp.from as Agent).mailbox,
                )

                val decision = receiveMessage(mailbox) { msg ->
                    msg.conversationId == cfp.conversationId &&
                        (msg is AgentMessage.Accept || msg is AgentMessage.Reject)
                }
                decisionsReceivedBy[this@Bidder.name]!!.add(decision::class.simpleName!!)
            }
        }

        inner class Initiator : Agent("initiator") {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                delay(0.1)  // let bidders start listening first
                receivedOutcome = contractNet<CallForBids, Bid>(
                    bidders = listOf(a, b, c),
                    callForProposals = CallForBids("paint house"),
                    deadline = 1.0,
                    selectBest = { proposals -> proposals.minByOrNull { it.proposal.price } },
                )
                initiatorReturnedAt = currentTime
            }
        }

        val a = Bidder("a", quote = 100.0)
        val b = Bidder("b", quote = 75.0)   // cheapest → should win
        val c = Bidder("c", quote = 110.0)
        val initiator = Initiator()

        override fun initialize() {
            super.initialize()
            activate(a.behavior)
            activate(b.behavior)
            activate(c.behavior)
            activate(initiator.behavior)
        }
    }

    @Test
    fun contractNetRoutesAcceptToLowestBidderAndRejectToOthers() {
        val model = Model("CnpScenarioTest")
        val s = CnpScenario(model, "cnp")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        // All bidders saw a CFP exactly once.
        assertEquals(1, s.cfpsReceivedBy["a"])
        assertEquals(1, s.cfpsReceivedBy["b"])
        assertEquals(1, s.cfpsReceivedBy["c"])

        // The cheapest bidder (b) was accepted; the other two were rejected.
        assertEquals(listOf("Reject"), s.decisionsReceivedBy["a"])
        assertEquals(listOf("Accept"), s.decisionsReceivedBy["b"])
        assertEquals(listOf("Reject"), s.decisionsReceivedBy["c"])

        // Outcome is the cheapest bid.
        assertNotNull(s.receivedOutcome)
        assertEquals(s.b, s.receivedOutcome!!.winner)
        assertEquals(75.0, s.receivedOutcome!!.winningProposal.proposal.price, 1e-9)

        // Initiator returned at t = 0.1 (initial delay) + 1.0 (deadline) = 1.1
        assertEquals(1.1, s.initiatorReturnedAt, 1e-9)
    }

    /**
     *  CNP with no bidders that respond — should return null without
     *  raising and the initiator should still complete normally.
     */
    private class NoResponseScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var outcome: ContractNetOutcome<String>? = null
        var initiatorDone: Boolean = false

        // A "silent" bidder that never reads its mailbox.
        inner class SilentBidder : Agent("silent")

        inner class Initiator : Agent("initiator") {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                outcome = contractNet<String, String>(
                    bidders = listOf(silent),
                    callForProposals = "anybody?",
                    deadline = 1.0,
                )
                initiatorDone = true
            }
        }

        val silent = SilentBidder()
        val initiator = Initiator()

        override fun initialize() {
            super.initialize()
            activate(initiator.behavior)
        }
    }

    @Test
    fun contractNetReturnsNullWhenNoBidderResponds() {
        val model = Model("NoResponseTest")
        val s = NoResponseScenario(model, "noresp")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertNull(s.outcome, "no proposals → null outcome")
        assertTrue(s.initiatorDone, "initiator should complete its process even with no bidders")
    }

    /**
     *  CNP where selectBest returns null (no proposal acceptable) —
     *  outcome is null; all responding bidders should still get Reject.
     */
    private class AllRejectedScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        data class Bid(val price: Double)

        var outcome: ContractNetOutcome<Bid>? = null
        var receivedRejects = 0

        inner class Bidder(aName: String) : Agent(aName) {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                val cfp = receiveMessage(mailbox) { it is AgentMessage.Request<*> }
                sendMessage(
                    AgentMessage.Propose(
                        from = this@Bidder,
                        proposal = Bid(999.0),
                        conversationId = cfp.conversationId!!,
                    ),
                    (cfp.from as Agent).mailbox,
                )
                val d = receiveMessage(mailbox) { it.conversationId == cfp.conversationId }
                if (d is AgentMessage.Reject) receivedRejects += 1
            }
        }

        inner class Initiator : Agent("initiator") {
            val behavior: KSLProcess = process(isDefaultProcess = true) {
                outcome = contractNet<String, Bid>(
                    bidders = listOf(x, y),
                    callForProposals = "task",
                    deadline = 1.0,
                    selectBest = { null },  // reject everything
                )
            }
        }

        val x = Bidder("x")
        val y = Bidder("y")
        val initiator = Initiator()

        override fun initialize() {
            super.initialize()
            activate(x.behavior)
            activate(y.behavior)
            activate(initiator.behavior)
        }
    }

    @Test
    fun contractNetRejectsAllRespondersWhenSelectorReturnsNull() {
        val model = Model("AllRejectedTest")
        val s = AllRejectedScenario(model, "allrej")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertNull(s.outcome)
        assertEquals(2, s.receivedRejects, "both bidders should have been rejected")
    }

    // ── JobShopExample smoke test ────────────────────────────────────────────

    @Test
    fun jobShopExampleRunsAndAssignsJobs() {
        val model = Model("JobShopSmokeTest")
        val sys = JobShopExample(model, "jobshop")
        model.lengthOfReplication = 500.0
        model.numberOfReplications = 1
        model.simulate()

        // With arrival mean 8 and a 500-unit run, we expect roughly 60 jobs.
        // The smoke test just confirms the model ran and at least one job
        // was successfully assigned.
        assertTrue(
            sys.dispatcher.jobsAssigned > 0,
            "JobShopExample should assign at least one job; got ${sys.dispatcher.jobsAssigned}"
        )
    }
}
