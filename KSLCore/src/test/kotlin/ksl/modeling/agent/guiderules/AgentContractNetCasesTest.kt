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
import ksl.modeling.agent.contractNet
import ksl.modeling.agent.receiveMessageOfType
import ksl.modeling.agent.sendMessage
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A rule of the agent guide's "...delegate work with Contract-Net" (`ksl-agent.md`): the decision is sent at the
 *  close, before a bidder's timer at the same instant, so a limit of the deadline counted from the bid never cuts off
 *  a decision. A process bidder waiting with `receiveMessageOrTimeout` is checked elsewhere; this class checks the
 *  other ways a bidder can wait with a limit: a statechart bidder whose waiting state has an `onTimeout` of the
 *  deadline, and a process bidder waiting on a mailbox trigger in `waitForFirst` with the deadline as its timeout,
 *  each as the winner and as a loser.
 */
class AgentContractNetCasesTest {

    /**
     *  An initiator calls for proposals at 1 with a deadline of 1, so the round closes at 2. Both bidders bid at 1,
     *  as the call reaches them, and wait for the decision for at most 1: their timers fall at 2 too. `winner` names
     *  the proposal chosen.
     */
    private class Auction(parent: ModelElement, val winner: String) : AgentModel(parent, "Auction") {
        val log = mutableListOf<String>()

        /** Bids as the call arrives and waits in Waiting, whose time limit is the deadline. */
        inner class ChartBidder : PermanentAgent("Chart") {
            init {
                statechart {
                    initial("Idle")
                    state("Idle") {
                        onMessage<AgentMessage.Request<String>> { call ->
                            call.from.mailbox.deliver(AgentMessage.Propose(this@ChartBidder, "Chart", call.conversationId!!))
                            transitionTo("Waiting")
                        }
                    }
                    state("Waiting") {
                        onTimeout(1.0) {
                            log.add("Chart timedOut@$time")
                            transitionTo("Idle")
                        }
                        onMessage<AgentMessage.Accept> {
                            log.add("Chart Accept@$time")
                            transitionTo("Idle")
                        }
                        onMessage<AgentMessage.Reject> {
                            log.add("Chart Reject@$time")
                            transitionTo("Idle")
                        }
                    }
                }
            }
        }

        val chart = ChartBidder()

        /** Bids as the call arrives and waits on a mailbox trigger, with the deadline as its timeout. */
        inner class ProcessBidder : Agent("Process") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val cfp = receiveMessageOfType<AgentMessage.Request<String>, AgentMessage>(mailbox)
                sendMessage(AgentMessage.Propose(this@ProcessBidder, "Process", cfp.conversationId!!), cfp.from.mailbox)
                val decision = mailbox.trigger { it.conversationId == cfp.conversationId }
                val r = waitForFirst(decision, timeout = 1.0)
                if (r.timedOut) log.add("Process timedOut@$time")
                else log.add("Process ${r.valueOf(decision)::class.simpleName}@$time")
            }
        }

        inner class Initiator : Agent("Initiator") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val bidder = ProcessBidder()
                activate(bidder.script)
                delay(1.0)
                contractNet<String, String>(
                    bidders = listOf(chart, bidder),
                    callForProposals = "job",
                    deadline = 1.0,
                    selectBest = { ps -> ps.firstOrNull { it.proposal == winner } },
                )
            }
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            activate(Initiator().script)
        }
    }

    private fun auction(winner: String): List<String> {
        val model = Model("ContractNetCases${System.nanoTime()}", autoCSVReports = false)
        val a = Auction(model, winner)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        return a.log.sorted()
    }

    @Test
    @DisplayName("a statechart bidder with an onTimeout of the deadline wins, and is told so before its timeout")
    fun statechartBidderWins() {
        assertEquals(listOf("Chart Accept@2.0", "Process Reject@2.0"), auction(winner = "Chart"))
    }

    @Test
    @DisplayName("a process bidder waiting on a mailbox trigger with the deadline as its timeout wins, and is told so before its timeout")
    fun triggerBidderWins() {
        assertEquals(listOf("Chart Reject@2.0", "Process Accept@2.0"), auction(winner = "Process"))
    }
}
