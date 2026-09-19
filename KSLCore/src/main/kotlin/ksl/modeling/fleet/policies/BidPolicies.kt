package ksl.modeling.fleet.policies

import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.spatial.FleetSpaceIfc

/**
 * A dispatcher's invitation to bid on a task.
 *
 * Carries the instant it was issued as well as the task, because a bid may legitimately depend on
 * how long a vehicle has to respond and because a run's record should show when the call went out,
 * not only what came back.
 */
data class CallForProposals(val task: Dispatcher.Task, val issuedAt: Double)

/**
 * A vehicle's offer.
 *
 * **Lower is better.** That convention has to be stated somewhere and this is the place: a bid is a
 * cost the vehicle is quoting, not a fitness it is claiming, so distance, completion time and
 * remaining charge all point the same way without any policy having to negate anything.
 *
 * The [note] is for the modeller, not for the mechanism -- nothing reads it. It exists so that a
 * study comparing bidding rules can record *why* a vehicle offered what it did, which is otherwise
 * lost the moment the auction closes.
 */
data class Bid(val vehicle: FleetVehicle, val value: Double, val note: String? = null)

/**
 * What a vehicle offers when a dispatcher calls for proposals.
 *
 * Carried by the vehicle rather than computed by the dispatcher on the vehicle's behalf, and that is
 * the substantive difference between an auction and a centrally-solved assignment. A dispatcher that
 * evaluates every vehicle itself is solving an optimisation over a fleet it models; a dispatcher
 * that asks is deferring to what each vehicle knows about itself -- its charge, its faults, its own
 * idea of what it is willing to take on. The second is expressible here and is not expressible in
 * the passive paradigm at all, because a passive resource has nothing with which to hold an opinion.
 */
fun interface BidPolicyIfc {

    /**
     * @return the vehicle's offer, lower being better, or **null to decline**. Declining is normal
     *   operation and not an error: a vehicle out of range, out of charge, or already committed has
     *   nothing useful to say, and a dispatcher that received a bid from it anyway would have to
     *   invent a number meaning "no", which is how sentinel values get compared as though they were
     *   costs.
     */
    fun bid(vehicle: FleetVehicle, cfp: CallForProposals, space: FleetSpaceIfc): Bid?
}

/**
 * Bid the distance to the pickup, and decline when it is unreachable.
 *
 * Distance along the guide path, never straight-line separation: on a one-way loop a vehicle
 * standing a few feet past a pickup point must go all the way round, and bidding its proximity would
 * win it the job it is furthest from.
 */
class NetworkDistanceBid : BidPolicyIfc {

    override fun bid(vehicle: FleetVehicle, cfp: CallForProposals, space: FleetSpaceIfc): Bid? {
        val there = space.location(cfp.task.pickupLocation) ?: return null
        if (!vehicle.movement.isReachable(there)) return null
        return Bid(vehicle, vehicle.movement.pathDistanceTo(there), "distance to pickup")
    }

    override fun toString(): String = "NetworkDistanceBid"
}

/**
 * Bid the time to finish the whole job: travel to the pickup, then carry the load to its
 * destination, at this vehicle's own velocity.
 *
 * The difference from [NetworkDistanceBid] is the point of having two. Nearest-to-pickup is a proxy
 * for "soonest done" that stops being one as soon as vehicles differ in speed or the loaded legs
 * differ in length -- a fast vehicle slightly further away finishes first, and a rule that cannot
 * say so will not send it. This is the rule that can, and it needs nothing from the dispatcher to do
 * it, because the vehicle knows its own velocity.
 */
class CompletionTimeBid : BidPolicyIfc {

    override fun bid(vehicle: FleetVehicle, cfp: CallForProposals, space: FleetSpaceIfc): Bid? {
        val pickup = space.location(cfp.task.pickupLocation) ?: return null
        val destination = space.location(cfp.task.destination) ?: return null
        if (!vehicle.movement.isReachable(pickup)) return null
        if (!space.isReachable(pickup, destination)) return null
        val velocity = vehicle.nominalVelocity
        if (velocity <= 0.0) return null
        val distance = vehicle.movement.pathDistanceTo(pickup) + space.distance(pickup, destination)
        return Bid(vehicle, distance / velocity, "empty + loaded legs at $velocity")
    }

    override fun toString(): String = "CompletionTimeBid"
}

/**
 * Bid distance, but decline outright when already carrying out a task.
 *
 * Included because declining has to be demonstrably ordinary. A rule that always returns a number
 * makes "no" indistinguishable from "very expensive", and the first time a modeller wants a vehicle
 * to genuinely refuse -- a fault, a flat battery, a shift ending -- they will reach for a large
 * constant and discover that a large constant still wins an auction nobody else entered.
 */
class DeclineWhenBusyBid(private val inner: BidPolicyIfc = NetworkDistanceBid()) : BidPolicyIfc {

    override fun bid(vehicle: FleetVehicle, cfp: CallForProposals, space: FleetSpaceIfc): Bid? {
        if (vehicle.hasAssignment) return null
        return inner.bid(vehicle, cfp, space)
    }

    override fun toString(): String = "DeclineWhenBusyBid($inner)"
}
