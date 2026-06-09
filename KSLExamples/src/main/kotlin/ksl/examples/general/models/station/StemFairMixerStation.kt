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
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  STEM Career Fair Mixer (book chapter 6), reimplemented with the station
 *  framework (no DSL). The chapter-6 model is the simple form: stationary
 *  exponential arrivals (no NHPP), no capacity schedules, sequential resource
 *  use, with class-based branching.
 *
 *  Three student classes by `qObjectType`:
 *   - 1 = NonWanderer: name-tag -> MalWart -> JHBunt -> exit
 *   - 2 = Wanderer (non-leaver): name-tag -> wander -> MalWart -> JHBunt -> exit
 *   - 3 = Leaver: name-tag -> wander -> exit
 *
 *  Sampling matches the legacy: independent Bernoulli draws for wander (p=0.5)
 *  and leave (p=0.1), with leave consulted only when wander is true. Streams
 *  are pinned to the legacy's stream numbers; the streams are consumed in a
 *  different order than the process-view version, so results are statistical
 *  not bit-identical.
 */
class StemFairMixerStation(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    // sampling RVs used by the marking hook (created once so streams persist)
    private val wanderRV = BernoulliRV(0.5, 6)
    private val leaveRV = BernoulliRV(0.1, 7)

    init {
        // per-class statistics are surfaced via QObjectClass registrations
        net.registerClass(QObjectClass("NonWanderer", typeId = 1))
        net.registerClass(QObjectClass("Wanderer", typeId = 2))
        net.registerClass(QObjectClass("Leaver", typeId = 3))

        val exit = net.sink("Exit")
        // JHBunt last — its activity time is the talk; the next-receiver is the sink
        val jhBunt = net.singleQStation("JHBunt", ExponentialRV(6.0, 4), capacity = 3, nextReceiver = exit)
        // MalWart precedes JHBunt for both type 1 and type 2
        val malWart = net.singleQStation("MalWart", ExponentialRV(3.0, 5), capacity = 2, nextReceiver = jhBunt)
        // post-wander router: type 3 -> exit, others -> MalWart
        val postWander = ByTypeRouter(
            mapOf(3 to exit),
            default = malWart
        )
        net.register("PostWanderRouter", postWander)
        // wandering delay (pure delay, no contention)
        val wander = net.activityStation("Wander", TriangularRV(15.0, 20.0, 45.0, 3), nextReceiver = postWander)
        // post-name-tag router: type 1 -> MalWart (skip wander), others -> Wander
        val postNameTag = ByTypeRouter(
            mapOf(1 to malWart),
            default = wander
        )
        net.register("PostNameTagRouter", postNameTag)
        // name-tag delay (pure delay)
        val nameTag = net.activityStation("NameTag", UniformRV(15.0 / 60.0, 45.0 / 60.0, 2), nextReceiver = postNameTag)

        net.source(
            name = "Arrivals",
            interArrivalRV = ExponentialRV(2.0, 1),
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
}

fun main() {
    val model = Model("StemFairMixerStation")
    val sfm = StemFairMixerStation(model, "Mixer")
    model.numberOfReplications = 400
    model.lengthOfReplication = 6.0 * 60.0
    model.simulate()
    model.print()
    println()
    println("Overall system time   = ${sfm.network.systemTime.acrossReplicationStatistic.average}")
    println("NonWanderer time      = ${sfm.network.classSystemTime("NonWanderer")?.acrossReplicationStatistic?.average}")
    println("Wanderer time         = ${sfm.network.classSystemTime("Wanderer")?.acrossReplicationStatistic?.average}")
    println("Leaver time           = ${sfm.network.classSystemTime("Leaver")?.acrossReplicationStatistic?.average}")
}
