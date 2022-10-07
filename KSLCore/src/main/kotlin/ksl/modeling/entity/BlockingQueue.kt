package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement
import java.util.function.Predicate

fun interface EntitySelectorIfc {

    fun selectEntity(queue: Queue<ProcessModel.Entity>): ProcessModel.Entity?

}

class BlockingQueue<T : ModelElement.QObject>(
    parent: ModelElement,
    val capacity: Int = Int.MAX_VALUE,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(capacity >= 1) { "The size of the blocking queue must be >= 1" }
    }

    val isChannelEmpty: Boolean
        get() = myChannelQ.isEmpty
    val isChannelNotEmpty: Boolean
        get() = myChannelQ.isNotEmpty

    val availableSlots: Int
        get() = capacity - myChannelQ.size

    val isFull: Boolean
        get() = myChannelQ.size == capacity
    val isNotFull: Boolean
        get() = !isFull

    private val mySenderQ: Queue<ProcessModel.Entity> = Queue(this, "${name}:SenderQ")
    val senderQ: QueueCIfc<ProcessModel.Entity>
        get() = mySenderQ

    //TODO consider holding Requests(amount, entity, predicate)
    private val myReceiverRequestQ: Queue<Request> = Queue(this, "${name}:ReceiverQ")
    val receiverRequestQ: QueueCIfc<Request>
        get() = myReceiverRequestQ

    private val myChannelQ: Queue<T> = Queue(this, "${name}:ChannelQ")
    val channelQ: QueueCIfc<T>
        get() = myChannelQ

    inner class Request(
        val receiver: ProcessModel.Entity,
        val predicate: (T) -> Boolean,
        val amountRequested: Int = 1,
        val waitStats: Boolean = true
    ) : QObject() {

        /**
         * Checks if the request can be filled
         */
        val isFillable: Boolean
            get() {
                val list = myChannelQ.find(predicate)
                return amountRequested <= list.size
            }

        /**
         *  If the request can be filled, this returns a list of the items
         *  that satisfy the request.  This does not remove the items from the channel.
         *  Throws exception if the request cannot be filled.
         */
        fun requestedList(): List<T> {
            val list = myChannelQ.find(predicate)
            require(amountRequested <= list.size) { "Attempted to fill $amountRequested when only ${list.size} was available" }
            return list.take(amountRequested)
        }
    }

    /**
     *  The user of the BlockingQueue can supply a function that will select the next entity
     *  that is waiting to receive items from the queue's channel after new items are
     *  added to the channel.
     */
    var receiverRequestSelector: RequestSelector = RequestSelector()

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

    open inner class RequestSelector {
        open fun selectRequest(queue: Queue<Request>): Request? {
            return queue.peekNext()
        }
    }

    inner class FirstFillableRequest() : RequestSelector(){
        override fun selectRequest(queue: Queue<Request>): Request? {
            for(request in queue){
                if (request.isFillable){
                    return request
                }
            }
            return null
        }
    }

    interface RequestSelectorIfc<T : ModelElement.QObject> {
        fun selectRequest(queue: Queue<BlockingQueue<T>.Request>): BlockingQueue<T>.Request
    }

    /**
     *  Called from ProcessModel via the Entity to put the entity in the queue if the
     *  blocking queue is full when sending
     */
    internal fun enqueueSender(sender: ProcessModel.Entity, priority: Int = sender.priority) {
        mySenderQ.enqueue(sender, priority)
    }

    /**
     * Called from ProcessModel via the entity to remove the entity from the blocking queue
     * when there is space to send
     */
    internal fun dequeSender(sender: ProcessModel.Entity, waitStats: Boolean = true) {
        mySenderQ.remove(sender, waitStats)
    }

    /**
     * Called from ProcessModel via the entity to enqueue the receiver if it has to wait
     * when trying to receive from the blocking queue
     */
    internal fun enqueueReceiver(
        receiver: ProcessModel.Entity,
        predicate: (T) -> Boolean,
        amount: Int = 1,
        priority: Int = receiver.priority
    ) {
        val request = Request(receiver, predicate, amount)
        request.priority = priority
        myReceiverRequestQ.enqueue(request)
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
        // check if receivers are waiting, select next receiver
        if (myReceiverRequestQ.isNotEmpty) {
            // select the next receiver to review channel queue
            val request = receiverRequestSelector.selectRequest(myReceiverRequestQ) //TODO we should allow for null

            if (request != null) {
                if (request.isFillable) {
                    //TODO pass the request back to the entity so that it can pull them from the queue
                    // after being resumed?
                }
            }
            //TODO this will need to change with requests, also since we know new item is in channel we
            // should be more specific about the review, no need for use of Queue.Status
            // ask selected entity to review the channel queue and decide what to do
            TODO("sendToChannel is not implemented yet")
            // entity.reviewBlockingQueue(this, Queue.Status.ENQUEUED)
        }
    }

    /**
     * Allows many items to be sent (placed into) the channel if there is space.  Calles
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
     * @param condition the criteria for selecting the items
     * @return a list of the items found.  May be empty if nothing is found that matches the condition.
     */
    fun findInChannel(condition: Predicate<T>): MutableList<T> {
        return myChannelQ.find(condition)
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
     *
     * @param condition the criteria for selecting the items
     * @param waitStats indicates whether waiting time statistics should be collected
     * @return a list of the items found.  May be empty if nothing is found that matches the condition.
     */
    fun receiveFromChannel(condition: Predicate<T>, waitStats: Boolean = true): MutableList<T> {
        check(myChannelQ.isNotEmpty) { "$name : Attempted to receive from an empty channel queue" }
        val list = findInChannel(condition)
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
        check(myChannelQ.isNotEmpty) { "$name : Attempted to receive from an empty channel queue" }
        check(!containsAll(c)) { "$name : The channel does not contain all of the items in the collection" }
        myChannelQ.removeAll(c, waitStats)
        // actions related to removal of elements, check for waiting senders
        // select next waiting sender
        if (mySenderQ.isNotEmpty) {
            // select the entity waiting to send elements into the channel
            val entity = senderSelector.selectEntity(mySenderQ)
            // ask selected entity to review the channel queue and decide what to do
            if (entity != null) {
                entity.reviewBlockingQueue(this, Queue.Status.DEQUEUED)
            }
            //TODO right now this just cause the selected sender to resume its process
        }
    }
}