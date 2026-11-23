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

package ksl.modeling.entity

import ksl.simulation.ModelElement

/**
 *  The trigger of items from a blocking queue, made by `BlockingQueue.itemsTrigger` (an amount of
 *  matching items) or `BlockingQueue.anyItemsTrigger` (every matching item, at least one). It can
 *  fire at once when no request is waiting and the channel holds what it needs. With requests waiting,
 *  it places its request in the queue's request queue, and is filled at once if the queue's request
 *  selector chooses it, as the untimed receive is; otherwise the queue claims the wait when it later
 *  selects the request. When the channel already holds what it needs, its request is placed ahead of
 *  the wait's other triggers, so that it can be filled at once; otherwise it is placed in argument
 *  order with them.
 *
 *  @param amount the number of items wanted, or null for every matching item
 */
internal class ItemsTrigger<T : ModelElement.QObject>(
    val blockingQ: BlockingQueue<T>,
    val amount: Int?,
    val predicate: (T) -> Boolean,
    val blockingPriority: Int?
) : WaitTrigger<List<T>>() {

    init {
        if (amount != null) {
            require(amount >= 1) { "The amount of items requested must be >= 1; it was $amount." }
        }
    }

    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        blockingQ.requestQ.isEmpty && blockingQ.countByChannel(predicate) >= (amount ?: 1)

    /**
     *  With requests already waiting and the items it needs in the channel, whether it is filled at once is known
     *  only once its request is queued: the queue's request selector may fill it ahead of them. It then registers in
     *  argument order, and claims the wait as it registers when the selector chooses it.
     */
    override val decidesWhenQueued: Boolean
        get() = !blockingQ.requestQ.isEmpty && blockingQ.countByChannel(predicate) >= (amount ?: 1)

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): List<T> {
        // As the untimed receive does when it can be filled at once: the request enters the queue and
        // is filled in the same instant, which records a wait of zero.
        return fill(blockingQ, newRequest(process.entity, null, process.entity.priority))
    }

    override fun register(group: WaitGroup): Registration {
        val registration = ItemsRegistration(this, group)
        // ranked by the priority when the wait began, whatever a trigger registered before it did to the entity's
        val request = newRequest(group.entity, registration, group.entityPriority)
        registration.request = request
        // The selector chooses who is served now. If it chooses this request, the wait is claimed while it registers
        // and goes on without suspending, as the untimed receive does; if it chooses an earlier request, even one
        // of this same wait, that one is served.
        blockingQ.requestJoined(request)
        return registration
    }

    private fun newRequest(
        entity: ProcessModel.Entity,
        registration: Registration?,
        entityPriority: Int
    ): BlockingQueue<T>.ChannelRequest {
        val priority = blockingPriority ?: entityPriority
        val request = if (amount != null) blockingQ.enqueueAmountRequest(entity, predicate, amount, priority, registration)
        else blockingQ.enqueueChannelRequest(entity, predicate, priority, registration)
        return request
    }

    override val aloneState: AloneState
        get() = if (amount != null) AloneState.BLOCKED_RECEIVING_ITEMS else AloneState.BLOCKED_RECEIVING_ANY_ITEMS

    override fun toString(): String =
        if (amount != null) "ItemsTrigger(${blockingQ.name}, amount = $amount)" else "AnyItemsTrigger(${blockingQ.name})"
}

/**
 *  An items trigger's request in one wait. The winner takes its items after its resume, exactly as
 *  the untimed receive takes them; a loser leaves the request queue at the claim, its wait recorded
 *  as the queue records any request's wait, and the queue looks at the requests behind it once the
 *  claim is over; termination removes the request with nothing recorded.
 */
internal class ItemsRegistration<T : ModelElement.QObject>(
    private val itemsTrigger: ItemsTrigger<T>,
    group: WaitGroup
) : Registration(itemsTrigger, group) {

    lateinit var request: BlockingQueue<T>.ChannelRequest

    override fun onReleaseAsLoser() {
        itemsTrigger.blockingQ.removeRequestDeferringNotification(request)
    }

    override fun onReleaseOnTermination() {
        // the requests behind it are looked at once every registration of the ended wait is released
        itemsTrigger.blockingQ.removeRequestOnTermination(request)
    }

    override val deferredNotificationSource: Any
        get() = itemsTrigger.blockingQ

    override fun notifyDeferred() {
        itemsTrigger.blockingQ.notifyAfterDeferredRemoval()
    }

    /** Held while a seize trigger's win was undecided, the request may have been passed over: the queue looks again. */
    override fun lookAgain(process: ProcessModel.Entity.ProcessCoroutine) {
        itemsTrigger.blockingQ.notifyAfterDeferredRemoval()
    }

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        return AfterResume.Value(fill(itemsTrigger.blockingQ, request))
    }
}

/** Takes a request's items, as the untimed receive of that kind takes them. */
internal fun <T : ModelElement.QObject> fill(blockingQ: BlockingQueue<T>, request: BlockingQueue<T>.ChannelRequest): List<T> =
    if (request is BlockingQueue<T>.AmountRequest) blockingQ.fill(request) else blockingQ.fill(request)
