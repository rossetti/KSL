/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.models.station

import ksl.modeling.station.ShortestQueueRouter
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  Two parallel single-server stations fed by a join-shortest-queue dispatcher,
 *  built with the Phase-0 routing primitives. Arrivals are dispatched to whichever
 *  server currently has the least work in process.
 */
class StationNetworkParallelServers(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    init {
        val exit = net.sink("Exit")
        val server1 = net.singleQStation("Server1", ExponentialRV(0.8, 2), nextReceiver = exit)
        val server2 = net.singleQStation("Server2", ExponentialRV(0.8, 3), nextReceiver = exit)
        val dispatch = ShortestQueueRouter(listOf(server1, server2))
        net.register("Dispatch", dispatch)
        net.source("Arrivals", ExponentialRV(0.5, 1), firstReceiver = dispatch)
    }
}

fun main() {
    val model = Model("StationNetworkParallelServers")
    val ps = StationNetworkParallelServers(model, "PS")
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
    println()
    println("Number in system = ${ps.network.numInSystem.acrossReplicationStatistic.average}")
    println("System time      = ${ps.network.systemTime.acrossReplicationStatistic.average}")
}
