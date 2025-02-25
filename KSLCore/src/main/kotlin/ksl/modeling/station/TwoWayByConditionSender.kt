package ksl.modeling.station

import ksl.simulation.ModelElement

class TwoWayByConditionSender<T: ModelElement.QObject>(
    private val predicate: () -> Boolean,
    private val trueReceiver: QObjectReceiverIfc<T>,
    private val falseReceiver: QObjectReceiverIfc<T>
) : QObjectSender<T>(), QObjectReceiverIfc<T> {

    override fun receive(arrivingQObject: T) {
        send(arrivingQObject)
    }

    override fun selectNextReceiver(): QObjectReceiverIfc<T> {
        return if (predicate.invoke()) trueReceiver else falseReceiver
    }
}