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

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContractNetOutcome
import ksl.modeling.agent.contractNet
import ksl.modeling.agent.receiveMessageOfType
import ksl.modeling.agent.receiveMessageOrTimeout
import ksl.modeling.agent.sendMessage
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Rules stated in the agent guide (`ksl-agent.md`), each checked on the smallest model that shows it:
 *
 *  - `mailbox.triggerOfType<T>()` lets a message of type T be one of the things `waitForFirst` waits for, and
 *    passes over messages of other types ("...send and receive messages");
 *  - in a Contract-Net round, a proposal that arrives after the deadline is not collected and its sender is sent
 *    neither an `Accept` nor a `Reject`; the round closes at most the deadline after a bid is sent; and the decision
 *    is sent at the close, before a bidder's timer at the same instant, so a bidder waiting for the decision with
 *    the deadline as its limit is never cut off ("...delegate work with Contract-Net").
 */
class AgentGuideRulesTest {

    // ---------- triggerOfType ----------

    private class Hub(parent: ModelElement) : AgentModel(parent, "hubModel") {
        val log = mutableListOf<String>()

        inner class HubAgent : Agent("hub")

        val hub = HubAgent()

        inner class Receiver : Agent("receiver") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val requests = hub.mailbox.triggerOfType<AgentMessage.Request<*>>()
                val result = waitForFirst(requests, timeout = 20.0)
                if (result.timedOut) {
                    log.add("timedOut@$time")
                } else {
                    val m = result.valueOf(requests)
                    log.add("${m::class.simpleName}:${m.payload}@$time pending=${hub.mailbox.size}")
                }
            }
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            activate(Receiver().script)
            schedule({ _: KSLEvent<Nothing> -> hub.mailbox.deliver(AgentMessage.Inform(hub, "news")) }, 2.0)
            schedule({ _: KSLEvent<Nothing> -> hub.mailbox.deliver(AgentMessage.Request(hub, "job")) }, 4.0)
        }
    }

    @Test
    @DisplayName("triggerOfType fires for the first message of its type and leaves a message of another type in the mailbox")
    fun triggerOfTypeFiltersByType() {
        // an Inform arrives at 2 and stays; the Request at 4 ends the wait, and the Inform is still pending
        val model = Model("TriggerOfType${System.nanoTime()}", autoCSVReports = false)
        val h = Hub(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        assertEquals(listOf("Request:job@4.0 pending=1"), h.log)
    }

    // ---------- Contract-Net ----------

    /**
     *  The initiator calls for proposals at 1 with a deadline of 1, so the round closes at 2. Each bidder is
     *  described by when it reads the call (a delay from 0, during which it is busy), how long it takes to bid after
     *  reading it, and how long it waits for the decision.
     */
    private class Auction(parent: ModelElement) : AgentModel(parent, "auction") {
        data class Spec(val busyUntil: Double, val think: Double, val patience: Double)

        val log = mutableListOf<String>()
        val specs = linkedMapOf<String, Spec>()
        val bidAt = mutableMapOf<String, Double>()
        val decidedAt = mutableMapOf<String, Double>()
        var collected: List<String> = emptyList()
        var outcome: ContractNetOutcome<String>? = null
        var closedAt = Double.NaN
        private val bidders = mutableListOf<Bidder>()

        inner class Bidder(aName: String, val spec: Spec) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                if (spec.busyUntil > 0.0) delay(spec.busyUntil)
                val cfp = receiveMessageOfType<AgentMessage.Request<String>, AgentMessage>(mailbox)
                if (spec.think > 0.0) delay(spec.think)
                sendMessage(
                    AgentMessage.Propose(this@Bidder, this@Bidder.name, conversationId = cfp.conversationId!!),
                    (cfp.from as Agent).mailbox
                )
                bidAt[this@Bidder.name] = time
                val reply = receiveMessageOrTimeout(mailbox, spec.patience) { it.conversationId == cfp.conversationId }
                if (reply.timedOut) {
                    log.add("${this@Bidder.name} timedOut@$time")
                } else {
                    decidedAt[this@Bidder.name] = time
                    log.add("${this@Bidder.name} ${reply.value::class.simpleName}@$time")
                }
            }
        }

        inner class Initiator : Agent("initiator") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                outcome = contractNet<String, String>(
                    bidders = bidders.toList(),
                    callForProposals = "job",
                    deadline = 1.0,
                    selectBest = { ps -> collected = ps.map { it.proposal }; ps.firstOrNull() },
                )
                closedAt = time
            }
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            bidAt.clear()
            decidedAt.clear()
            collected = emptyList()
            outcome = null
            closedAt = Double.NaN
            bidders.clear()
            for ((name, spec) in specs) bidders.add(Bidder(name, spec).also { activate(it.script) })
            activate(Initiator().script)
        }
    }

    private fun auction(vararg bidders: Pair<String, Auction.Spec>): Pair<Auction, Throwable?> {
        val model = Model("ContractNetRules${System.nanoTime()}", autoCSVReports = false)
        val a = Auction(model)
        a.specs.putAll(bidders)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return a to error
    }

    @Test
    @DisplayName("a proposal arriving after the deadline is not collected, and its sender gets neither Accept nor Reject")
    fun lateProposalIsIgnored() {
        // E reads the call at 1 and bids at once; L is busy in a delay until 2.5, bids then, after the close at 2,
        // and waits 5 for a decision that never comes
        val (a, error) = auction(
            "E" to Auction.Spec(busyUntil = 0.0, think = 0.0, patience = 10.0),
            "L" to Auction.Spec(busyUntil = 2.5, think = 0.0, patience = 5.0),
        )
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("E"), a.collected, "the proposals collected")
        assertEquals("E", a.outcome?.winner?.name)
        assertEquals(listOf("E Accept@2.0", "L timedOut@7.5"), a.log)
    }

    @Test
    @DisplayName("the round closes at most the deadline after a bid is sent")
    fun roundClosesWithinTheDeadlineOfEachBid() {
        // A bids at 1, as the call is made; B thinks for 0.5 and bids at 1.5. The round closes at 2, and each
        // decision reaches its bidder no later than the deadline (1) after its bid
        val (a, error) = auction(
            "A" to Auction.Spec(busyUntil = 0.0, think = 0.0, patience = 10.0),
            "B" to Auction.Spec(busyUntil = 0.0, think = 0.5, patience = 10.0),
        )
        assertNull(error, "the run stopped: $error")
        assertEquals(2.0, a.closedAt, "the round's close")
        assertEquals(mapOf("A" to 1.0, "B" to 1.5), a.bidAt)
        assertEquals(mapOf("A" to 2.0, "B" to 2.0), a.decidedAt)
        for ((name, bid) in a.bidAt) {
            assertTrue(a.decidedAt.getValue(name) - bid <= 1.0, "$name's decision came more than the deadline after its bid")
        }
    }

    @Test
    @DisplayName("the decision is sent at the close, before a bidder's timer at the same instant, so it is never cut off")
    fun decisionBeatsATimerAtTheSameInstant() {
        // both bid at 1, as the call is made, and wait for the decision with the deadline (1) as their limit: their
        // timers fall at 2, the instant the round closes
        val (a, error) = auction(
            "A" to Auction.Spec(busyUntil = 0.0, think = 0.0, patience = 1.0),
            "B" to Auction.Spec(busyUntil = 0.0, think = 0.0, patience = 1.0),
        )
        assertNull(error, "the run stopped: $error")
        assertEquals("A", a.outcome?.winner?.name)
        assertEquals(listOf("A Accept@2.0", "B Reject@2.0"), a.log.sorted())
    }
}
