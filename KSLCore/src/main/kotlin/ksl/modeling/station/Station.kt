package ksl.modeling.station

import ksl.simulation.ModelElement

abstract class Station(
    parent: ModelElement,
    var sender: SendQObjectIfc? = null,
    name: String? = null
) : ModelElement(parent, name), ReceiveQObjectIfc {

    var nextReceiver: ReceiveQObjectIfc? = null

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
     * @param qObj the completed QObject
     */
    protected fun send(qObj: QObject) {
        if (sender != null) {
            sender!!.send(qObj)
        } else if (nextReceiver != null) {
            nextReceiver!!.receive(qObj)
        } else {
            val sb = StringBuilder()
            sb.append("There was no sender or receiver for station: ")
            sb.appendLine(name)
            sb.appendLine(", both had null values.  Make sure to create the receiver or sender.")
            throw RuntimeException(sb.toString())
        }
    }
}