package ksl.modeling.station

import ksl.simulation.ModelElement

/**
 *  A functional interface that promises to send. Within the
 *  context of qObjects a sender will cause a qObject
 *  to be (eventually) received by a receiver.
 */
fun interface QObjectSenderIfc {
    fun send()
}

/**
 *  An abstract base class that may use the current state of
 *  its qObject to specify its sending functionality.
 */
abstract class QObjectSender(protected val myQObject: ModelElement.QObject) : QObjectSenderIfc

/**
 *  Represents an iterator based sequence of receivers that can be used
 *  to send the qObject to the next receiver. At the end of the iterator
 *  the default behavior is to silently end.
 */
open class ReceiverSequence(
    private val receiverItr: ListIterator<QObjectReceiverIfc>,
    qObject: ModelElement.QObject
) : QObjectSender(qObject) {

    override fun send() {
        if (receiverItr.hasNext()){
            receiverItr.next().receive(myQObject)
        } else {
            handleIteratorEnd()
        }
    }

    /**
     *  Can be overridden to provide functionality when
     *  the iterator has no more receivers.
     */
    protected open fun handleIteratorEnd(){

    }

}
