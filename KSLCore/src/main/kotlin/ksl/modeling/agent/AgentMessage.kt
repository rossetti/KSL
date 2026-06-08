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

import ksl.modeling.entity.ProcessModel

/**
 *  Base type for messages exchanged between agents.
 *
 *  The hierarchy is sealed at the performative level: every concrete message
 *  is ultimately one of [Request], [Inform], [Propose], [Accept], [Reject], or
 *  [Cancel]. The performatives themselves are open so that user models can
 *  add domain-specific payloads (for example, a concrete
 *  `class TaskOffer : AgentMessage.Request<Task>(...)`).
 *
 *  This vocabulary is loosely inspired by FIPA's Agent Communication Language,
 *  but kept intentionally small. It is sufficient to express Contract-Net-style
 *  task delegation, request/inform interactions, and simple negotiation.
 *
 *  The performatives are nested inside [AgentMessage] (so user code says
 *  `AgentMessage.Request(...)` rather than a bare `Request`) for two
 *  reasons: it keeps the sealed family visually associated with its parent,
 *  and it prevents collision with KSL's existing inner `Request` types in
 *  `ProcessModel.Entity` and related classes — those would otherwise shadow
 *  ours inside any `process { }` body, which is exactly where messages are
 *  constructed.
 *
 *  The [from] reference is typed as [ProcessModel.Entity] rather than the
 *  more specific `Agent` so that messages remain usable across `AgentModel`
 *  instances. Handlers that need the concrete agent type may cast.
 */
sealed class AgentMessage {

    /**
     *  The entity (typically an [AgentModel.Agent]) that sent this message.
     */
    abstract val from: ProcessModel.Entity

    /**
     *  Optional identifier that groups messages belonging to the same logical
     *  exchange (a Contract-Net call, a multi-turn negotiation, a tracked
     *  task lifecycle). Use a fresh value per conversation when grouping
     *  matters; leave `null` for one-shot messages.
     */
    abstract val conversationId: String?

    /**
     *  A request that the recipient perform some action, expressed via [payload].
     */
    open class Request<P>(
        override val from: ProcessModel.Entity,
        val payload: P,
        override val conversationId: String? = null,
    ) : AgentMessage()

    /**
     *  An informational message that conveys a fact ([payload]) to the
     *  recipient without expecting any particular response.
     */
    open class Inform<P>(
        override val from: ProcessModel.Entity,
        val payload: P,
        override val conversationId: String? = null,
    ) : AgentMessage()

    /**
     *  A proposal carrying a candidate [proposal] for the recipient to
     *  evaluate, typically in response to an earlier [Request]. Used as the
     *  bid step of Contract-Net-style interactions. The conversation id is
     *  required because a propose is meaningful only as part of an ongoing
     *  exchange.
     */
    open class Propose<P>(
        override val from: ProcessModel.Entity,
        val proposal: P,
        override val conversationId: String,
    ) : AgentMessage()

    /**
     *  Indicates acceptance of a prior [Propose] or [Request] within the
     *  same conversation.
     */
    open class Accept(
        override val from: ProcessModel.Entity,
        override val conversationId: String,
    ) : AgentMessage()

    /**
     *  Indicates rejection of a prior [Propose] or [Request] within the
     *  same conversation, optionally including a [reason].
     */
    open class Reject(
        override val from: ProcessModel.Entity,
        override val conversationId: String,
        val reason: String? = null,
    ) : AgentMessage()

    /**
     *  Indicates that the sender is withdrawing from the conversation,
     *  abandoning whatever request or task was previously in flight.
     */
    open class Cancel(
        override val from: ProcessModel.Entity,
        override val conversationId: String,
    ) : AgentMessage()
}
