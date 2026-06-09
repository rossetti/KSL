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

import ksl.modeling.station.config.QueueingNetworkModelBuilder
import ksl.modeling.station.config.QueueingNetworkSpec
import ksl.modeling.station.config.QueueingNetworkToml
import ksl.modeling.station.config.RoutingSpec
import ksl.modeling.station.config.SinkSpec
import ksl.modeling.station.config.SourceSpec
import ksl.modeling.station.config.StationSpec
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData

/**
 *  Drive-Through Pharmacy described as data (a [QueueingNetworkSpec]),
 *  serialized to TOML, then built into a runnable model via
 *  [QueueingNetworkModelBuilder]. Demonstrates the data-driven path: the model
 *  is identical to the DSL and hand-built forms, but its source of truth is a
 *  TOML string.
 */
fun main() {
    fun exp(mean: Double) = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(mean)))

    val spec = QueueingNetworkSpec(
        name = "Pharmacy",
        sources = listOf(SourceSpec("Arrivals", exp(1.0), routing = RoutingSpec.Direct("Pharmacist"))),
        stations = listOf(StationSpec("Pharmacist", exp(0.5), capacity = 1, routing = RoutingSpec.Direct("Exit"))),
        sinks = listOf(SinkSpec("Exit"))
    )

    val toml = QueueingNetworkToml.encode(spec)
    println("=== TOML description ===")
    println(toml)

    val model = QueueingNetworkModelBuilder.fromToml(toml).build()
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
}
