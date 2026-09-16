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

package ksl.examples.general.agv

import ksl.modeling.agv.AgvSystem
import ksl.modeling.fleet.FleetTransportResult
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ReassigningPolicy
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  A cart is turned round in mid-journey for a nearer job.
 *
 *  This is the one thing the passive paradigm has no place for, and it is worth being precise about
 *  why. It is **not** that the movement machinery cannot redirect a moving transporter — the guide
 *  path has been able to do that since the passive subsystem was built, and this example goes down
 *  exactly that code path. It is that under the passive paradigm a transporter *belongs to* the
 *  entity that seized it, for the whole journey. There is no object whose business it would be to
 *  decide otherwise. The decision has nowhere to live.
 *
 *  Here it has somewhere: a dispatcher that can see the whole board and take a task back.
 *
 *  ## Reading the three cases
 *
 *  The middle case is the capability; the third is what makes it a rule rather than a reflex. A
 *  policy that always swapped would produce the middle result and the wrong third one, and on a
 *  busy floor it would churn -- revoking and re-revoking as the board shifts, with carts spending
 *  their time changing their minds. The threshold is what makes a swap have to be worth making.
 *
 *  The cart never reverses. A redirect takes effect at the next zone boundary, because something
 *  between two places cannot stop and turn round; the guide path decides when, and it is the same
 *  code the passive subsystem has always used.
 *
 *  Note the reassignment count on the load that was put back. Its accumulated wait survives the
 *  swap -- the task never left the queue -- so a load that has been waiting longest still looks
 *  like one, and the fact that it was passed over is reported rather than absorbed.
 *
 *  ## The layout, and why the arithmetic is unambiguous
 *
 *  A one-way ring of four legs of 100, with the cart parked on a spur off `N`.
 *
 *  ```
 *          Depot
 *            |
 *            N ──────► E ── NearStation
 *            ▲                  │
 *            │                  ▼
 *  FarStation ── W ◄────── S ── Shipping
 *  ```
 *
 *  At **t = 2** the cart has travelled 20 and stands at `N`. Its own pickup at `W` is 300 ahead; the
 *  new one at `E` is 100. The swap saves 200 and is worth making.
 *
 *  At **t = 15** the cart has travelled 150 and is fifty units past `E`. Its own pickup at `W` is now
 *  150 ahead; the new one at `E` is 350, all the way back round the one-way ring. The swap is
 *  genuinely worse, and the rule declines it.
 *
 *  Both numbers come from the layout rather than from a confidence interval, which is what makes the
 *  second case worth running: without it, a rule that simply always swapped would look identical.
 *
 *  ## What re-tasking costs
 *
 *  The load that is put back does not lose its place. Its task never left the queue, so the wait it
 *  has already accumulated survives the revocation — re-queueing it would reset the clock and make
 *  the load that has waited longest look as though it had just arrived, corrupting both the
 *  statistic and any age-based rule reading it. What the run reports instead is a reassignment
 *  count, so the swap is visible rather than silent.
 *
 *  ## Why this example reports no confidence intervals
 *
 *  Every other comparison in this family of examples runs many replications and reports half-widths,
 *  because the quantity being compared is a random variable. Here it is not. The cart's velocity is
 *  constant, the legs are 100 apiece, the two loads arrive at times the study chooses, and there is
 *  no arrival process at all. One replication produces the whole answer, and the claim being made is
 *  arithmetic: at t = 2 the swap saves exactly 200 and the rule takes it; at t = 15 it costs exactly
 *  200 and the rule refuses. An interval around a deterministic quantity would obscure that rather
 *  than support it.
 */
class RetaskingInFlightExample(
    parent: ModelElement,
    policy: AssignmentPolicyIfc,
    private val nearArrivesAt: Double,
    name: String? = null
) : ProcessModel(parent, name) {

    private val nearPickup = "NearStation"
    private val farPickup = "FarStation"
    private val shipping = "Shipping"
    private val depot = "Depot"

    val network: GuidedPathNetwork = buildNetwork()

    init {
        spatialModel = network
    }

    val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

    val cart = AgvVehicle(
        agv, TransporterPlacement.At(depot), ConstantRV(10.0), name = "Cart"
    ).apply { homeBase = depot }

    val delivered = linkedMapOf<String, FleetTransportResult>()

    private inner class Load(private val label: String, private val from: String) : Entity(label) {
        val production = process(isDefaultProcess = true) {
            currentLocation = network.requireLocation(from)
            delivered[label] = transportByFleet(agv, destination = shipping, origin = from)
        }
    }

    override fun initialize() {
        delivered.clear()
        activate(Load("far", farPickup).production)
        activate(Load("near", nearPickup).production, timeUntilActivation = nearArrivesAt)
    }

    /** A square ring with a parking spur, so both pickups are the same distance from the depot. */
    private fun buildNetwork(): GuidedPathNetwork = GuidedPathNetwork.builder("Ring")
        .intersection("N", x = 0.0, y = 100.0)
        .intersection("E", x = 100.0, y = 0.0)
        .intersection("S", x = 0.0, y = -100.0)
        .intersection("W", x = -100.0, y = 0.0)
        .intersection("Park", x = 0.0, y = 140.0)
        .link("NE", "N", "E", length = 100.0, zoneLength = 10.0, beginDirection = 315.0)
        .link("ES", "E", "S", length = 100.0, zoneLength = 10.0, beginDirection = 225.0)
        .link("SW", "S", "W", length = 100.0, zoneLength = 10.0, beginDirection = 135.0)
        .link("WN", "W", "N", length = 100.0, zoneLength = 10.0, beginDirection = 45.0)
        .link("ParkSpur", "N", "Park", length = 20.0, zoneLength = 20.0,
            type = LinkType.SPUR, beginDirection = 90.0)
        .station(nearPickup, "E")
        .station(farPickup, "W")
        .station(shipping, "S")
        .station(depot, "Park")
        .build()
}

/** Runs one deterministic replication of the shop under a policy and an arrival time. */
private fun runRetasking(policy: AssignmentPolicyIfc, nearArrivesAt: Double): RetaskingInFlightExample {
    val m = Model("Retasking")
    val shop = RetaskingInFlightExample(m, policy, nearArrivesAt, name = "Shop")
    m.numberOfReplications = 1
    m.lengthOfReplication = 2_000.0
    m.simulate()
    return shop
}

private fun reportRetasking(title: String, shop: RetaskingInFlightExample) {
    println("  $title")
    for ((label, r) in shop.delivered) {
        println(
            "    %-6s delivered at %7.1f   waited %6.1f   reassignments %d".format(
                label, r.totalTime, r.waitForAssignment + r.waitForArrival, r.numReassignments
            )
        )
    }
    println(
        "    revocations: %.0f".format(
            shop.agv.dispatcher.numAssignmentsRevoked.value
        )
    )
    println()
}

fun main() {
    println()
    println("Re-tasking a cart in mid-journey - what the passive paradigm has no place for")
    println()

    reportRetasking(
        "Without re-tasking: the cart commits at t=0 and finishes what it started.",
        runRetasking(NearestVehiclePolicy(), nearArrivesAt = 2.0)
    )
    reportRetasking(
        "With re-tasking, near job at t=2 (worth 200 units): the cart is turned round.",
        runRetasking(ReassigningPolicy(improvementThreshold = 20.0), nearArrivesAt = 2.0)
    )
    reportRetasking(
        "With re-tasking, near job at t=15 (would cost 200): the rule declines the swap.",
        runRetasking(ReassigningPolicy(improvementThreshold = 20.0), nearArrivesAt = 15.0)
    )
}
