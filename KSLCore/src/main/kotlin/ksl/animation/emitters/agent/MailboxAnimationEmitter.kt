/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.animation.emitters.agent

import ksl.animation.AnimationEvent
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.simulation.Model

/**
 * An [AgentModel.MailboxObserver] that emits [AnimationEvent.AgentMessageDelivered] on
 * each delivery and [AnimationEvent.AgentMessageConsumed] on each consumption for one
 * agent's mailbox. Attach one per agent
 * (`agent.mailbox.addObserver(MailboxAnimationEmitter(agent.name, model))`); the mailbox
 * is unchanged.
 *
 * The mailbox callbacks carry only the message and the post-operation mailbox size (not
 * the owning agent or the time), so each instance is bound to a single [agentName] and
 * reads the current simulated time from [Model.time]. The message's runtime class name is
 * used as the message type.
 *
 * @param agentName the name of the mailbox's owning agent
 * @param model the run-wide model, for its current time and animation sink
 */
class MailboxAnimationEmitter(
    private val agentName: String,
    private val model: Model
) : AgentModel.MailboxObserver<AgentMessage> {

    override fun onMessageDelivered(message: AgentMessage, currentSize: Int) {
        emit(AnimationEvent.AgentMessageDelivered(model.time, agentName, messageType(message), currentSize))
    }

    override fun onMessageConsumed(message: AgentMessage, currentSize: Int) {
        emit(AnimationEvent.AgentMessageConsumed(model.time, agentName, messageType(message), currentSize))
    }

    private fun messageType(message: AgentMessage): String =
        message.javaClass.simpleName.ifEmpty { "Message" }

    private fun emit(event: AnimationEvent) {
        val sink = model.animationSink
        if (sink.isActive) sink.emit(event)
    }
}
