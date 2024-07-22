package ksl.modeling.station

import ksl.simulation.ModelElement

/** A generic interface that can be implemented to allow
 * facilitates the receiving of QObjects
 *
 * @author rossetti
 */
fun interface ReceiveQObjectIfc {
    fun receive(qObj: ModelElement.QObject)
}