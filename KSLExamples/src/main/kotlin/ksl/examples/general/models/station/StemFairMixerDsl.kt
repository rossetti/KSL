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

import ksl.modeling.station.ByTypeRouter
import ksl.modeling.station.QObjectClass
import ksl.modeling.station.queueingNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  STEM Career Fair Mixer expressed with the Phase-1 builder DSL. Compare with
 *  [StemFairMixerStation]: the topology reads top-down inside `queueingNetwork`.
 */
fun main() {
    val model = Model("StemFairMixerDsl")

    val wanderRV = BernoulliRV(0.5, 6)
    val leaveRV = BernoulliRV(0.1, 7)

    val net = model.queueingNetwork("Mixer") {
        // per-class system time + completions surface via QObjectClass registrations
        network.registerClass(QObjectClass("NonWanderer", typeId = 1))
        network.registerClass(QObjectClass("Wanderer", typeId = 2))
        network.registerClass(QObjectClass("Leaver", typeId = 3))

        val exit = sink("Exit")
        val jhBunt = station("JHBunt", ExponentialRV(6.0, 4), capacity = 3, nextReceiver = exit)
        val malWart = station("MalWart", ExponentialRV(3.0, 5), capacity = 2, nextReceiver = jhBunt)
        // post-wander: leavers exit; others go to MalWart
        val postWander = ByTypeRouter(mapOf(3 to exit), default = malWart)
        network.register("PostWanderRouter", postWander)
        val wander = delay("Wander", TriangularRV(15.0, 20.0, 45.0, 3), nextReceiver = postWander)
        // post-name-tag: non-wanderers skip Wander
        val postNameTag = ByTypeRouter(mapOf(1 to malWart), default = wander)
        network.register("PostNameTagRouter", postNameTag)
        val nameTag = delay("NameTag", UniformRV(15.0 / 60.0, 45.0 / 60.0, 2), nextReceiver = postNameTag)

        source(
            "Arrivals", ExponentialRV(2.0, 1),
            firstReceiver = nameTag,
            marking = { q ->
                val isWanderer = wanderRV.value > 0.5
                val isLeaver = isWanderer && (leaveRV.value > 0.5)
                q.qObjectType = when {
                    !isWanderer -> 1
                    !isLeaver -> 2
                    else -> 3
                }
            }
        )
    }

    model.numberOfReplications = 400
    model.lengthOfReplication = 6.0 * 60.0
    model.simulate()
    model.print()
    println()
    println("Overall system time = ${net.systemTime.acrossReplicationStatistic.average}")
    println("Wanderer time       = ${net.classSystemTime("Wanderer")?.acrossReplicationStatistic?.average}")
    println("Leaver time         = ${net.classSystemTime("Leaver")?.acrossReplicationStatistic?.average}")
}
