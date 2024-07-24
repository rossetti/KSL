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
     * A QObject may or may not have a helper object that implements the
     * ListIterator<QObjectReceiverIfc> interface.  If this helper object is supplied it will
     * be used to send the processed QObject to its next location for
     * processing.
     *
     * If the QObject does not have the helper object, then the nextReceiver
     * property is uses (if not null) to determine the next location for the qObject.
     *
     * If neither helper object is supplied then the station quietly does nothing
     * with the qObject.
     *
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