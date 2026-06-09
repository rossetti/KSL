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
 *  A SinkStation is the network's terminal egress: it disposes of the QObject
 *  instances it receives, decrementing the number in the system and recording
 *  the total time in the system. It is the self-contained special case of a
 *  [NetworkEgress] and generalizes [DisposalStation] by tying the disposal into
 *  the owning [StationNetwork]'s system-level responses and event stream.
 *
 *  Typically created via [StationNetwork.sink].
 *
 *  @param network the network this sink belongs to
 *  @param name the name of the station
 */
class SinkStation internal constructor(
    private val network: StationNetwork,
    name: String? = null
) : ModelElement(network, name), NetworkEgress, RoutingOutletsIfc {

    override val portName: String
        get() = this.name

    // A sink is terminal: it has no outlets and is exempt from the dangling check.
    override fun outlets(): List<QObjectReceiverIfc> = emptyList()

    override val hasOnwardRouting: Boolean
        get() = true

    override fun receive(arrivingQObject: QObject) {
        // exit-time validation: any held resource is a leak (cannot be released later)
        network.verifyNoAllocations(arrivingQObject, this.name)
        network.objectExited(arrivingQObject, this, transferred = false)
    }
}
