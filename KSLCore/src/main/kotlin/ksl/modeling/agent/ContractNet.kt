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

import ksl.modeling.entity.KSLProcessBuilder
import java.util.concurrent.atomic.AtomicLong

/**
 *  Result of a successful Contract-Net negotiation. Contains the agent
 *  that won the contract and the proposal they submitted, so the caller
 *  can read the bid details (e.g., quoted completion time, price).
 */
data class ContractNetOutcome<B>(
    val winner: AgentModel.Agent,
    val winningProposal: AgentMessage.Propose<B>,
)

private val conversationCounter = AtomicLong(0)

/**
 *  Generate a process-global, JVM-unique conversation id. Exposed for
 *  callers that want to construct messages with a pre-allocated id
 *  (rare). Note: [contractNet] does **not** use this — it draws from a
 *  per-model counter reset each replication, so its ids are reproducible
 *  run-to-run and isolated between models. If you mix manual ids with
 *  `contractNet` on the same mailboxes, pass a distinct [prefix] here to
 *  avoid any overlap with the default `"cnp"` namespace.
 */
fun nextConversationId(prefix: String = "cnp"): String =
    "$prefix-${conversationCounter.incrementAndGet()}"

/**
 *  Run a Contract-Net Protocol negotiation from inside the calling
 *  agent's process. The calling agent (the *initiator*) sends a
 *  [AgentMessage.Request] with payload [callForProposals] to every
 *  agent in [bidders], waits for [deadline] simulated time units, then
 *  collects the [AgentMessage.Propose] messages that arrived with the
 *  matching conversation id. The [selectBest] function picks the
 *  winning proposal (default: first received).
 *
 *  Proposals for this conversation are captured via a mailbox
 *  *reservation* held for the duration of the call, so they are
 *  isolated from any other consumer on the initiator's mailbox — a
 *  statechart `onMessage<Propose>` handler or a concurrent
 *  `receiveMessage` cannot steal bids. Only proposals whose sender is
 *  an [AgentModel.Agent] are considered (a non-Agent
 *  [ksl.modeling.entity.ProcessModel.Entity] has no mailbox to notify
 *  and cannot be the winner); any others are ignored.
 *
 *  The winner is sent [AgentMessage.Accept]; every other agent that
 *  proposed is sent [AgentMessage.Reject]. Proposals that arrive after
 *  the reservation is released (e.g. after the deadline) are not
 *  collected and flow to the initiator's mailbox normally.
 *
 *  Returns the [ContractNetOutcome] for the winner, or `null` if no
 *  bidder proposed, or if [selectBest] returned `null`.
 *
 *  This helper must be called from inside the initiator's `process { }`
 *  body so it can suspend on `delay` and `sendMessage`. The initiator
 *  is taken from the calling [KSLProcessBuilder.entity], which must be
 *  an [AgentModel.Agent].
 *
 *  Type parameters:
 *    - [Q] the payload type of the call-for-proposals
 *    - [B] the payload type expected on incoming [AgentMessage.Propose]
 *      messages. Due to JVM type erasure this is a compile-time
 *      contract, not a runtime check; bidders that respond with a
 *      `Propose<X>` for some other `X` will still be visible to this
 *      call and the unchecked cast to `Propose<B>` happens internally.
 *      Keep the bid type consistent within a model.
 */
suspend fun <Q, B> KSLProcessBuilder.contractNet(
    bidders: List<AgentModel.Agent>,
    callForProposals: Q,
    deadline: Double,
    selectBest: (List<AgentMessage.Propose<B>>) -> AgentMessage.Propose<B>? = { it.firstOrNull() },
): ContractNetOutcome<B>? {
    val initiator = entity as? AgentModel.Agent
        ?: error("contractNet can only be called from inside an AgentModel.Agent's process { }.")

    // Per-model, per-replication counter → reproducible, model-isolated.
    val convId = initiator.agentModel.nextConversationId()

    // 1. Reserve this conversation BEFORE broadcasting, so the proposals
    //    are captured in isolation. Without this, a statechart
    //    `onMessage<Propose>` handler (or any other receiver) on the
    //    initiator's own mailbox could consume bids before we collect
    //    them.
    val reservation = initiator.mailbox.reserve { m ->
        m is AgentMessage.Propose<*> && m.conversationId == convId
    }

    try {
        // 2. Broadcast the call for proposals.
        for (bidder in bidders) {
            sendMessage(
                AgentMessage.Request(initiator, callForProposals, conversationId = convId),
                bidder.mailbox,
            )
        }

        // 3. Wait for the deadline. Proposals accumulate in the
        //    reservation while we are suspended.
        if (deadline > 0.0) delay(deadline)

        // 4. Collect the captured proposals. Only proposals whose sender
        //    is an AgentModel.Agent are considered: a non-Agent
        //    ProcessModel.Entity has no mailbox to Accept/Reject and
        //    cannot be the typed winner. (AgentMessage.from is a
        //    ProcessModel.Entity, so this guards the casts below.)
        @Suppress("UNCHECKED_CAST")
        val proposals = reservation.collected()
            .asSequence()
            .filterIsInstance<AgentMessage.Propose<*>>()
            .filter { it.from is AgentModel.Agent }
            .toList() as List<AgentMessage.Propose<B>>

        // 5. Select a winner.
        val winning = selectBest(proposals)

        // 6. Notify everyone who proposed (sender guaranteed an Agent).
        if (winning != null) {
            val winner = winning.from as AgentModel.Agent
            sendMessage(AgentMessage.Accept(initiator, convId), winner.mailbox)
            for (p in proposals) {
                if (p !== winning) {
                    val loser = p.from as AgentModel.Agent
                    sendMessage(AgentMessage.Reject(initiator, convId, "not selected"), loser.mailbox)
                }
            }
            return ContractNetOutcome(winner, winning)
        } else {
            // No acceptable proposal — reject everyone who responded.
            for (p in proposals) {
                val loser = p.from as AgentModel.Agent
                sendMessage(
                    AgentMessage.Reject(initiator, convId, "no acceptable proposal"),
                    loser.mailbox,
                )
            }
            return null
        }
    } finally {
        reservation.release()
    }
}
