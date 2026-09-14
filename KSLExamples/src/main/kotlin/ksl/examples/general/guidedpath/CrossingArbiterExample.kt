/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.guidedpath

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.ZoneCrossing
import ksl.modeling.guidedpath.rules.AlternatingArbiter
import ksl.modeling.guidedpath.rules.BoundedBatchArbiter
import ksl.modeling.guidedpath.rules.CrossingArbiterIfc
import ksl.modeling.guidedpath.rules.PedestrianPriorityArbiter
import ksl.modeling.guidedpath.rules.VehiclePriorityArbiter
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  One crossing, four disciplines, and the two failures that make the case for an arbiter.
 *
 *  A crossing arbitrates between two populations that want the same space and cannot both have it.
 *  The point of this example is not that one discipline is best -- none of them is -- but that
 *  **deciding only one of the two questions produces a broken model that still runs**, and that the
 *  break is visible only if you look at both sides at once.
 *
 *  - Decide only "may this walker go?" and, under any steady pedestrian flow, there is never an
 *    instant with nobody waiting, so the crossing never reopens and **vehicles starve**.
 *  - Decide only "should vehicles be held?" and traffic never leaves a long enough gap, so
 *    **pedestrians never cross**.
 *
 *  Both are run here. Neither raises, neither warns, and each produces a perfectly plausible table
 *  of numbers for a model that does not represent what it claims to.
 *
 *  ## Deterministic
 *
 *  No randomness at all: walkers arrive every `WALKER_EVERY` minutes and carts every `CART_EVERY`,
 *  and a zone is a minute. So every figure below can be checked by hand, and the example is a
 *  specification of behaviour rather than an estimate of it.
 */
object CrossingArbiterExample {

    const val HORIZON: Double = 120.0

    /** Zones are twelve feet and everything moves twelve feet a minute, so a zone is a minute. */
    const val ZONE: Double = 12.0

    const val WALKER_EVERY: Double = 1.5
    const val CART_EVERY: Double = 4.0
    const val WALK_TIME: Double = 2.0

    /** The four disciplines, made fresh for each run so none inherits another's state. */
    fun arbiters(): Map<String, CrossingArbiterIfc> = linkedMapOf(
        "PedestrianPriority" to PedestrianPriorityArbiter(),
        "VehiclePriority" to VehiclePriorityArbiter(),
        "Alternating" to AlternatingArbiter(walkTime = 6.0, driveTime = 6.0),
        "BoundedBatch" to BoundedBatchArbiter(batchSize = 2, maxWait = 5.0)
    )

    /**
     *  A one-way loop with a crossing part way along its outbound leg.
     *
     *  The cart circulates so that traffic is continuous; walkers cross and go.
     */
    class Town(parent: ModelElement, arbiter: CrossingArbiterIfc) : ProcessModel(parent, "Town") {

        // A one-way loop rather than a single aisle, so the cart can keep circulating: a
        // one-way link cannot be run backwards, and sending a cart home along one raises.
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Town")
            .link("Aisle", "A", "B", length = 6 * ZONE, zoneLength = ZONE)
            .link("Return", "B", "A", length = 6 * ZONE, zoneLength = ZONE)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(ZONE), 1, name = "Cart"
        )

        val crossing = ZoneCrossing(
            this, system, listOf(network.zone("Aisle.Zone3")!!), arbiter, name = "Walkway"
        )

        val walkQ = HoldQueue(this, "WalkQ")

        var cartTrips: Int = 0
            private set
        var walkersAcross: Int = 0
            private set

        inner class Walker : Entity() {
            val walk = process(isDefaultProcess = true) {
                crossOnFoot(crossing, WALK_TIME, walkQ)
                walkersAcross++
            }
        }

        // Named classes rather than lambdas because each one schedules itself, and a lambda that
        // refers to the property it is being assigned to cannot have its type inferred.
        private inner class WalkerAction : EventActionIfc<Nothing> {
            override fun action(event: KSLEvent<Nothing>) {
                activate(Walker().walk)
                schedule(this, WALKER_EVERY)
            }
        }

        private inner class CartAction : EventActionIfc<Nothing> {
            override fun action(event: KSLEvent<Nothing>) {
                // Sent back and forth so there is always traffic wanting the crossing. Counting
                // arrivals rather than dispatches is what makes the number mean "got through".
                if (!cart.isMoving) {
                    cart.sendTo(if (cart.currentLocation?.name == "B") "A" else "B")
                }
                schedule(this, CART_EVERY)
            }
        }

        private val myWalkerAction = WalkerAction()
        private val myCartAction = CartAction()

        init {
            // Attached ONCE, when the element is built. A listener attached in initialize() is
            // added again at the start of every replication and never removed, so replication n
            // runs with n of them and counts every arrival n times -- and the counter being
            // reset correctly just below is what hides it.
            cart.attachArrivalListener { cartTrips++ }
        }

        override fun initialize() {
            cartTrips = 0
            walkersAcross = 0
            schedule(myWalkerAction, WALKER_EVERY)
            schedule(myCartAction, 0.5)
        }
    }

    /** What one discipline did over the horizon. */
    class Outcome(
        val name: String,
        val cartTrips: Int,
        val walkersAcross: Int,
        val fracBarred: Double,
        val meanWaitToCross: Double,
        val turns: Double
    )

    fun runWith(name: String, arbiter: CrossingArbiterIfc): Outcome {
        val m = Model("Crossing_$name")
        val town = Town(m, arbiter)
        town.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Outcome(
            name = name,
            cartTrips = town.cartTrips,
            walkersAcross = town.walkersAcross,
            fracBarred = town.crossing.fracTimeBarred.withinReplicationStatistic.weightedAverage,
            meanWaitToCross = town.crossing.waitToCross.withinReplicationStatistic.weightedAverage,
            turns = town.crossing.turnsTaken.value
        )
    }
}

fun main() {
    println()
    println("One crossing, four disciplines, over ${CrossingArbiterExample.HORIZON.toInt()} minutes")
    println("A walker every ${CrossingArbiterExample.WALKER_EVERY} minutes, a cart dispatched every " +
            "${CrossingArbiterExample.CART_EVERY}, and a zone is a minute.")
    println()
    println("  %-20s %11s %11s %11s %11s %8s".format(
        "discipline", "cart trips", "walkers", "frac barred", "mean wait", "turns"
    ))

    val outcomes = CrossingArbiterExample.arbiters().map { (name, arbiter) ->
        CrossingArbiterExample.runWith(name, arbiter)
    }
    for (o in outcomes) {
        // A discipline under which nobody ever crosses has no wait to report, and printing NaN
        // for it would read as a defect rather than as the finding it is.
        val wait = if (o.walkersAcross == 0) "--" else "%.2f".format(o.meanWaitToCross)
        println("  %-20s %11d %11d %11.3f %11s %8.0f".format(
            o.name, o.cartTrips, o.walkersAcross, o.fracBarred, wait, o.turns
        ))
    }

    val ped = outcomes.first { it.name == "PedestrianPriority" }
    val veh = outcomes.first { it.name == "VehiclePriority" }

    println()
    println("  Read the two priority rows together, because each is a model that runs, reports, and")
    println("  does not represent what it claims to.")
    println()
    println("  PedestrianPriority put ${ped.walkersAcross} people across and moved the cart")
    println("  ${ped.cartTrips} time(s). A walker every ${CrossingArbiterExample.WALKER_EVERY} minutes")
    println("  taking ${CrossingArbiterExample.WALK_TIME} minutes to cross leaves no instant with the")
    println("  crossing empty, so it never reopens and the vehicles starve outright. A study that")
    println("  reported only pedestrian service would call this a success: nobody waited at all.")
    println()
    println("  Note what the run itself said about it. The guide path reported a transporter still")
    println("  waiting when the replication ended, and named what it was waiting for and who had it:")
    println("  the crossing. A model that stops moving says so rather than quietly reporting a")
    println("  smaller throughput.")
    println()
    println("  VehiclePriority is the exact mirror: ${veh.cartTrips} cart trips and")
    println("  ${veh.walkersAcross} people across -- not a slow crossing, no crossing. This rule never")
    println("  bars traffic, so a turn opens only if the crossing happens to be idle, and on a busy")
    println("  aisle it never is. Here the failure is silent: nothing waits at the horizon, because")
    println("  the walkers are all still queued, and queued is not stalled.")
    println()
    println("  That is the argument for the arbiter having TWO questions rather than one. Each")
    println("  priority rule answers only one of them and is complete, consistent and wrong.")
    println()
    println("  The other two rows answer both. Alternating gives each side a share that does not")
    println("  depend on how hard the other is pushing; BoundedBatch opens on a group and admits")
    println("  only that group, so a stream of arrivals cannot extend one turn indefinitely. Which")
    println("  of them is right is a modelling question -- a signal and a warden are different")
    println("  things -- and neither is the library's to choose, which is why the arbiter is a")
    println("  substitutable object and not a policy baked into the crossing.")

    check(outcomes.size == 4) { "expected four disciplines, got ${outcomes.size}" }
    check(ped.cartTrips < veh.cartTrips) {
        "pedestrian priority should starve the vehicles relative to vehicle priority"
    }
    check(veh.walkersAcross < ped.walkersAcross) {
        "vehicle priority should starve the pedestrians relative to pedestrian priority"
    }
}
