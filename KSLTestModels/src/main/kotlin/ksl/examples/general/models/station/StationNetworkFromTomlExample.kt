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
 *  Demonstrates describing a queueing network as data, serializing it to TOML,
 *  and building a runnable model from the TOML text via [QueueingNetworkModelBuilder].
 */
fun main() {
    fun exp(mean: Double) = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(mean)))

    val spec = QueueingNetworkSpec(
        name = "tandem",
        sources = listOf(SourceSpec("Arrivals", exp(6.0), routing = RoutingSpec.Direct("Station1"))),
        stations = listOf(
            StationSpec("Station1", exp(4.0), routing = RoutingSpec.Direct("Station2")),
            StationSpec("Station2", exp(3.0), routing = RoutingSpec.Direct("Exit"))
        ),
        sinks = listOf(SinkSpec("Exit"))
    )

    val toml = QueueingNetworkToml.encode(spec)
    println("=== Network described in TOML ===")
    println(toml)

    // build a runnable model straight from the TOML text
    val model = QueueingNetworkModelBuilder.fromToml(toml).build()
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
}
