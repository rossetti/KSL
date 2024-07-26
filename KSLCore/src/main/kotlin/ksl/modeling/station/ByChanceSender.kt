package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc

/**
 *  Promises to randomly pick the receiver and send the
 *  arriving QObject instance to the receiver.
 */
class ByChanceSender(
    private val picker: RElementIfc<QObjectReceiverIfc>
) : QObjectReceiverIfc {

    override fun receive(arrivingQObject: ModelElement.QObject) {
        val selected = picker.randomElement
        beforeSendingAction?.invoke(selected, arrivingQObject)
        selected.receive(arrivingQObject)
        afterSendingAction?.invoke(selected, arrivingQObject)
    }

    var beforeSendingAction: ((receiver: QObjectReceiverIfc, qObject: ModelElement.QObject) -> Unit)? = null

    var afterSendingAction: ((receiver: QObjectReceiverIfc, qObject: ModelElement.QObject) -> Unit)? = null
}