package ksl.modeling.station

import ksl.simulation.ModelElement

/**
 *  A station is a location that can receive and potentially
 *  process instances of the QObject class.
 */
abstract class Station(
    parent: ModelElement,
    var nextReceiver: QObjectReceiverIfc?,
    name: String? = null
) : ModelElement(parent, name), QObjectReceiverIfc {

    /**
     * A Station may or may not have a helper object that implements the
     * SendQObjectIfc interface.  If this helper object is supplied it will
     * be used to send the processed QObject to its next location for
     * processing.
     *
     * A Station may or may not have a helper object that implements the
     * ReceiveQObjectIfc interface.  If this helper object is supplied and
     * the SendQObjectIfc helper is not supplied, then the object that implements
     * the ReceiveQObjectIfc will be the next receiver for the QObject
     *
     * If neither helper object is supplied then a runtime exception will
     * occur when trying to use the send() method
     * @param qObject the completed QObject
     */
    protected open fun sendToNextReceiver(qObject: QObject) {
        if (qObject.receiverIterator != null){
            if (qObject.receiverIterator!!.hasNext()){
                qObject.receiverIterator!!.next().receive(qObject)
            }
        } else {
            nextReceiver?.receive(qObject)
        }
    }
}