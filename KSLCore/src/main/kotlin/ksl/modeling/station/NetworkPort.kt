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
 *  A boundary port through which QObject instances enter a [StationNetwork].
 *  An ingress performs the network's entry bookkeeping (increment the number
 *  in system, publish the entered-network event) and then forwards the
 *  arriving instance to the first internal receiver.
 *
 *  A [SourceStation] is the self-contained special case of an ingress that
 *  also generates the arriving instances. Externally created instances (for
 *  example, transferred from another network) can also enter through an
 *  ingress so that the network is an open system.
 */
interface NetworkIngress : QObjectReceiverIfc {
    /** The name of this port, unique within its [StationNetwork]. */
    val portName: String
}

/**
 *  A boundary port through which QObject instances leave a [StationNetwork].
 *  An egress performs the network's exit bookkeeping (decrement the number in
 *  system, record the system time, count the completion, publish the
 *  exited-network event) and then either disposes of the instance or hands it
 *  off to an external receiver.
 *
 *  A [SinkStation] is the self-contained special case of an egress that
 *  disposes of the instances it receives.
 */
interface NetworkEgress : QObjectReceiverIfc {
    /** The name of this port, unique within its [StationNetwork]. */
    val portName: String
}

/**
 *  Observes lifecycle events published by a [StationNetwork]. Override only the
 *  events of interest; all methods do nothing by default. This is the
 *  notification seam other modeling views (process, agent-based) subscribe to.
 */
interface NetworkObserver {

    /** Called immediately after [qObject] enters the network through [ingress]. */
    fun enteredNetwork(qObject: ModelElement.QObject, ingress: NetworkIngress) {}

    /** Called immediately after [qObject] is disposed by the network at [egress]. */
    fun exitedNetwork(qObject: ModelElement.QObject, egress: NetworkEgress) {}

    /** Called immediately after [qObject] is handed off (transferred) at [egress]. */
    fun transferred(qObject: ModelElement.QObject, egress: NetworkEgress) {}
}
