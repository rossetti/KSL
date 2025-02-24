package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc

fun interface SendingActionIfc<T: ModelElement.QObject<T>> {
    fun action(receiver: QObjectReceiverIfc<T>, qObject: T)
}

/**
 *  Promises to randomly pick the receiver and send the
 *  arriving QObject instance to the receiver. Can act as
 *  both a receiver and a sender of QObject instances. Receiving
 *  instances are immediately sent.
 */
open class ByChanceSender<T: ModelElement.QObject<T>>(
    private val picker: RElementIfc<QObjectReceiverIfc<T>>
) : QObjectSender<T>(), QObjectReceiverIfc<T>{

    final override fun receive(arrivingQObject: T) {
        send(arrivingQObject)
    }

    final override fun selectNextReceiver(): QObjectReceiverIfc<T> {
        return picker.randomElement
    }

}