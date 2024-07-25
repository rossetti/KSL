package ksl.modeling.station

import ksl.simulation.ModelElement
import ksl.simulation.ModelElement.QObject

/** A generic interface that can be implemented to
 * facilitate the receiving of QObjects for processing.
 *
 * @author rossetti
 */
fun interface QObjectReceiverIfc {
    fun receive(qObject: ModelElement.QObject)
}

object DoNothingReceiver : QObjectReceiverIfc {
    override fun receive(qObject: QObject) {
    }
}