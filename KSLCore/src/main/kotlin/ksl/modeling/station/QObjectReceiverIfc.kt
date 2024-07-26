package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.simulation.ModelElement.QObject

/** A generic interface that can be implemented to
 * facilitate the receiving of QObjects for processing.
 *
 * @author rossetti
 */
fun interface QObjectReceiverIfc {
    fun receive(arrivingQObject: ModelElement.QObject)
}

object DoNothingReceiver : QObjectReceiverIfc {
    override fun receive(arrivingQObject: QObject) {
    }
}

object NotImplementedReceiver : QObjectReceiverIfc {
    override fun receive(arrivingQObject: QObject) {
        TODO("Attempted to send ${arrivingQObject.name} to a not implemented receiver. Check your station receiver assignments.")
    }
}