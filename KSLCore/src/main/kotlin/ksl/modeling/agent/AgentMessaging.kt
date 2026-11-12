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

import ksl.modeling.entity.AfterResume
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ProcessModel.Companion.TIMEOUT_PRIORITY
import ksl.modeling.entity.Registration
import ksl.modeling.entity.SuspendType
import ksl.modeling.entity.WaitGroup
import ksl.modeling.entity.WaitResult
import ksl.modeling.entity.WaitTrigger

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
    try {
        suspendFor(suspension)
    } finally {
        // Deregister on *every* exit path. A normal delivery has already removed
        // this waiter inside deliver(), so the call is a no-op there. What matters
        // is the abnormal path: terminating a process resumes its continuation with
        // a ProcessTerminatedException, which unwinds through this suspension point.
        // Without this, the waiter would outlive its process, and the next matching
        // message would be handed to it -- ahead of the pending queue and any live
        // receiver -- consuming the message and then resuming a terminated process.
        mailbox.removeWaiter(waiter)
    }
    @Suppress("UNCHECKED_CAST")
    return waiter.result as M
}

/**
 *  Wait, for at most [timeout] time units, for a message matching [predicate] in [mailbox].
 *
 *  The result is satisfied with the message if one arrives in time, or is already waiting in
 *  the mailbox (then the call returns at once). Otherwise, when the time runs out, the result is
 *  timed out, nothing is taken from the mailbox, and a matching message delivered later is kept
 *  in the mailbox as if nobody had waited. While it waits, the receiver takes its place among the
 *  mailbox's waiting receivers in the order it started waiting, as with [receiveMessage].
 *
 *  A message delivered at the very instant the time runs out wins at the default priorities:
 *  the timer runs late in the instant. Give an earlier [timeoutPriority] to let the time limit
 *  win instead.
 *
 *  ```
 *  val reply = receiveMessageOrTimeout(mailbox, timeout = 5.0) { it.conversationId == convId }
 *  if (reply.timedOut) giveUp() else handle(reply.value)
 *  ```
 *
 *  @param mailbox the mailbox to receive from
 *  @param timeout the time limit; zero or more, or infinite for no limit
 *  @param timeoutPriority the priority of the event that ends the wait when the time runs out
 *  @param suspensionName a name for the suspension, for tracing
 *  @param predicate selects the messages to accept; by default, any message
 *  @return the message, or that the wait timed out, with the time waited
 */
suspend fun <M : AgentMessage> KSLProcessBuilder.receiveMessageOrTimeout(
    mailbox: AgentModel.AgentMailbox<M>,
    timeout: Double,
    timeoutPriority: Int = TIMEOUT_PRIORITY,
    suspensionName: String? = null,
    predicate: (M) -> Boolean = { true },
): WaitResult<M> {
    val trigger = mailbox.trigger(predicate)
    val result = waitForFirst(trigger, timeout = timeout, timeoutPriority = timeoutPriority, suspensionName = suspensionName)
    return if (result.timedOut) WaitResult.TimedOut(result.timeWaited)
    else WaitResult.Satisfied(result.valueOf(trigger), result.timeWaited)
}

/**
 *  Wait, for at most [timeout] time units, for a message of type T in [mailbox]. Messages of
 *  other types are left in the mailbox. See [receiveMessageOrTimeout].
 *
 *  @param mailbox the mailbox to receive from
 *  @param timeout the time limit; zero or more, or infinite for no limit
 *  @param timeoutPriority the priority of the event that ends the wait when the time runs out
 *  @param suspensionName a name for the suspension, for tracing
 *  @return the message, or that the wait timed out, with the time waited
 */
suspend inline fun <reified T : M, M : AgentMessage> KSLProcessBuilder.receiveMessageOfTypeOrTimeout(
    mailbox: AgentModel.AgentMailbox<M>,
    timeout: Double,
    timeoutPriority: Int = TIMEOUT_PRIORITY,
    suspensionName: String? = null,
): WaitResult<T> {
    @Suppress("UNCHECKED_CAST")
    return receiveMessageOrTimeout(mailbox, timeout, timeoutPriority, suspensionName) { it is T } as WaitResult<T>
}

/**
 *  The trigger of a message on a mailbox, made by `AgentMailbox.trigger`. It can fire at once
 *  when a matching message is already waiting, which it then takes; otherwise it registers a
 *  waiter on the mailbox, which a matching delivery claims.
 */
internal class MessageTrigger<M : AgentMessage>(
    val mailbox: AgentModel.AgentMailbox<M>,
    val predicate: (M) -> Boolean,
) : WaitTrigger<M>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = mailbox.hasMatch(predicate)

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): M =
        checkNotNull(mailbox.tryTake(predicate)) { "A message trigger on the mailbox of ${mailbox.owner} found no matching message to take." }

    override fun register(group: WaitGroup): Registration {
        val registration = MessageRegistration(this, group)
        registration.waiter = mailbox.registerWaiter(predicate, registration)
        return registration
    }

    override fun toString(): String = "MessageTrigger(mailbox of ${mailbox.owner.name})"
}

/**
 *  A message trigger's waiter in one wait. The delivery that matches it removes it from the
 *  mailbox and claims the wait with the message; a loser or a terminated wait removes it.
 */
internal class MessageRegistration<M : AgentMessage>(
    private val messageTrigger: MessageTrigger<M>,
    group: WaitGroup,
) : Registration(messageTrigger, group) {

    lateinit var waiter: AgentModel.AgentMailbox<M>.Waiter

    override fun onReleaseAsLoser() {
        messageTrigger.mailbox.removeWaiter(waiter)
    }

    override fun onReleaseOnTermination() {
        messageTrigger.mailbox.removeWaiter(waiter)
    }

    override suspend fun afterWinningResume(process: ProcessModel.Entity.ProcessCoroutine): AfterResume {
        // The delivery has already removed the waiter and given the message to the claim.
        markReleased()
        return AfterResume.Value(group.value)
    }
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

/**
 *  Run [block] with a **reservation** held on [mailbox]: while it is active, every
 *  message matching [predicate] is diverted into the reservation's private buffer
 *  instead of reaching any other consumer, and [block] collects them at its leisure.
 *  The reservation is released when [block] finishes, on every exit path.
 *
 *  Reach for it when a conversation spans more than one message and must not be
 *  intercepted. Delivery on a mailbox is competitive: a message is offered first to
 *  any reservation, then to a suspended [receiveMessage] waiter, and only then
 *  queued and shown to statechart `onMessage` handlers. So an agent that both runs a
 *  multi-message protocol and carries a statechart matching the same traffic has no
 *  guarantee about which one sees a given message — unless the protocol reserves it.
 *
 *  ```
 *  val replies = withReservation(mailbox, { it.conversationId == convId }) { reservation ->
 *      for (peer in peers) sendMessage(AgentMessage.Request(me, ask, convId), peer.mailbox)
 *      delay(deadline)          // replies accumulate in the reservation meanwhile
 *      reservation.collected()
 *  }
 *  ```
 *
 *  Prefer this to reserving and releasing by hand. A reservation that is never
 *  released keeps swallowing every matching message for the rest of the
 *  replication, silently — the scoped form makes that impossible, including when
 *  [block] throws or the surrounding process is terminated.
 *
 *  This is the mechanism [contractNet] uses internally for its bidding round.
 *
 *  @param mailbox the mailbox to reserve on — usually the calling agent's own
 *  @param predicate which messages this reservation captures
 *  @param block receives the live reservation; its result is returned
 */
suspend fun <M : AgentMessage, R> KSLProcessBuilder.withReservation(
    mailbox: AgentModel.AgentMailbox<M>,
    predicate: (M) -> Boolean,
    block: suspend KSLProcessBuilder.(reservation: AgentModel.AgentMailbox<M>.Reservation) -> R,
): R {
    val reservation = mailbox.reserve(predicate)
    return try {
        block(reservation)
    } finally {
        reservation.release()
    }
}
