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

import ksl.modeling.station.MarkingHookIfc
import ksl.modeling.station.config.ActivityStationSpec
import ksl.modeling.station.config.QObjectClassSpec
import ksl.modeling.station.config.QueueingNetworkModelBuilder
import ksl.modeling.station.config.QueueingNetworkSpec
import ksl.modeling.station.config.QueueingNetworkToml
import ksl.modeling.station.config.RoutingSpec
import ksl.modeling.station.config.SinkSpec
import ksl.modeling.station.config.SourceSpec
import ksl.modeling.station.config.StationSpec
import ksl.modeling.station.config.TypeBranch
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData

/**
 *  STEM Career Fair Mixer (chapter 6) described as a [QueueingNetworkSpec],
 *  serialized to TOML, then built into a runnable model via
 *  [QueueingNetworkModelBuilder]. Demonstrates the data-driven path with:
 *
 *  - per-class statistics via [QObjectClassSpec] entries (NonWanderer / Wanderer / Leaver)
 *  - by-type routing for the post-name-tag and post-wander dispatch
 *  - a marking-hook registry entry that samples the two Bernoulli draws and
 *    stamps the resulting `qObjectType`
 *  - [ActivityStationSpec] for the pure-delay name-tag and wandering stations
 *    (added to the spec specifically to support this model)
 */
fun main() {
    fun exp(mean: Double) = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(mean)))
    fun uniform(min: Double, max: Double) = RVData(
        RVType.Uniform, mapOf("min" to doubleArrayOf(min), "max" to doubleArrayOf(max))
    )
    fun triangular(min: Double, mode: Double, max: Double) = RVData(
        RVType.Triangular,
        mapOf(
            "min" to doubleArrayOf(min),
            "mode" to doubleArrayOf(mode),
            "max" to doubleArrayOf(max)
        )
    )

    val spec = QueueingNetworkSpec(
        name = "Mixer",
        classes = listOf(
            QObjectClassSpec("NonWanderer", typeId = 1),
            QObjectClassSpec("Wanderer", typeId = 2),
            QObjectClassSpec("Leaver", typeId = 3)
        ),
        sources = listOf(
            SourceSpec(
                "Arrivals", exp(2.0),
                marking = "classifyStudent",
                routing = RoutingSpec.Direct("NameTag")
            )
        ),
        activityStations = listOf(
            ActivityStationSpec("NameTag", uniform(15.0 / 60.0, 45.0 / 60.0),
                routing = RoutingSpec.ByType(
                    listOf(TypeBranch(1, "MalWart")),
                    default = "Wander"
                )),
            ActivityStationSpec("Wander", triangular(15.0, 20.0, 45.0),
                routing = RoutingSpec.ByType(
                    listOf(TypeBranch(3, "Exit")),
                    default = "MalWart"
                ))
        ),
        stations = listOf(
            StationSpec("MalWart", exp(3.0), capacity = 2, routing = RoutingSpec.Direct("JHBunt")),
            StationSpec("JHBunt", exp(6.0), capacity = 3, routing = RoutingSpec.Direct("Exit"))
        ),
        sinks = listOf(SinkSpec("Exit"))
    )

    val toml = QueueingNetworkToml.encode(spec)
    println("=== TOML description ===")
    println(toml)

    // build with the marking-hook registry; same RVs as the legacy (streams 6 and 7)
    val wanderRV = BernoulliRV(0.5, 6)
    val leaveRV = BernoulliRV(0.1, 7)

    val model = QueueingNetworkModelBuilder(
        spec,
        markings = mapOf(
            "classifyStudent" to MarkingHookIfc { q, _ ->
                val isWanderer = wanderRV.value > 0.5
                val isLeaver = isWanderer && (leaveRV.value > 0.5)
                q.qObjectType = when {
                    !isWanderer -> 1
                    !isLeaver -> 2
                    else -> 3
                }
            }
        )
    ).build()
    model.numberOfReplications = 400
    model.lengthOfReplication = 6.0 * 60.0
    model.simulate()
    model.print()
}
