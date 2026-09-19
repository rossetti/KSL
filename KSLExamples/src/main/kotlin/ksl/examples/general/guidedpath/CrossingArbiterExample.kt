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

import ksl.controls.KSLStringControl
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

/*
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
 *  Both are run here, and neither raises. In the table below they are obvious -- `cart trips 0`
 *  and `walkers 0` are not numbers anybody would accept -- but that is an artefact of the crossing
 *  being the whole model. Put the same crossing in a shop with fifteen other responses and those
 *  become two rows among many, both of them plausible in isolation: a crossing that vehicles
 *  rarely use, a crossing that pedestrians rarely use. Nothing raises, nothing warns, and the
 *  throughput the study cares about is still a number. That is the failure mode, and it is why
 *  the two questions have to be asked together rather than one at a time.
 *
 *  The two failures do not fail alike, which is worth knowing before trusting either. Under
 *  pedestrian priority the guide path reports a transporter still waiting when the replication
 *  ends, and names both what it waits for and who holds it: a model that stops moving says so.
 *  Under vehicle priority nothing waits at the horizon at all, because the walkers are still
 *  queued -- and queued is not stalled. The first failure announces itself; the second is silent.
 *
 *  ## What the other two disciplines add
 *
 *  [AlternatingArbiter] gives each side a share that does not depend on how hard the other is
 *  pushing. [BoundedBatchArbiter] opens on a group and admits only that group, so a stream of
 *  arrivals cannot extend one turn indefinitely. Both answer *both* questions, which is the
 *  property the two priority rules lack. Which of them a model wants is a modelling question -- a
 *  signal and a warden are different things -- and not the library's to choose, which is why the
 *  arbiter is a substitutable object rather than a policy baked into the crossing.
 *
 *  ## Deterministic
 *
 *  No randomness at all: walkers arrive every `walkerEvery` minutes and carts every `cartEvery`,
 *  and a zone is a minute. So every figure below can be checked by hand, and the example is a
 *  specification of behaviour rather than an estimate of it.
 */
/**
 *  A one-way loop with a crossing part way along its outbound leg.
 *
 *  The cart circulates so that traffic is continuous; walkers cross and go.
 */
class CrossingArbiterExample(
    parent: ModelElement,
    arbiterName: String = "PedestrianPriority",
    name: String? = null
) : ProcessModel(parent, name) {

    /** Zones are twelve feet and everything moves twelve feet a minute, so a zone is a minute. */
    private val zone = 12.0

    private val walkerEvery = 1.5
    private val cartEvery = 4.0
    private val walkTime = 2.0

    // A one-way loop rather than a single aisle, so the cart can keep circulating: a
    // one-way link cannot be run backwards, and sending a cart home along one raises.
    private val network: GuidedPathNetwork = GuidedPathNetwork.builder("Town")
        .link("Aisle", "A", "B", length = 6 * zone, zoneLength = zone)
        .link("Return", "B", "A", length = 6 * zone, zoneLength = zone)
        .build()

    init {
        spatialModel = network
    }

    val system = GuidedPathTransportSystem(this, network, name = "Sys")

    private val cart = GuidedTransporter(
        system, TransporterPlacement.At("A"), ConstantRV(zone), 1, name = "Cart"
    )

    val crossing = ZoneCrossing(
        this, system, listOf(network.zone("Aisle.Zone3")!!), arbiterFor(arbiterName), name = "Walkway"
    )

    /** The four disciplines, made fresh on each call so none inherits another's turn state. */
    private fun arbiterFor(discipline: String): CrossingArbiterIfc = when (discipline) {
        "PedestrianPriority" -> PedestrianPriorityArbiter()
        "VehiclePriority" -> VehiclePriorityArbiter()
        "Alternating" -> AlternatingArbiter(walkTime = 6.0, driveTime = 6.0)
        "BoundedBatch" -> BoundedBatchArbiter(batchSize = 2, maxWait = 5.0)
        else -> throw IllegalArgumentException(
            "unknown crossing discipline '$discipline'; expected one of $crossingDisciplines"
        )
    }

    /**
     *  The discipline the crossing runs under, by name, so that a scenario or an app can change it
     *  without holding an arbiter object. The constructor takes the same name, so building the
     *  model and changing it afterwards say the same thing.
     *
     *  **This is the example's own control, and not the library's.**
     *  [ksl.modeling.guidedpath.ZoneCrossing] carries an `arbiterName` too, but it offers only the
     *  two priority disciplines -- the two that a name defines completely. The other two here are
     *  parameterised, and the parameters are not incidental: `Alternating` at six minutes each way
     *  and `BoundedBatch` at two with a five-minute cap are *this study's design points*, chosen so
     *  the four rows are comparable. Naming them is the study's business, which is why the names
     *  and the numbers behind them live here rather than in the package.
     *
     *  That is the general shape of it. A study over which family of rule to use is a study over a
     *  name, and the library offers it; a study over a rule's parameter is a study over a number,
     *  and belongs in the model that chooses it.
     */
    @set:KSLStringControl(
        allowedValues = ["PedestrianPriority", "VehiclePriority", "Alternating", "BoundedBatch"],
        comment = "Which admission discipline the crossing runs under"
    )
    var arbiterName: String = arbiterName
        set(value) {
            crossing.arbiter = arbiterFor(value)
            field = value
        }

    private val walkQ = HoldQueue(this, "WalkQ")

    var cartTrips: Int = 0
        private set
    var walkersAcross: Int = 0
        private set

    private inner class Walker : Entity("Walker") {
        val walk = process(isDefaultProcess = true) {
            crossOnFoot(crossing, walkTime, walkQ)
            walkersAcross++
        }
    }

    // Named classes rather than lambdas because each one schedules itself, and a lambda that
    // refers to the property it is being assigned to cannot have its type inferred.
    private inner class WalkerAction : EventActionIfc<Nothing> {
        override fun action(event: KSLEvent<Nothing>) {
            activate(Walker().walk)
            schedule(this, walkerEvery)
        }
    }

    private inner class CartAction : EventActionIfc<Nothing> {
        override fun action(event: KSLEvent<Nothing>) {
            // Sent back and forth so there is always traffic wanting the crossing. Counting
            // arrivals rather than dispatches is what makes the number mean "got through".
            if (!cart.isMoving) {
                cart.sendTo(if (cart.currentLocation?.name == "B") "A" else "B")
            }
            schedule(this, cartEvery)
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
        schedule(myWalkerAction, walkerEvery)
        schedule(myCartAction, 0.5)
    }
}


/**
 *  The four disciplines, named once.
 *
 *  The `@KSLStringControl` below writes them out a second time and has to: an annotation's
 *  `allowedValues` must be a compile-time literal, so it cannot read this list. That duplicate is
 *  forced; a third copy in `main` was not.
 */
private val crossingDisciplines =
    listOf("PedestrianPriority", "VehiclePriority", "Alternating", "BoundedBatch")

fun main() {
    val horizon = 120.0
    val disciplines = crossingDisciplines

    /** What one discipline did over the horizon. */
    class Outcome(
        val name: String,
        val cartTrips: Int,
        val walkersAcross: Int,
        val fracBarred: Double,
        val meanWaitToCross: Double,
        val turns: Double
    )

    println()
    println("One crossing, four disciplines, over ${horizon.toInt()} minutes")
    println("A walker every 1.5 minutes, a cart dispatched every 4.0, and a zone is a minute.")
    println()
    println("  %-20s %11s %11s %11s %11s %8s".format(
        "discipline", "cart trips", "walkers", "frac barred", "mean wait", "turns"
    ))

    val outcomes = disciplines.map { discipline ->
        val m = Model("Crossing_$discipline")
        val town = CrossingArbiterExample(m, discipline, name = "Town")
        // On, where the benchmarks force it off. It walks every zone on every change, which a
        // throughput measurement cannot afford and a two-hour run over twelve zones can. This
        // example asserts who holds the crossing at which instant, so the harness that checks the
        // space's bookkeeping against itself earns its cost here.
        town.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = horizon
        m.simulate()
        // Read after the run rather than in `replicationEnded`, which is correct **because there
        // is one replication** and would quietly stop being correct with more. A Response resets
        // its within-replication statistic in `beforeReplication`, so with ten replications these
        // three lines would report replication ten rather than an average of the ten, and nothing
        // would say so. A study wanting more than one replication should observe these in
        // `replicationEnded` -- one write per replication, which is what carries an interval.
        Outcome(
            name = discipline,
            cartTrips = town.cartTrips,
            walkersAcross = town.walkersAcross,
            fracBarred = town.crossing.fracTimeBarred.withinReplicationStatistic.weightedAverage,
            meanWaitToCross = town.crossing.waitToCross.withinReplicationStatistic.weightedAverage,
            turns = town.crossing.turnsTaken.value
        )
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

    check(outcomes.size == 4) { "expected four disciplines, got ${outcomes.size}" }
    check(ped.cartTrips < veh.cartTrips) {
        "pedestrian priority should starve the vehicles relative to vehicle priority"
    }
    check(veh.walkersAcross < ped.walkersAcross) {
        "vehicle priority should starve the pedestrians relative to pedestrian priority"
    }
}
