package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc

fun interface SendingActionIfc {
    fun action(receiver: QObjectReceiverIfc, qObject: ModelElement.QObject)
}

/**
 *  Promises to randomly pick the receiver and send the
 *  arriving QObject instance to the receiver. Can act as
 *  both a receiver and a sender of QObject instances. Receiving
 *  instances are immediately sent.
 */
open class ByChanceSender(
    private val picker: RElementIfc<QObjectReceiverIfc>
) : QObjectSender(), QObjectReceiverIfc{

    final override fun receive(arrivingQObject: ModelElement.QObject) {
        send(arrivingQObject)
    }

    final override fun selectNextReceiver(): QObjectReceiverIfc {
        return picker.randomElement
    }

}