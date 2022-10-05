package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement
import java.util.function.Predicate

fun interface EntitySelectorIfc {

    fun selectEntity(queue: Queue<ProcessModel.Entity>) : ProcessModel.Entity?

}
class BlockingQueue(
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
    private val myChannelQ: Queue<QObject> = Queue(this, "${name}:ChannelQ")
    val channelQ: QueueCIfc<QObject>
        get() = myChannelQ

 //   var receiverSelector: EntitySelectorIfc = ::selectEntity
//var senderSelector: EntitySelectorIfc

    internal fun enqueueSender(sender: ProcessModel.Entity, priority: Int = sender.priority) {
        mySenderQ.enqueue(sender, priority)
    }

    internal fun enqueueReceiver(receiver: ProcessModel.Entity, priority: Int = receiver.priority) {
        myReceiverQ.enqueue(receiver, priority)
    }

    private fun selectEntity(queue: Queue<ProcessModel.Entity>): ProcessModel.Entity? {
        return queue.peekNext()
    }

    internal fun sendToChannel(qObject: QObject) {
        check(notFull) { "$name : Attempted to send ${qObject.name} to a full channel queue." }
        myChannelQ.enqueue(qObject)
        //TODO actions related to putting qObject in channel
        // check if receivers are waiting, select next receiver, resume it
        if(myReceiverQ.isNotEmpty){
            val entity = selectEntity(myReceiverQ)!!
            //TODO what if channel does not have what the entity is waiting on?
            // check criteria and only resume if channel has what is needed
            // I think we should let the entity decide if it should resume
            entity.resumeProcess()
        }
        TODO("Not implemented yet")
    }

    internal fun sendToChannel(c: Collection<QObject>) {
        require(c.size < availableSlots) { "$name : The channel has $availableSlots and cannot hold the collection of size ${c.size}" }
        for(qo in c){
            sendToChannel(qo)
        }
    }

    /**
     * @param c the collection to check
     * @return true if the channel contains all items in the collection
     */
    fun containsAll(c: Collection<QObject>): Boolean {
        return myChannelQ.contains(c)
    }

    /** Finds the items in the channel according to the condition.  Does not remove the items from the channel.
     *
     * @param condition the criteria for selecting the items
     * @return a list of the items found.  May be empty if nothing is found that matches the condition.
     */
    fun findInChannel(condition: Predicate<QObject>): MutableList<QObject> {
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
    fun receiveFromChannel(condition: Predicate<QObject>, waitStats: Boolean = true): MutableList<QObject> {
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
    fun removeAllFromChannel(c: Collection<QObject>, waitStats: Boolean = true) {
        check(myChannelQ.isNotEmpty) { "$name : Attempted to receive from an empty channel queue" }
        check(!containsAll(c)) { "$name : The channel does not contain all of the items in the collection" }
        myChannelQ.removeAll(c, waitStats)
        //TODO actions related to removal of elements, check for waiting senders
        // select next waiting sender and resume it
    }
}