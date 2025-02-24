package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.simulation.ModelElement.QObject

/** A generic interface that can be implemented to
 * facilitate the receiving of QObjects for processing.
 *
 * @author rossetti
 */
fun interface QObjectReceiverIfc<T: ModelElement.QObject<T>> {
    fun receive(arrivingQObject: T)
}

class DoNothingReceiver<T: ModelElement.QObject<T>> : QObjectReceiverIfc<T> {
    override fun receive(arrivingQObject: T) {
    }
}

class NotImplementedReceiver<T: ModelElement.QObject<T>> : QObjectReceiverIfc<T> {
    override fun receive(arrivingQObject: T) {
        val sb = StringBuilder().apply {
            appendLine("Attempted to send ${arrivingQObject.name} to a not implemented receiver.")
            appendLine("The qObject may be trying to leave receiver ${arrivingQObject.currentReceiver}")
            appendLine("Check your station receiver assignments for NotImplementedReceiver assignments.")
        }
        TODO(sb.toString())
    }
}