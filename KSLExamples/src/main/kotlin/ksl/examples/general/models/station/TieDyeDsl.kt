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
import ksl.modeling.station.queueingNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  Tie-Dye T-Shirts expressed with the Phase-1 builder DSL. Same topology as
 *  [TieDyeStation], but the network reads top-to-bottom as a declarative recipe
 *  for the fork-join + shared-resource pattern.
 */
fun main() {
    val sizeRV = DEmpiricalRV(doubleArrayOf(3.0, 5.0), doubleArrayOf(0.75, 1.0), streamNum = 1)
    val orderTBA = ExponentialRV(60.0, 10)
    val paperworkRV = UniformRV(8.0, 10.0, streamNum = 2)
    val shirtTimeRV = UniformRV(15.0, 25.0, streamNum = 3)
    val packagingRV = TriangularRV(5.0, 10.0, 15.0, streamNum = 4)

    val model = Model("TieDyeDsl")

    val net = model.queueingNetwork("TieDye") {
        val shirtMakers = pool("ShirtMakers", capacity = 2)
        val packager = resource("Packager", capacity = 1)
        val exit = sink("Exit")

        // post-join: parent re-seizes Packager for packaging
        val releasePackager2 = release("ReleasePackager2", packager, nextReceiver = exit)
        val packaging = delay("Packaging", packagingRV, nextReceiver = releasePackager2)
        val seizePackager2 = seize("SeizePackager2", packager, nextReceiver = packaging)

        val joinStation = join("Join", nextReceiver = seizePackager2)

        // child path: pooled shirt-making -> Join#1 (child input)
        val shirtMaking = pooledStation("ShirtMaking", shirtMakers, shirtTimeRV,
            nextReceiver = joinStation.childInput())

        // parent path: seize Packager -> paperwork -> release -> Join#0 (parent input)
        val releasePackager1 = release("ReleasePackager1", packager,
            nextReceiver = joinStation.parentInput())
        val paperwork = delay("Paperwork", paperworkRV, nextReceiver = releasePackager1)
        val seizePackager1 = seize("SeizePackager1", packager, nextReceiver = paperwork)

        // fork: parent -> paperwork; children -> shirt-making
        val fork = fork(
            name = "Fork",
            join = joinStation,
            childCount = ChildCountIfc { p -> p.qObjectType },
            childReceiver = shirtMaking,
            nextReceiver = seizePackager1
        )

        // orders arrive; mark each with its size (3 or 5) for the fork's child-count hook
        source("Orders", orderTBA, firstReceiver = fork,
            marking = { q -> q.qObjectType = sizeRV.value.toInt() })
    }

    model.numberOfReplications = 30
    model.lengthOfReplication = 480.0
    model.simulate()
    model.print()
    println()
    println("Order system time = ${net.systemTime.acrossReplicationStatistic.average}")
}
