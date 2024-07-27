package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc

fun interface SendingActionIfc {
    fun action(receiver: QObjectReceiverIfc, qObject: ModelElement.QObject)
}

/**
 *  Promises to randomly pick the receiver and send the
 *  arriving QObject instance to the receiver.
 */
class ByChanceSender(
    private val picker: RElementIfc<QObjectReceiverIfc>
) : QObjectReceiverIfc {

    override fun receive(arrivingQObject: ModelElement.QObject) {
        val selected = picker.randomElement
        beforeSendingAction?.action(selected, arrivingQObject)
        selected.receive(arrivingQObject)
        afterSendingAction?.action(selected, arrivingQObject)
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