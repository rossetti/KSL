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
import ksl.modeling.entity.SuspendType

/**
 *  Send [message] to [mailbox] from inside an agent's `process { }`
 *  body. Non-blocking: the mailbox is unbounded and `deliver` always
 *  succeeds. Declared `suspend` only to be callable from
 *  restricted-suspension lambdas; no actual suspension occurs.
 *
 *  If a peer is currently suspended in [receiveMessage] on [mailbox]
 *  with a matching predicate, the message is handed directly to that
 *  waiter and the peer is scheduled to resume — bypassing the pending
 *  queue entirely. Otherwise the message is queued and any registered
 *  arrival listeners (statechart hooks) are fired.
 *
 *  ```
 *  val behavior = process {
 *      sendMessage(AgentMessage.Inform(this@MyAgent, "hello"), peer.mailbox)
 *  }
 *  ```
 */
@Suppress("UnusedReceiverParameter")  // KSLProcessBuilder receiver is for DSL availability
suspend fun <M : AgentMessage> KSLProcessBuilder.sendMessage(
    message: M,
    mailbox: AgentModel.AgentMailbox<M>,
) {
    mailbox.deliver(message)
}

/**
 *  Suspend until a message matching [predicate] is available in
 *  [mailbox], then return it. Default predicate matches anything.
 *
 *  If [pending][AgentModel.AgentMailbox] already contains a matching
 *  message, returns it immediately without suspending. Otherwise the
 *  calling entity is parked via a per-call
 *  [ksl.modeling.entity.ProcessModel.Entity.Suspension], and the
 *  matching [sendMessage] from another agent will resume it directly.
 */
suspend fun <M : AgentMessage> KSLProcessBuilder.receiveMessage(
    mailbox: AgentModel.AgentMailbox<M>,
    suspensionName: String? = null,
    predicate: (M) -> Boolean = { true },
): M {
    // Fast path: a matching message is already queued.
    mailbox.tryTake(predicate)?.let { return it }

    // Slow path: register a waiter and suspend. The deliver call from a
    // peer will resume us with the matched message.
    val suspension = entity.Suspension(name = suspensionName, type = SuspendType.WAIT_FOR_ITEMS)
    val waiter = mailbox.registerWaiter(predicate, suspension)
    suspendFor(suspension)
    @Suppress("UNCHECKED_CAST")
    return waiter.result as M
}

/**
 *  Suspend until a message of type [T] arrives in [mailbox], then
 *  return it. Useful for handlers that key off the performative type.
 *
 *  ```
 *  val request = receiveMessageOfType<AgentMessage.Request<TaskOffer>, AgentMessage>(myMailbox)
 *  ```
 */
suspend inline fun <reified T : M, M : AgentMessage> KSLProcessBuilder.receiveMessageOfType(
    mailbox: AgentModel.AgentMailbox<M>,
    suspensionName: String? = null,
): T {
    @Suppress("UNCHECKED_CAST")
    return receiveMessage(mailbox, suspensionName) { it is T } as T
}
