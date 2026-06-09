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

import ksl.modeling.station.queueingNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  Drive-Through Pharmacy expressed with the Phase-1 builder DSL. Compare with
 *  [DriveThroughPharmacyStation]: the topology reads top-to-bottom declaratively.
 */
fun main() {
    val model = Model("DriveThroughPharmacyDsl")
    val numServers = 1

    val net = model.queueingNetwork("Pharmacy") {
        val exit = sink("Exit")
        val pharmacist = station("Pharmacist", ExponentialRV(0.5, 2), capacity = numServers, nextReceiver = exit)
        val arrivals = source("Arrivals", ExponentialRV(1.0, 1))
        arrivals routeTo pharmacist
    }

    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
    println()
    println("Number in system  = ${net.numInSystem.acrossReplicationStatistic.average}")
    println("System time       = ${net.systemTime.acrossReplicationStatistic.average}")
    println("Throughput        = ${net.numCompleted.acrossReplicationStatistic.average}")
}
