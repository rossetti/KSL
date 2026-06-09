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
import ksl.utilities.random.robj.RElementIfc

/**
 *  Routes each arriving QObject to the station with the smallest work-in-process
 *  among [stations], as ordered by [comparator] (default: fewest at station). This
 *  is the join-shortest-queue policy and generalizes the pick-station receiver.
 *
 *  @param stations the candidate stations, which must be non-empty
 *  @param comparator the ordering used to pick the "smallest" station
 */
class ShortestQueueRouter(
    stations: List<Station>,
    private val comparator: Comparator<Station> = StationWIPComparator()
) : Router() {

    init {
        require(stations.isNotEmpty()) { "ShortestQueueRouter requires at least one station." }
    }

    private val myStations: List<Station> = stations.toList()

    override fun selectNextReceiver(qObject: ModelElement.QObject): QObjectReceiverIfc =
        myStations.minWith(comparator)

    override fun destinations(): List<QObjectReceiverIfc> = myStations
}

/**
 *  Routes arriving QObject instances to [receivers] in cyclic order, distributing
 *  successive instances round-robin regardless of their content.
 *
 *  @param receivers the destinations to cycle through, which must be non-empty
 */
class RoundRobinRouter(
    receivers: List<QObjectReceiverIfc>
) : Router() {

    init {
        require(receivers.isNotEmpty()) { "RoundRobinRouter requires at least one receiver." }
    }

    private val myReceivers: List<QObjectReceiverIfc> = receivers.toList()
    private var nextIndex: Int = 0

    override fun selectNextReceiver(qObject: ModelElement.QObject): QObjectReceiverIfc {
        val selected = myReceivers[nextIndex]
        nextIndex = (nextIndex + 1) % myReceivers.size
        return selected
    }

    override fun destinations(): List<QObjectReceiverIfc> = myReceivers

    override fun resetRouter() {
        nextIndex = 0
    }
}

/**
 *  Routes each arriving QObject to a receiver chosen at random by [picker]. The
 *  [knownDestinations] make the random choice introspectable for the network
 *  graph (the picker alone does not expose its elements). Supply a picker — such
 *  as an [ksl.modeling.elements.REmpiricalList] — over the same destinations.
 *
 *  @param picker the random element provider that selects the next receiver
 *  @param knownDestinations the receivers the picker chooses among, for graph introspection
 */
class ProbabilisticRouter(
    private val picker: RElementIfc<QObjectReceiverIfc>,
    knownDestinations: List<QObjectReceiverIfc>
) : Router() {

    private val myDestinations: List<QObjectReceiverIfc> = knownDestinations.toList()

    override fun selectNextReceiver(qObject: ModelElement.QObject): QObjectReceiverIfc =
        picker.randomElement

    override fun destinations(): List<QObjectReceiverIfc> = myDestinations
}
