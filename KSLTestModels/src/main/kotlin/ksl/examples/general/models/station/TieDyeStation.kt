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
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  Tie-Dye T-Shirts (book chapter 6), reimplemented with Phase-3 station
 *  primitives. The fork-join showcase: an Order spawns N Shirts (N = the
 *  order's size, 3 or 5), the Order does paperwork while the Shirts go through
 *  the shirt makers concurrently, then the Order waits for *its* shirts at the
 *  join and finishes with packaging.
 *
 *  Resources:
 *  - ShirtMakers: shared [SResourcePool], capacity 2 (all shirts share these).
 *  - Packager: free-standing [SResource], capacity 1, *used twice* (paperwork and
 *    packaging) — modeled with explicit [SeizeStation]/[ReleaseStation] pairs
 *    around the delay, since the resource is held across two unrelated points
 *    in the flow.
 *
 *  The legacy uses ProcessModel + BlockingQueue; this is a pure station-view
 *  rendering. Stream consumption order differs, so agreement is statistical.
 */
class TieDyeStation(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    private val sizeRV: DEmpiricalRV = DEmpiricalRV(doubleArrayOf(3.0, 5.0), doubleArrayOf(0.75, 1.0), streamNum = 1)
    private val orderTBA: ExponentialRV = ExponentialRV(60.0, 10)
    private val paperworkRV: UniformRV = UniformRV(8.0, 10.0, streamNum = 2)
    private val shirtTimeRV: UniformRV = UniformRV(15.0, 25.0, streamNum = 3)
    private val packagingRV: TriangularRV = TriangularRV(5.0, 10.0, 15.0, streamNum = 4)

    init {
        // shared resources
        val shirtMakers = net.resourcePool("ShirtMakers", capacity = 2)
        val packager = net.resource("Packager", capacity = 1)

        // build the topology back-to-front so each downstream is already constructed
        val exit = net.sink("Exit")

        // After the join, the parent (Order) seizes Packager again for packaging.
        // Packaging delay station has no resource (pure delay; the seize provides the contention).
        val releasePackager2 = net.releaseStation("ReleasePackager2", packager, nextReceiver = exit)
        val packaging = net.activityStation("Packaging", packagingRV, nextReceiver = releasePackager2)
        val seizePackager2 = net.seizeStation("SeizePackager2", packager, nextReceiver = packaging)

        // join releases the parent Order onward to packaging once all its shirts arrive
        val joinStation = net.joinStation("Join", nextReceiver = seizePackager2)

        // Shirt (child) path: pooled shirt-maker station -> Join's child input.
        // ResourcePoolStation handles seize/delay/release as one welded operation,
        // since the shirt-makers are not held across separate steps.
        val shirtMaking = net.resourcePoolStation("ShirtMaking", shirtMakers, shirtTimeRV,
            nextReceiver = joinStation.childInput())

        // Paperwork (parent) path: seize Packager -> paperwork delay -> release Packager -> Join (parent input).
        // Here we use explicit Seize/Release because the Packager is held across the paperwork
        // and is needed again later for packaging -- showing the Arena-style atomic operations.
        val releasePackager1 = net.releaseStation("ReleasePackager1", packager,
            nextReceiver = joinStation.parentInput())
        val paperwork = net.activityStation("Paperwork", paperworkRV, nextReceiver = releasePackager1)
        val seizePackager1 = net.seizeStation("SeizePackager1", packager, nextReceiver = paperwork)

        // Fork: parent -> seize-packager for paperwork; children -> shirt-making.
        // The child count is read from the parent's qObjectType (stamped at the source).
        val fork = net.forkStation(
            name = "Fork",
            join = joinStation,
            childCount = ChildCountIfc { p -> p.qObjectType },
            childFactory = null,
            childReceiver = shirtMaking,
            nextReceiver = seizePackager1
        )

        // Orders arrive; sampled size (3 or 5) is stamped as the qObjectType so the fork can read it
        net.source(
            name = "Orders",
            interArrivalRV = orderTBA,
            firstReceiver = fork,
            marking = { q -> q.qObjectType = sizeRV.value.toInt() }
        )
    }
}

fun main() {
    val model = Model("TieDyeStation")
    val td = TieDyeStation(model, "TieDye")
    model.numberOfReplications = 30
    model.lengthOfReplication = 480.0
    model.simulate()
    model.print()
    println()
    println("Orders in system  = ${td.network.numInSystem.acrossReplicationStatistic.average}")
    println("Order system time = ${td.network.systemTime.acrossReplicationStatistic.average}")
    println("Orders completed  = ${td.network.numCompleted.acrossReplicationStatistic.average}")
}
