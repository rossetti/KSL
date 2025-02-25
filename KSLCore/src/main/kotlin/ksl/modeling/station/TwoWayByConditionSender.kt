package ksl.modeling.station

import ksl.simulation.ModelElement

class TwoWayByConditionSender(
    private val predicate: () -> Boolean,
    private val trueReceiver: QObjectReceiverIfc,
    private val falseReceiver: QObjectReceiverIfc
) : QObjectSender(), QObjectReceiverIfc {

    override fun receive(arrivingQObject: ModelElement.QObject) {
        send(arrivingQObject)
    }

    override fun selectNextReceiver(): QObjectReceiverIfc {
        return if (predicate.invoke()) trueReceiver else falseReceiver
    }
}