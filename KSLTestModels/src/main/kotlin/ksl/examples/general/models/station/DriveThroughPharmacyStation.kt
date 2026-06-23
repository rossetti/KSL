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
 *  Drive-Through Pharmacy (book chapter 4), reimplemented with the Phase-0
 *  [StationNetwork] primitives (no DSL).
 *
 *  The legacy `DriveThroughPharmacy` is hand-coded with `EventAction`s and
 *  time-weighted variables — it does not use any station classes. This version
 *  is the queueing-network rendering of the same system: an arrival source, a
 *  single-queue station with one pharmacist (configurable), and a sink.
 *
 *  Streams are pinned to match the legacy (arrival stream 1, service stream 2)
 *  but the stream consumption *order* differs from the event-action wiring, so
 *  results are expected to match statistically (not bit-for-bit).
 */
class DriveThroughPharmacyStation(
    parent: ModelElement,
    numServers: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(numServers >= 1) { "The number of pharmacists must be >= 1" }
    }

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    /** Read-only view of the station network and its system-level responses. */
    val network: StationNetworkCIfc
        get() = net

    init {
        val exit = net.sink("Exit")
        val pharmacist = net.singleQStation(
            "Pharmacist",
            activityTime = ExponentialRV(0.5, 2),
            capacity = numServers,
            nextReceiver = exit
        )
        net.source("Arrivals", ExponentialRV(1.0, 1), firstReceiver = pharmacist)
    }
}

fun main() {
    val model = Model("DriveThroughPharmacyStation")
    val p = DriveThroughPharmacyStation(model, name = "Pharmacy")
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
    println()
    println("Number in system  = ${p.network.numInSystem.acrossReplicationStatistic.average}")
    println("System time       = ${p.network.systemTime.acrossReplicationStatistic.average}")
    println("Throughput        = ${p.network.numCompleted.acrossReplicationStatistic.average}")
}
