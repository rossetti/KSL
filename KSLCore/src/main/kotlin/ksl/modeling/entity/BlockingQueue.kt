package ksl.modeling.entity

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
 * @param parent the parent of the queue
 * @param capacity the capacity of the queue, by default Int.MAX_VALUE (infinite)
 * @param name the name of the queue
 */
class BlockingQueue<T : ModelElement.QObject>(
    parent: ModelElement,
    val capacity: Int = Int.MAX_VALUE,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(capacity >= 1) { "The size of the blocking queue must be >= 1" }
    }

    var requestQResumptionPriority: Int = KSLEvent.DEFAULT_PRIORITY - 8
    var senderQResumptionPriority: Int = KSLEvent.DEFAULT_PRIORITY - 9 // sender has more priority

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
    ) : QObject() {

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
    ) : ChannelRequest(receiver, predicate) {
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
     *  The user of the BlockingQueue can supply a function that will select the next entity
     *  that is waiting to receive items from the queue's channel after new items are
     *  added to the channel.
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

    /**
     * The default is to select the next based on the queue discipline
     */
    inner class RequestSelector<T : ModelElement.QObject> : RequestSelectorIfc<T> {
        override fun selectRequest(queue: Queue<BlockingQueue<T>.ChannelRequest>): BlockingQueue<T>.ChannelRequest? {
            return queue.peekNext()
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
    }

    /**
     * Called from ProcessModel via the entity to enqueue the receiver if it has to wait
     * when trying to receive from the blocking queue
     */
    internal fun requestItems(
        receiver: ProcessModel.Entity,
        predicate: (T) -> Boolean,
        amount: Int = 1,
        priority: Int
    ): AmountRequest {
        require(amount >= 1) { "The requested amount must be >= 1" }
        val request = AmountRequest(receiver, predicate, amount)
        request.priority = priority
        // always enqueue to capture wait statistics of those that do not wait
        myRequestQ.enqueue(request)
        return request
    }

    /**
     * Called from ProcessModel via the entity to enqueue the receiver if it has to wait
     * when trying to receive from the blocking queue
     */
    internal fun requestItems(
        receiver: ProcessModel.Entity,
        predicate: (T) -> Boolean,
        priority: Int
    ): ChannelRequest {
        val request = ChannelRequest(receiver, predicate)
        request.priority = priority
        myRequestQ.enqueue(request)
        return request
    }

    /**
     * Called from ProcessModel via the entity to get the items associated with
     * a request to the blocking queue for items.  The request must be able to be
     * filled. The requested items are extracted from the channel and returned
     * to the requesting entity.
     */
    internal fun fill(request: AmountRequest): List<T> {
        require(request.canBeFilled) { "The request could not be filled" }
        val list = myChannelQ.filter(request.predicate) // the items that meet the predicate
        val requestedItems = list.take(request.amountRequested)
        removeAllFromChannel(requestedItems, channelQ.waitTimeStatOption)
        myRequestQ.remove(request)
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
        val list = myChannelQ.filter(request.predicate) // the items that meet the predicate
        removeAllFromChannel(list, channelQ.waitTimeStatOption)
        myRequestQ.remove(request)
        return list
    }

    /**
     *  Called from ProcessModel via the entity to place the item into the
     *  blocking queue's channel. There must be space for the item in the channel.
     *  Adding an item to the channel triggers the processing of entities that
     *  are waiting to receive items from the channel.
     */
    internal fun sendToChannel(qObject: T) {
        check(isNotFull) { "$name : Attempted to send ${qObject.name} to a full channel queue." }
        myChannelQ.enqueue(qObject)
        // actions related to putting a new qObject in the channel
        // check if receivers are waiting, select next request waiting by a receiver
        if (myRequestQ.isNotEmpty) {
            // select the next request to be filled
            val request = receiverRequestSelector.selectRequest(myRequestQ)
            if (request != null) {
                if (request.canBeFilled) {
                    val entity = request.receiver
                    entity.resumeProcess(0.0, requestQResumptionPriority)
                }
            }
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

    /** The channel must not be empty; otherwise an IllegalStateException will occur.
     * @param waitStats indicates whether waiting time statistics should be collected
     * @return the next element in the channel
     */
    fun receiveNextFromChannel(waitStats: Boolean = true): QObject {
        check(myChannelQ.isNotEmpty) { "$name : Attempted to receive from an empty channel queue" }
        val qObject = myChannelQ.peekNext()!!
        removeAllFromChannel(listOf(qObject), waitStats)
        return qObject
    }

    /** Finds the items in the channel according to the condition and removes them.
     * The channel must not be empty.
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

    /** Attempts to remove all the supplied items from the channel. Throws an IllegalStateException
     * if all the items are not present in the channel. Removing items from the channel triggers
     * the entities waiting to send more items into the channel if the channel was full.
     *
     * @param c the collection of items to remove from the channel
     * @param waitStats indicates whether waiting time statistics should be collected
     */
    fun removeAllFromChannel(c: Collection<T>, waitStats: Boolean = true) {
        if (myChannelQ.isEmpty) {
            throw IllegalStateException("$name : Attempted to receive from an empty channel queue")
        }
        if (!myChannelQ.contains(c)) {
            throw IllegalStateException("$name : The channel does not contain all the items in the collection")
        }
        myChannelQ.removeAll(c, waitStats)
        // actions related to removal of elements, check for waiting senders
        // select next waiting sender
        if (mySenderQ.isNotEmpty) {
            // select the entity waiting to send elements into the channel
            val entity = senderSelector.selectEntity(mySenderQ)
            // ask selected entity to review the channel queue and decide what to do
            if (entity != null) {
                entity.resumeProcess(0.0, senderQResumptionPriority)
            }
        }
    }
}