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

package ksl.animation.emitters.station

import ksl.animation.AnimationEvent
import ksl.modeling.station.NetworkEgress
import ksl.modeling.station.NetworkIngress
import ksl.modeling.station.NetworkObserver
import ksl.modeling.station.StationNetwork
import ksl.simulation.ModelElement

/**
 * A [NetworkObserver] that emits the station-network flow events:
 * [AnimationEvent.EnteredNetwork] when a QObject enters through an ingress,
 * [AnimationEvent.ExitedNetwork] when it leaves (is disposed) through an egress, and
 * [AnimationEvent.Transferred] when it is handed off to another network at an egress.
 * Attach with `network.attachNetworkObserver(NetworkAnimationEmitter(network))`; the
 * network is unchanged.
 *
 * The QObject's numeric id identifies the flowing entity; ports are identified by their
 * [NetworkIngress.portName]/[NetworkEgress.portName].
 *
 * @param network the station network whose flow to animate
 */
class NetworkAnimationEmitter(private val network: StationNetwork) : NetworkObserver {

    override fun enteredNetwork(qObject: ModelElement.QObject, ingress: NetworkIngress) {
        emit(
            AnimationEvent.EnteredNetwork(
                network.time, qObject.id, network.name, ingress.portName,
                qObject.qObjectType, network.classNameForType(qObject.qObjectType)
            )
        )
    }

    override fun exitedNetwork(qObject: ModelElement.QObject, egress: NetworkEgress) {
        emit(AnimationEvent.ExitedNetwork(network.time, qObject.id, network.name, egress.portName))
    }

    override fun transferred(qObject: ModelElement.QObject, egress: NetworkEgress) {
        emit(AnimationEvent.Transferred(network.time, qObject.id, network.name, egress.portName))
    }

    private fun emit(event: AnimationEvent) {
        val sink = network.model.animationSink
        if (sink.isActive) sink.emit(event)
    }
}
