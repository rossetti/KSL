package ksl.modeling.station

import ksl.simulation.ModelElement

/** A generic interface that can be implemented to
 * facilitate the receiving of QObjects for processing.
 *
 * @author rossetti
 */
fun interface QObjectReceiverIfc {
    fun receive(qObject: ModelElement.QObject)
}
