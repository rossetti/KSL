package ksl.modeling.station

import ksl.simulation.ModelElement

/** A generic interface to facilitate the sending of
 * QObjects
 *
 * @author rossetti
 */
fun interface SendQObjectIfc {
    fun send(qObj: ModelElement.QObject)
}