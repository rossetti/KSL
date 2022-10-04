package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement
import kotlin.contracts.contract

class BlockingQueue(
    parent: ModelElement,
    val size: Int = Int.MAX_VALUE,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(size >= 1){"The size of the blocking queue must be >= 1"}
    }

    val full: Boolean
        get() = myChannelQ.size == size
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

    internal fun enqueueSender(sender: ProcessModel.Entity, priority: Int = sender.priority){
        mySenderQ.enqueue(sender, priority)
    }

    internal fun enqueueReceiver(receiver: ProcessModel.Entity, priority: Int = receiver.priority){
        myReceiverQ.enqueue(receiver, priority)
    }

    internal fun sendToChannel(qObject: QObject){
        check(notFull) {"Attempted to send ${qObject.name} to a full channel queue."}
       TODO("Not implemented yet")
    }

    internal fun receiveFromChannel(): QObject {
        check(myChannelQ.isNotEmpty){"Attempted to receive from an empty channel queue"}
        TODO("Not implemented yet")
        return QObject()
    }
}