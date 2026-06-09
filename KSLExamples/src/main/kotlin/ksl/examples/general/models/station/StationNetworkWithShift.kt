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

import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A single-station model whose server follows a repeating capacity schedule
 *  (on shift / off shift). Work that arrives off shift waits; when the shift
 *  resumes, the resource gains units and the waiting queue is served. The server
 *  is not interrupted mid-service when a shift ends (IGNORE-rule semantics).
 */
class StationNetworkWithShift(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    init {
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ExponentialRV(0.7, 2), nextReceiver = exit)

        val schedule = CapacitySchedule(this, repeatable = true)
        schedule.addItem(capacity = 1, duration = 480.0)   // on shift
        schedule.addItem(capacity = 0, duration = 480.0)   // off shift
        server.useCapacitySchedule(schedule)

        net.source("Arrivals", ExponentialRV(1.0, 1), firstReceiver = server)
    }
}

fun main() {
    val model = Model("StationNetworkWithShift")
    val m = StationNetworkWithShift(model, "Shift")
    model.numberOfReplications = 20
    model.lengthOfReplication = 9600.0   // 10 on/off cycles
    model.lengthOfReplicationWarmUp = 960.0
    model.simulate()
    model.print()
    println()
    println("Number in system = ${m.network.numInSystem.acrossReplicationStatistic.average}")
    println("System time      = ${m.network.systemTime.acrossReplicationStatistic.average}")
}
