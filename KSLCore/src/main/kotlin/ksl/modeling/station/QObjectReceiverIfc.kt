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
        val sb = StringBuilder().apply {
            appendLine("Attempted to send ${arrivingQObject.name} to a not implemented receiver.")
            appendLine("The qObject may be trying to leave receiver ${arrivingQObject.currentReceiver}")
            appendLine("Check your station receiver assignments for NotImplementedReceiver assignments.")
        }
        TODO(sb.toString())
    }
}