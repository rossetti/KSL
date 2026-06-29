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
import ksl.modeling.station.Station
import ksl.modeling.station.StationObserverIfc
import ksl.simulation.ModelElement

/**
 * A [StationObserverIfc] that emits [AnimationEvent.StationEntered] and
 * [AnimationEvent.StationExited] as QObjects enter and leave a station. Attach with
 * `station.attachStationObserver(...)`; this is the per-station flow animation enabled by the
 * additive station observer (decision D10/Phase 3B), complementing the network-boundary events.
 *
 * Stateless and shareable: the station is passed to each callback, so one instance can observe
 * many stations.
 */
class StationAnimationEmitter : StationObserverIfc {

    override fun entered(station: Station, qObject: ModelElement.QObject) {
        emit(station, AnimationEvent.StationEntered(station.time, qObject.id, station.name))
    }

    override fun exited(station: Station, qObject: ModelElement.QObject) {
        emit(station, AnimationEvent.StationExited(station.time, qObject.id, station.name))
    }

    private fun emit(station: Station, event: AnimationEvent) {
        val sink = station.model.animationSink
        if (sink.isActive) sink.emit(event)
    }
}
