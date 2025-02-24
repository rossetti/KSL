package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc

/**
 *  A functional interface that promises to send. Within the
 *  context of qObjects a sender should cause a qObject
 *  to be (eventually) received by a receiver.
 */
fun interface QObjectSenderIfc<T: ModelElement.QObject<T>> {
    fun send(qObject: T)
}

abstract class QObjectSender<T: ModelElement.QObject<T>>() :  QObjectSenderIfc<T> {

    /**
     *  Can be used to supply logic if the iterator does not have
     *  more receivers.
     */
    private var noNextReceiverHandler: ((T) -> Unit)? = null

    fun noNextReceiverHandler(fn: ((T) -> Unit)?){
        noNextReceiverHandler = fn
    }

    abstract fun selectNextReceiver(): QObjectReceiverIfc<T>?

    final override fun send(qObject: T) {
        val selected = selectNextReceiver()
        if (selected != null) {
            beforeSendingAction?.action(selected, qObject)
            selected.receive(qObject)
            afterSendingAction?.action(selected, qObject)
        } else {
            noNextReceiverHandler?.invoke(qObject)
        }
    }

    private var beforeSendingAction: SendingActionIfc<T>? = null

    fun beforeSendingAction(action : SendingActionIfc<T>){
        beforeSendingAction = action
    }

    private var afterSendingAction: SendingActionIfc<T>? = null

    fun afterSendingAction(action : SendingActionIfc<T>){
        afterSendingAction = action
    }
}

/**
 *  Represents an iterator based sequence of receivers that can be used
 *  to send the qObject to the next receiver. At the end of the iterator
 *  the default behavior is to silently end.
 */
class ReceiverSequence<T: ModelElement.QObject<T>>(
    private val receiverItr: ListIterator<QObjectReceiverIfc<T>>
) : QObjectSender<T>() {

    override fun selectNextReceiver(): QObjectReceiverIfc<T>? {
        return if (receiverItr.hasNext()){
            receiverItr.next()
        } else {
            null
        }
    }
}
