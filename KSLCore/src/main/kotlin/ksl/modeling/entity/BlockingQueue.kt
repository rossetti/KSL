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

package ksl.modeling.entity

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import java.util.function.Predicate

fun interface EntitySelectorIfc {

    fun selectEntity(queue: Queue<ProcessModel.Entity>): ProcessModel.Entity?

}

/**
 * A blocking queue facilitates the exchange of information between two or more
 * entities that are experiencing processes. Note that an entity can experience
 * one process at a time.
 *
 *  If the capacity is finite, then placing an item in the queue may cause
 *  the sender to block its current process.  Receivers of items from the queue may block
 *  if the number of items meeting their requirements are not available at the time of the
 *  request. By default, all queues are FIFO, but their disciplines can be set by the user.
 *  Instances are intended to be used to communicate between KSLProcesses via the shared
 *  mutable state of the queue.  Statistics on blocking and usage of the communication
 *  channel are automatically reported.
 *
 *  Senders send in the order of the sender queue. A process that sends to a full queue blocks until
 *  something removes an item, so with a finite capacity a process must not rely on itself, or on a
 *  process waiting on it, to empty a queue it sends to. A replication that ends with
 *  a sender blocked for long gets a warning.
 *
 * @param parent the parent of the queue
 * @param capacity the capacity of the queue, by default Int.MAX_VALUE (infinite)
 * @param name the name of the queue
 */
class BlockingQueue<T : ModelElement.QObject> @JvmOverloads constructor(
    parent: ModelElement,
    capacity: Int = Int.MAX_VALUE,
    name: String? = null
) : ModelElement(parent, name) {

    companion object {
        private val logger: KLogger = KotlinLogging.logger {}

        /**
         * The part of the replication so far that a blocked sender's wait must last to be warned about.
         * A judgement, not a derived value.
         */
        private const val LONG_WAIT_FRACTION = 0.10
    }

    init {
        require(capacity >= 1) { "The size of the blocking queue must be >= 1" }
    }

    /**
     *  The default size for the queue for each replication. This
     *  property cannot be changed during a replication.
     */
    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var capacity: Int  = capacity
        set(value) {
            require(value >= 1) { "The size of the blocking queue must be >= 1" }
            require(model.isNotRunning) {"The model cannot be running when changing the capacity of the blocking queue"}
            field = value
        }

    /**
     * The request whose entity has been told to resume and has not yet run. Until it has, the channel
     * can't be promised to anyone else: the request is still in the queue, so selecting again would
     * find it again, and it hasn't yet taken its items, so another request could be told about
     * the same ones. Requests are therefore woken one at a time, all at the same simulated time.
     */
    private var myRequestBeingResumed: ChannelRequest? = null

    /**
     * The same, for an entity blocked sending. Until it has run, a free slot is promised to it, and
     * only a send fills a slot, so holding back other senders is enough to keep the promise.
     */
    private var mySenderBeingResumed: ProcessModel.Entity? = null

    /** When an item last left the channel in this replication, or NaN if none has. */
    private var myTimeLastRemoval: Double = Double.NaN

    override fun beforeReplication() {
        myRequestBeingResumed = null
        mySenderBeingResumed = null
        myTimeLastRemoval = Double.NaN
    }

    /**
     * Reports any entity still blocked sending when a replication ends.
     *
     * A blocked sender schedules nothing, and it can't be known when it blocks whether anything will
     * ever remove an item, since any entity might. So a process that puts into a queue only it empties,
     * or two processes each sending to a queue the other empties, simply stop, and the
     * clock runs on to the end of the replication. A wait of at least a tenth of the replication so
     * far gets a warning. A shorter one is what a busy queue looks like at any moment.
     */
    override fun replicationEnded() {
        val (long, short) = mySenderQ.toList().partition {
            time - it.timeEnteredQueue >= LONG_WAIT_FRACTION * time
        }
        if (long.isNotEmpty()) {
            logger.warn {
                "BlockingQueue ($name): ${long.size} entit(ies) were still blocked sending when replication " +
                        "${model.currentReplicationNumber} ended. Nothing may be going to remove items " +
                        "from this queue." + describeBlockedSenders(long)
            }
        }
        if (short.isNotEmpty()) {
            logger.info {
                "BlockingQueue ($name): ${short.size} entit(ies) were still waiting to send when replication " +
                        "${model.currentReplicationNumber} ended." + describeBlockedSenders(short)
            }
        }
    }

    private fun describeBlockedSenders(senders: List<ProcessModel.Entity>): String = buildString {
        for (sender in senders) {
            append(System.lineSeparator())
            append("  (${sender.name}) has waited ${time - sender.timeEnteredQueue}, since ${sender.timeEnteredQueue}.")
        }
        append(System.lineSeparator())
        append("  The channel holds ${myChannelQ.size} of $capacity")
        if (myTimeLastRemoval.isNaN()) {
            append(", and no item has left it.")
        } else {
            append(", and the last item left it at $myTimeLastRemoval.")
        }
    }

    var requestQResumptionPriority: Int = ProcessModel.RESUME_PRIORITY
    var senderQResumptionPriority: Int = requestQResumptionPriority - 1 // sender has more priority

    /**
     *  True if the channel does not contain any items
     */
    val isChannelEmpty: Boolean
        get() = myChannelQ.isEmpty

    /**
     *  True if the channel contains items
     */
    val isChannelNotEmpty: Boolean
        get() = myChannelQ.isNotEmpty

    /**
     *  True if a send would go ahead now rather than wait. A send waits if the channel is full, or
     *  if its free slot is promised to an entity already in line to send. The channel can therefore
     *  have room while this is false, but only for the rest of the current simulated instant.
     */
    val canSendWithoutWaiting: Boolean
        get() = isNotFull && mySenderBeingResumed == null

    /**
     *  The number of available slots in the channel based on the capacity
     */
    val availableSlots: Int
        get() = capacity - myChannelQ.size

    /**
     *  True if the channel is at its capacity
     */
    val isFull: Boolean
        get() = myChannelQ.size == capacity

    /**
     *  True if the channel is not at its capacity
     */
    val isNotFull: Boolean
        get() = !isFull

    private val mySenderQ: Queue<ProcessModel.Entity> = Queue(this, "${this.name}:SenderQ")

    /**
     *  The queue that holds entities wanting to place items in to the channel that are
     *  waiting (blocked) because there is no available capacity
     */
    val senderQ: QueueCIfc<ProcessModel.Entity>
        get() = mySenderQ

    private val myRequestQ: Queue<ChannelRequest> = Queue(this, "${this.name}:RequestQ")

    /**
     *  The queue that holds requests by entities to remove items from the channel because
     *  the channel does not have the requested amount of items that meet the desired
     *  selection criteria.
     */
    val requestQ: QueueCIfc<ChannelRequest>
        get() = myRequestQ

    private val myChannelQ: Queue<T> = Queue(this, "${this.name}:ChannelQ")

    /**
     *  The channel that holds items that are being sent or received.
     */
    val channelQ: QueueCIfc<T>
        get() = myChannelQ

    init {
        if (capacity == Int.MAX_VALUE) {
            //no need for statistics for the sender, so turn it off
            senderQ.waitTimeStatOption = false
            senderQ.timeInQ.defaultReportingOption = false
            senderQ.numInQ.defaultReportingOption = false
        }
    }

    /** Removes the request from the queue and tells the associated entity to terminate its process.  The process
     *  that was suspended because the entity's request was placed in the queue is immediately terminated.
     *
     * @param request the request to remove from the queue
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     */
    fun removeAndTerminateChannelRequest(
        request: ChannelRequest,
        waitStats: Boolean = false
    ) {
        myRequestQ.remove(request, waitStats)
        request.receiver.terminateProcess()
        afterRequestLeft(request)
    }

    /**
     *  Removes and terminates all the requests waiting in the queue
     *
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     */
    fun removeAllAndTerminateChannelRequest(waitStats: Boolean = false) {
        while (myRequestQ.isNotEmpty) {
            val request = myRequestQ.peekNext()
            removeAndTerminateChannelRequest(request!!, waitStats)
        }
    }

    /** Removes the entity from the send queue and tells the entity to terminate its process.  The process
     *  that was suspended because the entity was placed in the queue is immediately terminated.
     *
     * @param entity the entity to remove from the queue
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue. The default is false.
     */
    fun removeAndTerminateWaitingSenders(
        entity: ProcessModel.Entity,
        waitStats: Boolean = false
    ) {
        mySenderQ.remove(entity, waitStats)
        entity.terminateProcess()
        afterSenderLeft(entity)
    }

    /**
     *  Removes and terminates all the entities waiting in the send queue
     *
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     */
    fun removeAllAndTerminateWaitingSenders(waitStats: Boolean = false) {
        while (mySenderQ.isNotEmpty) {
            val entity = mySenderQ.peekNext()
            removeAndTerminateWaitingSenders(entity!!, waitStats)
        }
    }

    /**
     *  @param option use false to turn off all statistics
     */
    fun waitTimeStatisticsOption(option: Boolean) {
        requestQ.waitTimeStatOption = option
        channelQ.waitTimeStatOption = option
        senderQ.waitTimeStatOption = option
        requestQ.defaultReportingOption = option
        senderQ.defaultReportingOption = option
        channelQ.defaultReportingOption = option
    }

    /**
     * Represents a request by an entity to receive a given amount of items from the channel
     * that meet the criteria (predicate).
     * @param receiver the entity that wants the items
     * @param predicate the criteria for selecting the items from the channel
     * waiting for their request
     */
    open inner class ChannelRequest(
        val receiver: ProcessModel.Entity,
        val predicate: (T) -> Boolean,
        priority: Int
    ) : QObject() {

        /** The request's place in a wait for the first of several things; null for an untimed receive. */
        internal var registration: Registration? = null

        init {
            this.priority = priority
        }

        open val canBeFilled: Boolean
            get() {
                return myChannelQ.filter(predicate).isNotEmpty()
            }

        /**
         * True if the request can not be filled at the time the property is accessed
         */
        val canNotBeFilled: Boolean
            get() = !canBeFilled
    }

    /**
     * Represents a request by an entity to receive a given amount of items from the channel
     * that meet the criteria (predicate).
     * @param receiver the entity that wants the items
     * @param predicate the criteria for selecting the items from the channel
     * @param amountRequested the number of items (meeting the criteria) that are needed
     */
    inner class AmountRequest(
        receiver: ProcessModel.Entity,
        predicate: (T) -> Boolean,
        val amountRequested: Int,
        priority: Int
    ) : ChannelRequest(receiver, predicate, priority) {
        init {
            require(amountRequested >= 1) { "The amount request $amountRequested must be >= 1" }
        }

        /**
         * True if the request can be filled at the time the property is accessed
         */
        override val canBeFilled: Boolean
            get() {
                val list = myChannelQ.filter(predicate)
                return amountRequested <= list.size
            }
    }

    /**
     *  A trigger for an amount of items from this queue's channel, for use in a wait for the first
     *  of several things (`waitForFirst`). The trigger fires when its request is selected while the
     *  wait is open, or at once when no request is waiting ahead of it and the channel holds the
     *  items, exactly as `waitForItems` is filled. Its value is the items, taken from the channel.
     *  While the wait is open, its request waits in this queue's request queue as a `waitForItems`
     *  request does, so it is selected, counted and recorded in the same way; if the wait ends
     *  otherwise, the request leaves the queue and its wait is recorded.
     *
     *  A trigger holds no state of any wait, so it can be kept and used in any number of waits.
     *
     *  @param amount the number of items wanted; at least 1
     *  @param predicate selects the items wanted
     *  @param blockingPriority the request's priority in the request queue; null (the default)
     *  means the entity's own priority when the wait starts
     */
    fun itemsTrigger(
        amount: Int = 1,
        predicate: (T) -> Boolean = alwaysTrue,
        blockingPriority: Int? = null
    ): WaitTrigger<List<T>> = ItemsTrigger(this, amount, predicate, blockingPriority)

    /**
     *  A trigger for every item in this queue's channel that matches [predicate], at least one, for
     *  use in a wait for the first of several things (`waitForFirst`). See `itemsTrigger`; this is
     *  to it what `waitForAnyItems` is to `waitForItems`.
     *
     *  @param predicate selects the items wanted
     *  @param blockingPriority the request's priority in the request queue; null (the default)
     *  means the entity's own priority when the wait starts
     */
    fun anyItemsTrigger(
        predicate: (T) -> Boolean,
        blockingPriority: Int? = null
    ): WaitTrigger<List<T>> = ItemsTrigger(this, null, predicate, blockingPriority)

    /**
     *  The user of the BlockingQueue can supply a function that will select the next entity
     *  that is waiting to receive items from the queue's channel after new items are
     *  added to the channel, and after a request has been filled.
     *
     *  By default, only the request at the head of the request queue can be selected, so a
     *  later request waits behind it even when its own items are in the channel. Use
     *  [FirstFillableRequest] to let a later request go ahead.
     */
    var receiverRequestSelector: RequestSelectorIfc<T> = RequestSelector()

    /**
     *  The user of the BlockingQueue can supply a function that will select, after items
     *  are removed from the channel, the next entity
     *  that is blocked waiting to send items to the queue's channel because the channel
     *  was full.
     */
    var senderSelector: EntitySelectorIfc = DefaultEntitySelector()

    private inner class DefaultEntitySelector : EntitySelectorIfc {
        override fun selectEntity(queue: Queue<ProcessModel.Entity>): ProcessModel.Entity? {
            return queue.peekNext()
        }

    }

    //TODO Why are these inside the BlockingQueue class? Why not defined external to the class?
    // Because the ChannelRequest has inner class access to the channel queue
    /**
     * The default is to select the next based on the queue discipline. This class
     * can be used to define a default method for selecting the next receiver's request
     * to receive items from the channel. The default is to use the queue discipline. The
     * request at the head of the queue is selected if it can be filled. If it can't, no
     * request is selected, whatever could be filled further back.
     */
    inner class RequestSelector<T : ModelElement.QObject> : RequestSelectorIfc<T> {
        override fun selectRequest(queue: Queue<BlockingQueue<T>.ChannelRequest>): BlockingQueue<T>.ChannelRequest? {
            val nextRequest = queue.peekNext() ?: return null
            if (nextRequest.canNotBeFilled){
                return null
            }
            return nextRequest
        }
    }

    /**
     * Allows the next request that can be filled to be selected regardless of its
     * location within the request queue.
     */
    inner class FirstFillableRequest() : RequestSelectorIfc<T> {
        override fun selectRequest(queue: Queue<ChannelRequest>): ChannelRequest? {
            for (request in queue) {
                if (request.canBeFilled) {
                    return request
                }
            }
            return null
        }
    }

    /**
     *  Interface for defining request selection
     */
    interface RequestSelectorIfc<T : ModelElement.QObject> {
        fun selectRequest(queue: Queue<BlockingQueue<T>.ChannelRequest>): BlockingQueue<T>.ChannelRequest?
    }

    /**
     *  Called from ProcessModel via the Entity to put the entity in the queue if the
     *  blocking queue is full when sending
     */
    internal fun enqueueSender(sender: ProcessModel.Entity, priority: Int) {
        mySenderQ.enqueue(sender, priority)
    }

    /**
     * Called from ProcessModel via the entity to remove the entity from the blocking queue
     * when there is space to send
     */
    internal fun dequeSender(sender: ProcessModel.Entity) {
        mySenderQ.remove(sender)
        if (mySenderBeingResumed === sender) {
            mySenderBeingResumed = null
        }
    }

    /**
     * Called from ProcessModel when a sender's process ends while it is blocked. No waiting time
     * statistics are collected, and this is a no-op when the sender has already been removed.
     */
    internal fun removeSender(sender: ProcessModel.Entity) {
        mySenderQ.remove(sender, false)
        afterSenderLeft(sender)
    }

    private fun afterSenderLeft(sender: ProcessModel.Entity) {
        if (mySenderBeingResumed === sender) {
            mySenderBeingResumed = null
            if (isNotFull) {
                notifyWaitingSender()
            }
        }
    }

    /**
     *  Called from ProcessModel.waitForAnyItems().
     *  Function allows internal enqueueing of created requests for items
     *  into the request queue (myRequestQ)
     */
    internal fun enqueueChannelRequest(
        receiver: ProcessModel.Entity,
        predicate: (T) -> Boolean,
        priority: Int,
        registration: Registration? = null
    ) : ChannelRequest {
        val request = ChannelRequest(receiver, predicate, priority)
        request.registration = registration
        myRequestQ.enqueue(request)
        return request
    }

    /**
     *  Called from ProcessModel.waitForItems().
     *  Function allows internal enqueueing of created requests for items
     *  into the request queue (myRequestQ)
     */
    internal fun enqueueAmountRequest(
        receiver: ProcessModel.Entity,
        predicate: (T) -> Boolean,
        amount: Int,
        priority: Int,
        registration: Registration? = null
    ) : AmountRequest {
        val request = AmountRequest(receiver, predicate, amount, priority)
        request.registration = registration
        myRequestQ.enqueue(request)
        return request
    }

    /**
     *  Uses the specified receiverRequestSelector to select the next request.
     *  Called from Entity's ProcessCoroutine when waiting for requesting items also
     *  from BlockingQueue.sendToChannel() when items are sent to the channel.
     *
     *  @return null implies that there is no next request that can be filled by items in the channel,
     *  or that a request already selected has yet to take its items
     */
    internal fun selectNextRequest() : ChannelRequest? {
        if (myRequestBeingResumed != null) {
            return null
        }
        val nextRequest = receiverRequestSelector.selectRequest(myRequestQ) ?: return null
        return if (nextRequest.canBeFilled){
            nextRequest
        } else {
            null
        }
    }

    /**
     * Called from ProcessModel via the entity to get the items associated with
     * a request to the blocking queue for items.  The request must be able to be
     * filled. The requested items are extracted from the channel and returned
     * to the requesting entity.
     */
    internal fun fill(request: AmountRequest): List<T> {
        require(request.canBeFilled) { "The request could not be filled" }
        // filter processes the queue in its current order and maintains that order in the returned list
        val list = myChannelQ.filter(request.predicate) // the items that meet the predicate
        val requestedItems = list.take(request.amountRequested)
        takeFromChannel(requestedItems, channelQ.waitTimeStatOption)
        myRequestQ.remove(request)
        afterRequestLeft(request)
        return requestedItems
    }

    /**
     * Called from ProcessModel via the entity to get the items associated with
     * a request to the blocking queue for items.  The request must be able to be
     * filled. The requested items are extracted from the channel and returned
     * to the requesting entity.
     */
    internal fun fill(request: ChannelRequest): List<T> {
        require(request.canBeFilled) { "The request could not be filled" }
        // filter processes the queue in its current order and maintains that order in the returned list
        val list = myChannelQ.filter(request.predicate) // the items that meet the predicate
        takeFromChannel(list, channelQ.waitTimeStatOption)
        myRequestQ.remove(request)
        afterRequestLeft(request)
        return list
    }

    /**
     * Called from ProcessModel to deregister a request whose process ended at the suspension point
     * that created it, rather than by being filled. No waiting time statistics are collected, which
     * matches the convention of removeAndTerminateChannelRequest. This is a no-op when the request
     * has already been removed by fill(), so it is safe on the normal path.
     *
     * Without it a request would outlive its process, and notifyWaitingRequests would select it
     * ahead of any live receiver, offering the item to a process that can never take it.
     *
     * @param request the request to remove from the queue of requests waiting for items
     */
    internal fun removeRequest(request: ChannelRequest) {
        if (myRequestQ.remove(request, false)) {
            afterRequestLeft(request)
        }
    }

    /**
     *  Removes the request of a wait that another trigger, or the timer, has ended. Its wait is
     *  recorded as the queue records any request's wait. The requests behind it are looked at only
     *  when the claim that ended the wait has finished, through notifyAfterDeferredRemoval.
     */
    internal fun removeRequestDeferringNotification(request: ChannelRequest) {
        check(myRequestBeingResumed !== request) {
            "$name: the request ${request.id} was released as a loser while the queue was resuming it."
        }
        myRequestQ.remove(request)
    }

    /** Looks at the waiting requests after a deferred removal. */
    internal fun notifyAfterDeferredRemoval() {
        notifyWaitingRequests()
    }

    /**
     *  Claims the wait of a request selected while it registers. It is marked as being resumed, as a
     *  request the queue selects is, so the items stay promised to it until it takes them.
     */
    internal fun claimSelected(request: ChannelRequest, registration: Registration) {
        myRequestBeingResumed = request
        val claimed = registration.group.claim(
            registration, null, ResumeSource.BLOCKING_QUEUE_RECEIVER, requestQResumptionPriority
        )
        check(claimed) { "$name: the request ${request.id} was selected for a wait that had already ended." }
    }

    /** The channel may hold items for a request that was waiting behind this one. */
    private fun afterRequestLeft(request: ChannelRequest) {
        if (myRequestBeingResumed === request) {
            myRequestBeingResumed = null
        }
        notifyWaitingRequests()
    }

    /**
     *  Called from ProcessModel via the entity to place the item into the
     *  blocking queue's channel. There must be space for the item in the channel.
     *  Adding an item to the channel triggers the processing of entities that
     *  are waiting to receive items from the channel.
     *  This function may resume an entity suspended within a waitForItems() function
     */
    internal fun sendToChannel(qObject: T) {
        check(isNotFull) { "$name : Attempted to send ${qObject.name} to a full channel queue." }
        myChannelQ.enqueue(qObject)
        // actions related to putting a new qObject in the channel
        notifyWaitingRequests()
        // more than one item may have left the channel, so there may be room for the next sender
        if (isNotFull) {
            notifyWaitingSender()
        }
    }

    private fun notifyWaitingRequests() {
        // a request already told to resume will look again when it has taken its items
        if (myRequestBeingResumed != null || myRequestQ.isEmpty) {
            return
        }
        // with one request waiting there is no decision to make
        val request = if (myRequestQ.size == 1) myRequestQ[0] else selectNextRequest()
        if (request != null && request.canBeFilled) {
            // the request is not removed here: it is the entity that causes the request to be removed
            val registration = request.registration
            if (registration != null) {
                // A request of a wait for the first of several things. Its wait is still open: a
                // request whose wait has ended has left the queue, apart from one being resumed,
                // and then nothing is selected. The claim schedules the resume.
                claimSelected(request, registration)
                return
            }
            myRequestBeingResumed = request
            request.receiver.scheduleResumeProcess(
                0.0,
                requestQResumptionPriority,
                ResumeSource.BLOCKING_QUEUE_RECEIVER,
                "blocking_queue=$name, request_id=${request.id}"
            )
        }
    }

    /**
     * Allows many items to be sent (placed into) the channel if there is space.  Calls
     * sendToChannel(qObject) to get the work done.
     */
    internal fun sendToChannel(c: Collection<T>) {
        require(c.size < availableSlots) { "$name : The channel has $availableSlots and cannot hold the collection of size ${c.size}" }
        for (qo in c) {
            sendToChannel(qo)
        }
    }

    /**
     * @param c the collection to check
     * @return true if the channel contains all items in the collection
     */
    fun containsAll(c: Collection<T>): Boolean {
        return myChannelQ.contains(c)
    }

    /** Finds the items in the channel according to the condition.  Does not remove the items from the channel.
     *
     * @param predicate the criteria for selecting the items
     * @return a list of the items found.  May be empty if nothing is found that matches the condition.
     */
    fun filterChannel(predicate: (T) -> Boolean): List<T> {
        return myChannelQ.filter(predicate)
    }

    /** Finds the items in the channel according to the condition.  Does not remove the items from the channel.
     *
     * @param predicate the criteria for selecting the items
     * @return a list of the items found.  May be empty if nothing is found that matches the condition.
     */
    fun filterChannel(predicate: Predicate<T>): List<T> {
        return filterChannel(predicate::test)
    }

    /**
     * @param predicate the condition to count by
     * @return the number of items in the channel that match the condition
     */
    fun countByChannel(predicate: (T) -> Boolean): Int {
        return myChannelQ.countBy(predicate)
    }

    /**
     * @param predicate the condition to count by
     * @return the number of items in the channel that match the condition
     */
    fun countByChannel(predicate: Predicate<T>): Int {
        return countByChannel(predicate::test)
    }

    /**
     * The first waiting request that wants the item, which is the reason the item can't be removed
     * directly from the channel. A request wants an item that passes its predicate, whether or not
     * the request has been told to resume.
     *
     * @param item the item to check
     * @return the request, or null if no waiting request wants the item
     */
    fun requestWanting(item: T): ChannelRequest? {
        for (request in myRequestQ) {
            if (request.predicate(item)) {
                return request
            }
        }
        return null
    }

    /**
     * @param item the item to check
     * @return true if no waiting request wants the item, so that it can be removed directly from the channel
     */
    fun canRemoveFromChannel(item: T): Boolean = requestWanting(item) == null

    /**
     * Finds the items that can be removed directly from the channel. Passing these to
     * removeAllFromChannel() can't fail because of a waiting request. Does not remove the items.
     *
     * @param condition the criteria for selecting the items. By default, every item.
     * @return the items in the channel that meet the condition and that no waiting request wants
     */
    fun availableForRemoval(condition: (T) -> Boolean = { true }): List<T> {
        return myChannelQ.filter(condition).filter { canRemoveFromChannel(it) }
    }

    /**
     * Finds the items that can be removed directly from the channel. Does not remove the items.
     *
     * @param condition the criteria for selecting the items
     * @return the items in the channel that meet the condition and that no waiting request wants
     */
    fun availableForRemoval(condition: Predicate<T>): List<T> {
        return availableForRemoval(condition::test)
    }

    /** Direct removal skips the line, so it can't take an item that a waiting request wants. */
    private fun checkNoRequestWants(item: T) {
        val request = requestWanting(item) ?: return
        throw IllegalStateException(
            "$name : cannot remove item ${item.name} from the channel at time $time. It is wanted by a " +
                    "request from entity ${request.receiver.name}, waiting since ${request.timeEnteredQueue}. " +
                    "Use availableForRemoval() to take only items no request wants, or remove the request " +
                    "first with removeAndTerminateChannelRequest()."
        )
    }

    /**
     * Removes the next item directly from the channel, skipping the line of waiting requests.
     * The channel must not be empty, and no waiting request can want the next item; otherwise an
     * IllegalStateException will occur. Use canRemoveFromChannel() to check.
     *
     * @param waitStats indicates whether waiting time statistics should be collected
     * @return the next element in the channel
     */
    fun receiveNextFromChannel(waitStats: Boolean = true): QObject {
        check(myChannelQ.isNotEmpty) { "$name : Attempted to receive from an empty channel queue" }
        val qObject = myChannelQ.peekNext()!!
        removeAllFromChannel(listOf(qObject), waitStats)
        return qObject
    }

    /** Finds the items in the channel according to the condition and removes them directly, skipping
     * the line of waiting requests. The channel must not be empty, and no waiting request can want
     * any of the items found; otherwise an IllegalStateException will occur and nothing is removed.
     * Use availableForRemoval() to take only the items that no request wants.
     *
     * @param condition the criteria for selecting the items
     * @param waitStats indicates whether waiting time statistics should be collected
     * @return a list of the items found.  May be empty if nothing is found that matches the condition.
     */
    fun receiveFromChannel(condition: Predicate<T>, waitStats: Boolean = true): List<T> {
        check(myChannelQ.isNotEmpty) { "$name : Attempted to receive from an empty channel queue" }
        val list = filterChannel(condition)
        removeAllFromChannel(list, waitStats)
        return list
    }

    /** Attempts to remove all the supplied items directly from the channel, skipping the line of
     * waiting requests. Throws an IllegalStateException, and removes nothing, if all the items are
     * not present in the channel or if a waiting request wants any of them. An empty collection
     * removes nothing. Use availableForRemoval() to find items that no request wants. Removing items
     * from the channel triggers the entities waiting to send more items into the channel if the
     * channel was full.
     *
     * @param c the collection of items to remove from the channel
     * @param waitStats indicates whether waiting time statistics should be collected
     */
    fun removeAllFromChannel(c: Collection<T>, waitStats: Boolean = true) {
        if (c.isEmpty()) {
            return
        }
        if (myChannelQ.isEmpty) {
            throw IllegalStateException("$name : Attempted to receive from an empty channel queue")
        }
        if (!myChannelQ.contains(c)) {
            throw IllegalStateException("$name : The channel does not contain all the items in the collection")
        }
        c.forEach { checkNoRequestWants(it) }
        takeFromChannel(c, waitStats)
    }

    /** The removal itself, which filling a request also uses, since it takes items its own request wants. */
    private fun takeFromChannel(c: Collection<T>, waitStats: Boolean) {
        for (item in c){
            val removed = myChannelQ.remove(item, waitStats)
            // item was removed, there may be waiting senders for placing items in the channel
            if (removed) {
                myTimeLastRemoval = time
                notifyWaitingSender()
            }
        }
    }

    private fun notifyWaitingSender() {
        // a sender already told to resume will look again when it has sent
        if (mySenderBeingResumed == null && mySenderQ.isNotEmpty) {
            // select an entity waiting to send elements into the channel
            val entity = senderSelector.selectEntity(mySenderQ)
            // if there is an entity selected, tell it to resume
            if (entity != null) {
                check(mySenderQ.contains(entity)) {
                    "$name : The sender selector chose ${entity.name}, which is not waiting to send to this queue."
                }
                mySenderBeingResumed = entity
                entity.scheduleResumeProcess(
                    0.0,
                    senderQResumptionPriority,
                    ResumeSource.BLOCKING_QUEUE_SENDER,
                    "blocking_queue=$name"
                )
            }
        }
    }
}
