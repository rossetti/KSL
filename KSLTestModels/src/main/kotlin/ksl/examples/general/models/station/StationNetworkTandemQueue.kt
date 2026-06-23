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

import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  The classic two-station tandem queue, built with the Phase-0 [StationNetwork]
 *  primitives. Compare with `ksl.examples.book.chapter4.TandemQueue`, which builds
 *  the identical model by hand: this version has no hand-rolled arrival event,
 *  number-in-system bookkeeping, or exit receiver — the network owns all of it.
 */
class StationNetworkTandemQueue(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    /** Exposes the network's read-only view so callers can observe its responses. */
    val network: StationNetworkCIfc
        get() = net

    init {
        val station1 = net.singleQStation("Station1", ExponentialRV(4.0, 2))
        val station2 = net.singleQStation("Station2", ExponentialRV(3.0, 3))
        val exit = net.sink("Exit")
        val arrivals = net.source("Arrivals", ExponentialRV(6.0, 1))

        arrivals.firstReceiver(station1)
        station1.nextReceiver(station2)
        station2.nextReceiver(exit)
    }
}

fun main() {
    val model = Model("StationNetworkTandemQueue")
    val tq = StationNetworkTandemQueue(model, "TQ")
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
    println()
    println("Number in system  = ${tq.network.numInSystem.acrossReplicationStatistic.average}")
    println("System time       = ${tq.network.systemTime.acrossReplicationStatistic.average}")
    println("Number completed  = ${tq.network.numCompleted.acrossReplicationStatistic.average}")
}
