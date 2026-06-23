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

import ksl.modeling.station.QObjectClass
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A two-class single-station model built with the Phase-0 multi-class primitives.
 *  Each class carries its own service-time random variable as its value object, and
 *  the station is told to use the instance's value object for its activity time. The
 *  network collects per-class time-in-system and throughput automatically.
 */
class StationNetworkMultiClass(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    init {
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", nextReceiver = exit)
        server.useQObjectForActivityTime = true

        val typeA = QObjectClass("TypeA", typeId = 1, valueObject = RandomVariable(this, ExponentialRV(2.0, 2)))
        val typeB = QObjectClass("TypeB", typeId = 2, valueObject = RandomVariable(this, ExponentialRV(5.0, 3)))

        net.source("ArrivalsA", ExponentialRV(8.0, 1), firstReceiver = server, qObjectClass = typeA)
        net.source("ArrivalsB", ExponentialRV(8.0, 4), firstReceiver = server, qObjectClass = typeB)
    }
}

fun main() {
    val model = Model("StationNetworkMultiClass")
    val mc = StationNetworkMultiClass(model, "MC")
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
    println()
    for (className in mc.network.classNames) {
        val st = mc.network.classSystemTime(className)!!.acrossReplicationStatistic.average
        val n = mc.network.classNumCompleted(className)!!.acrossReplicationStatistic.average
        println("$className: system time = $st, completed = $n")
    }
}
