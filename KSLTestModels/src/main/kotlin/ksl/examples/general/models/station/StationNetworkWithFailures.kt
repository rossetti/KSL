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
import ksl.modeling.station.SingleQStation
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A single-station model whose server is subject to time-based (calendar-clock)
 *  breakdowns: it fails after an exponential time-to-failure and is down for an
 *  exponential time-to-repair. Failures are finish-then-fail (the in-service job
 *  completes before the server goes down).
 */
class StationNetworkWithFailures(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    private val myServer: SingleQStation = net.singleQStation("Server", ExponentialRV(0.7, 2))

    /** Exposes the server so callers can read its failure/availability statistics. */
    val server: ksl.modeling.station.SingleQStationCIfc
        get() = myServer

    init {
        val exit = net.sink("Exit")
        myServer.nextReceiver(exit)
        myServer.useTimeBasedFailures(
            timeToFailure = ExponentialRV(100.0, 3),
            timeToRepair = ExponentialRV(20.0, 4)
        )
        net.source("Arrivals", ExponentialRV(1.0, 1), firstReceiver = myServer)
    }
}

fun main() {
    val model = Model("StationNetworkWithFailures")
    val m = StationNetworkWithFailures(model, "Fail")
    model.numberOfReplications = 30
    model.lengthOfReplication = 50000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
    println()
    println("Number of failures = ${m.server.resource.numTimesFailed.acrossReplicationStatistic.average}")
    println("Failed fraction    = ${m.server.resource.failedStateProportion.acrossReplicationStatistic.average}")
    println("Utilization        = ${m.server.resource.utilization.acrossReplicationStatistic.average}")
}
