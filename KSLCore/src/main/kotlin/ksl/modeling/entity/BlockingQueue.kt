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

    val full: Boolean
        get() = myChannelQ.size == capacity
    val notFull: Boolean
        get() = !full

    private val mySenderQ: Queue<ProcessModel.Entity> = Queue(this, "${name}:SenderQ")
    val senderQ: QueueCIfc<ProcessModel.Entity>
        get() = mySenderQ
    private val myReceiverQ: Queue<ProcessModel.Entity> = Queue(this, "${name}:ReceiverQ")
    val receiverQ: QueueCIfc<ProcessModel.Entity>
        get() = myReceiverQ
    private val myChannelQ: Queue<T> = Queue(this, "${name}:ChannelQ")
    val channelQ: QueueCIfc<T>
        get() = myChannelQ

    /**
     *  The user of the BlockingQueue can supply a function that will select the next entity
     *  that is waiting to receive items from the queue's channel after new items are
     *  added to the channel.
     */
    var receiverSelector: EntitySelectorIfc = DefaultEntitySelector()

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

    internal fun enqueueSender(sender: ProcessModel.Entity, priority: Int = sender.priority) {
        mySenderQ.enqueue(sender, priority)
    }

    internal fun enqueueReceiver(receiver: ProcessModel.Entity, priority: Int = receiver.priority) {
        myReceiverQ.enqueue(receiver, priority)
    }

    private fun selectEntity(queue: Queue<ProcessModel.Entity>): ProcessModel.Entity? {
        return queue.peekNext()
    }

    internal fun sendToChannel(qObject: T) {
        check(notFull) { "$name : Attempted to send ${qObject.name} to a full channel queue." }
        myChannelQ.enqueue(qObject)
        // actions related to putting a new qObject in the channel
        // check if receivers are waiting, select next receiver
        if (myReceiverQ.isNotEmpty) {
            // select the next receiver to review channel queue
            val entity = receiverSelector.selectEntity(myReceiverQ)!!
            // ask selected entity to review the channel queue and decide what to do
            entity.reviewBlockingQueue(this, Queue.Status.ENQUEUED)
        }
    }

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
     * if all the items are not present in the channel.
     *
     * @param c the collection of items to remove from the channel
     * @param waitStats indicates whether waiting time statistics should be collected
     */
    fun removeAllFromChannel(c: Collection<T>, waitStats: Boolean = true) {
        check(myChannelQ.isNotEmpty) { "$name : Attempted to receive from an empty channel queue" }
        check(!containsAll(c)) { "$name : The channel does not contain all of the items in the collection" }
        myChannelQ.removeAll(c, waitStats)
        // actions related to removal of elements, check for waiting senders
        // select next waiting sender and resume it
        if (mySenderQ.isNotEmpty) {
            // select the entity waiting to send elements into the channel
            val entity = receiverSelector.selectEntity(mySenderQ)!!
            // ask selected entity to review the channel queue and decide what to do
            entity.reviewBlockingQueue(this, Queue.Status.DEQUEUED)
        }
    }
}