package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc

/**
 *  A functional interface that promises to send. Within the
 *  context of qObjects a sender should cause a qObject
 *  to be (eventually) received by a receiver.
 */
fun interface QObjectSenderIfc {
    fun send(qObject: ModelElement.QObject)
}

abstract class QObjectSender() :  QObjectSenderIfc {

    /**
     *  Can be used to supply logic if the iterator does not have
     *  more receivers.
     */
    private var noNextReceiverHandler: ((ModelElement.QObject) -> Unit)? = null

    fun noNextReceiverHandler(fn: ((ModelElement.QObject) -> Unit)?){
        noNextReceiverHandler = fn
    }

    abstract fun selectNextReceiver(): QObjectReceiverIfc?

    final override fun send(qObject: ModelElement.QObject) {
        val selected = selectNextReceiver()
        if (selected != null) {
            beforeSendingAction?.action(selected, qObject)
            selected.receive(qObject)
            afterSendingAction?.action(selected, qObject)
        } else {
            noNextReceiverHandler?.invoke(qObject)
        }
    }

    private var beforeSendingAction: SendingActionIfc? = null

    fun beforeSendingAction(action : SendingActionIfc){
        beforeSendingAction = action
    }

    private var afterSendingAction: SendingActionIfc? = null

    fun afterSendingAction(action : SendingActionIfc){
        afterSendingAction = action
    }
}

/**
 *  Represents an iterator based sequence of receivers that can be used
 *  to send the qObject to the next receiver. At the end of the iterator
 *  the default behavior is to silently end.
 */
class ReceiverSequence(
    private val receiverItr: ListIterator<QObjectReceiverIfc>
) : QObjectSender() {

    override fun selectNextReceiver(): QObjectReceiverIfc? {
        return if (receiverItr.hasNext()){
            receiverItr.next()
        } else {
            null
        }
    }
}
