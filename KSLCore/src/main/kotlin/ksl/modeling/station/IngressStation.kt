/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.station

import ksl.simulation.ModelElement

/**
 *  A non-generating entry port: accepts externally created or transferred QObject
 *  instances into the network (performing the entry bookkeeping) and forwards them
 *  to the first internal receiver. Unlike a [SourceStation] it does not generate
 *  arrivals; it is the receiving end of a [TransferStation] or any external producer.
 *
 *  A transferred instance keeps its original creation time, so end-to-end system
 *  time across networks is preserved (and recorded at the final network's sink).
 *
 *  @param network the network this ingress feeds
 *  @param firstReceiver where entering instances are sent
 *  @param name the name of the station
 */
class IngressStation internal constructor(
    private val network: StationNetwork,
    firstReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(network, name), NetworkIngress, RoutingOutletsIfc {

    override val portName: String
        get() = this.name

    private var myFirstReceiver: QObjectReceiverIfc = firstReceiver

    /** Sets the receiver that processes entering instances. */
    fun firstReceiver(receiver: QObjectReceiverIfc) {
        myFirstReceiver = receiver
    }

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myFirstReceiver === NotImplementedReceiver) emptyList() else listOf(myFirstReceiver)

    override val hasOnwardRouting: Boolean
        get() = myFirstReceiver !== NotImplementedReceiver

    override fun receive(arrivingQObject: QObject) {
        network.objectEntered(arrivingQObject, this, isNew = false)
        myFirstReceiver.receive(arrivingQObject)
    }
}
