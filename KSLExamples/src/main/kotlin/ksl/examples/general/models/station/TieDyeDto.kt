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

import ksl.modeling.station.ChildCountIfc
import ksl.modeling.station.MarkingHookIfc
import ksl.modeling.station.config.*
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData

/**
 *  Tie-Dye T-Shirts described as data (a [QueueingNetworkSpec]) plus two
 *  named-hook entries — one for the per-order size marking (samples 3 or 5 and
 *  stamps it as qObjectType) and one for the fork's child-count function (reads
 *  qObjectType). Serialized to TOML, then built into a runnable model via
 *  [QueueingNetworkModelBuilder].
 *
 *  This demonstrates the DTO path including the named-hook registry pattern
 *  used for behavior that can't be serialized (per-instance sampling, dynamic
 *  child count, conditional routing). All structure lives in TOML; only the
 *  behavior hooks come from code at build time.
 */
fun main() {
    fun exp(mean: Double) = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(mean)))
    fun uni(min: Double, max: Double) = RVData(RVType.Uniform,
        mapOf("min" to doubleArrayOf(min), "max" to doubleArrayOf(max)))
    fun tri(min: Double, mode: Double, max: Double) = RVData(RVType.Triangular,
        mapOf("min" to doubleArrayOf(min), "mode" to doubleArrayOf(mode), "max" to doubleArrayOf(max)))

    val spec = QueueingNetworkSpec(
        name = "TieDye",
        sources = listOf(
            // marking happens at build time via a named hook; the hook samples a
            // size (3 or 5 with probs 0.75/0.25) and stamps it as qObjectType, which
            // the fork's childCount hook reads to spawn the right number of shirts
            SourceSpec("Orders", exp(60.0), marking = "sampleOrderSize",
                routing = RoutingSpec.Direct("Fork"))
        ),
        stations = listOf(
            // a SingleQStation with capacity 1 plays the part of "ActivityStation" here so
            // the DTO sticks to the StationSpec vocabulary; effectively a delay since no
            // additional pressure comes through (the SeizePackager throttles upstream)
            StationSpec("Paperwork", uni(8.0, 10.0), capacity = 1, routing = RoutingSpec.Direct("ReleasePackager1")),
            StationSpec("Packaging", tri(5.0, 10.0, 15.0), capacity = 1, routing = RoutingSpec.Direct("ReleasePackager2"))
        ),
        sinks = listOf(SinkSpec("Exit")),
        pools = listOf(PoolSpec("ShirtMakers", capacity = 2)),
        pooledStations = listOf(
            PooledStationSpec("ShirtMaking", "ShirtMakers", uni(15.0, 25.0), routing = RoutingSpec.Direct("Join#1"))
        ),
        resources = listOf(ResourceSpec("Packager", capacity = 1)),
        seizeStations = listOf(
            SeizeStationSpec("SeizePackager1", resource = "Packager", routing = RoutingSpec.Direct("Paperwork")),
            SeizeStationSpec("SeizePackager2", resource = "Packager", routing = RoutingSpec.Direct("Packaging"))
        ),
        releaseStations = listOf(
            ReleaseStationSpec("ReleasePackager1", resource = "Packager", routing = RoutingSpec.Direct("Join")),
            ReleaseStationSpec("ReleasePackager2", resource = "Packager", routing = RoutingSpec.Direct("Exit"))
        ),
        joinStations = listOf(JoinStationSpec("Join", routing = RoutingSpec.Direct("SeizePackager2"))),
        forkStations = listOf(
            ForkStationSpec(
                "Fork", join = "Join",
                childCount = "byOrderType",
                childRouting = RoutingSpec.Direct("ShirtMaking"),
                routing = RoutingSpec.Direct("SeizePackager1")
            )
        )
    )

    val toml = QueueingNetworkToml.encode(spec)
    println("=== TOML description ===")
    println(toml)

    // The DTO carries only structure + distributions + named hook names. The user
    // supplies the hook implementations at build time.
    val sizeSampler = DEmpiricalRV(doubleArrayOf(3.0, 5.0), doubleArrayOf(0.75, 1.0), streamNum = 1)
    val model = QueueingNetworkModelBuilder(
        QueueingNetworkToml.decode(toml),
        predicates = emptyMap(),
        childFactories = emptyMap(),
        childCounts = mapOf("byOrderType" to ChildCountIfc { p -> p.qObjectType }),
        markings = mapOf("sampleOrderSize" to MarkingHookIfc { q, _ -> q.qObjectType = sizeSampler.value.toInt() })
    ).build()
    model.numberOfReplications = 30
    model.lengthOfReplication = 480.0
    model.simulate()
    model.print()
    println()
    println("Order system time = ${model.response("TieDye:SystemTime")?.acrossReplicationStatistic?.average}")
}
