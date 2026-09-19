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
package ksl.modeling.fleet.policies

import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.TourStop
import ksl.modeling.fleet.exceptions.FleetTourException
import ksl.modeling.spatial.FleetSpaceIfc

/**
 * What a tour policy is given to decide with.
 *
 * The vehicle, so a policy can ask where it is and how far away things are through the movement
 * seam rather than through any one substrate; and the layout, for distances between two *places*,
 * which are facts about the layout and not about any vehicle.
 */
class TourContext internal constructor(
    val vehicle: FleetVehicle,
    val space: FleetSpaceIfc
) {

    /** How far the vehicle is from [location] now, along the path it would take. */
    fun distanceFromVehicle(location: String): Double =
        vehicle.movement.pathDistanceTo(space.requireLocation(location))

    /** How far apart two places are, along the path between them. */
    fun distanceBetween(from: String, to: String): Double =
        space.distance(space.requireLocation(from), space.requireLocation(to))

    /** The travel a stop sequence costs, starting from where the vehicle is now. */
    fun travelCost(stops: List<TourStop>): Double {
        if (stops.isEmpty()) return 0.0
        var total = distanceFromVehicle(stops.first().location)
        for (i in 1 until stops.size) {
            total += distanceBetween(stops[i - 1].location, stops[i].location)
        }
        return total
    }
}

/**
 * The order a vehicle visits the stops it has committed to.
 *
 * **This is the decision multi-load adds, and it is the dispatcher's.** With one task there is no
 * decision: collect, then deliver. With several there is an order, and the order is most of what
 * makes a multi-load fleet good or bad — so it is a seam rather than a rule, because otherwise
 * every study of a multi-load fleet would be a study of whichever heuristic was written here.
 *
 * **A vehicle executes tours; it does not author them** (`A7`). A vehicle may *compute* a candidate
 * order — that is how a bid prices the marginal cost of taking a task, and because a policy is a
 * pure function the bid can call the very policy the dispatcher will use, so the quote and the plan
 * cannot disagree. What a vehicle may never do is commit to one.
 *
 * ### The contract
 *
 * - The returned list must contain **exactly** the stops it was given — `remaining` plus `insert`,
 *   by identity, no more and no fewer. A violation raises [FleetTourException] naming the policy and
 *   what was added or dropped.
 * - **Precedence**: a task's pickup must come before its set-down.
 * - **Capacity**: walking the stops and counting one on at each pickup and one off at each
 *   set-down, the running count must never exceed the vehicle's load capacity. This is what makes
 *   the problem non-trivial: an insertion can be the cheapest and still infeasible.
 * - Pure and deterministic. No state across calls, no randomness without an explicit stream, and no
 *   mutation of anything it is handed.
 *
 * The framework validates all three rather than trusting them, and refuses rather than quietly
 * repairing: a framework that reorders a policy's answer has taken the decision away from it.
 */
fun interface TourPolicyIfc {

    /**
     * @param context the vehicle and the layout
     * @param remaining the stops the vehicle has still to make, in their current order
     * @param insert the stops a newly committed task requires, pickup first
     * @return the new order of all of them
     */
    fun plan(context: TourContext, remaining: List<TourStop>, insert: List<TourStop>): List<TourStop>
}

/**
 * Put each new stop wherever it adds least travel, subject to precedence and capacity.
 *
 * The default, and the standard heuristic for this problem. Every feasible pair of positions for
 * the pickup and its set-down is tried and the cheapest kept; ties go to the earlier position, so
 * the answer does not depend on the order the candidates happened to be generated in.
 *
 * Cost is not a consideration: with a load capacity of *c* there are at most `2c` stops, so the
 * search is `O(c^2)` distance lookups against a precomputed matrix. At any capacity a real fleet
 * has, that is nothing.
 *
 * **It degenerates to the obvious thing.** Inserting into an empty tour gives pickup then set-down,
 * which is exactly what a single-load transport has always been — so a fleet of capacity one plans
 * the same tour it would have been given before this seam existed.
 */
class CheapestInsertionTourPolicy : TourPolicyIfc {

    override fun plan(
        context: TourContext,
        remaining: List<TourStop>,
        insert: List<TourStop>
    ): List<TourStop> {
        if (insert.isEmpty()) return remaining
        if (insert.size != 2) return remaining + insert     // not a pickup/set-down pair
        val capacity = context.vehicle.loadCapacity
        var best: List<TourStop>? = null
        var bestCost = Double.POSITIVE_INFINITY
        for (p in 0..remaining.size) {
            for (d in p..remaining.size) {
                val candidate = remaining.toMutableList()
                candidate.add(d, insert[1])
                candidate.add(p, insert[0])
                if (!isFeasible(candidate, capacity, context.vehicle.numLoadsAboard)) continue
                val cost = context.travelCost(candidate)
                if (cost < bestCost - 1e-12) {
                    bestCost = cost
                    best = candidate
                }
            }
        }
        return best ?: (remaining + insert)
    }

    override fun toString(): String = "CheapestInsertionTourPolicy"
}

/**
 * Append each new task's stops to the end, changing nothing already planned.
 *
 * The naive baseline, and it is shipped because a gain has to be measured against something. A
 * fleet running this consolidates only by accident — it collects and delivers one load before
 * starting the next — so the difference between it and [CheapestInsertionTourPolicy] on one layout
 * is what tour planning is worth in that study.
 */
class AppendTourPolicy : TourPolicyIfc {
    override fun plan(
        context: TourContext,
        remaining: List<TourStop>,
        insert: List<TourStop>
    ): List<TourStop> = remaining + insert

    override fun toString(): String = "AppendTourPolicy"
}

/**
 * Collect everything first, then deliver everything.
 *
 * A milk run in the literal sense, and the shape a real collection round often has: a vehicle goes
 * out light, fills up, and comes back. It is easier to explain than cheapest insertion and usually
 * worse, which is why it is here as the comparison rather than as the default.
 *
 * Pickups keep the order they were committed in, and so do set-downs.
 */
class PickUpAllThenDeliverAllPolicy : TourPolicyIfc {
    override fun plan(
        context: TourContext,
        remaining: List<TourStop>,
        insert: List<TourStop>
    ): List<TourStop> {
        val all = remaining + insert
        val pickups = all.filter { it.action.loadChange > 0 }
        val rest = all.filter { it.action.loadChange <= 0 }
        return pickups + rest
    }

    override fun toString(): String = "PickUpAllThenDeliverAllPolicy"
}

/** True when the sequence never has a task's set-down before its pickup and never overfills. */
internal fun isFeasible(stops: List<TourStop>, capacity: Int, aboardAlready: Int): Boolean {
    var aboard = aboardAlready
    val collected = mutableSetOf<Int>()
    for (s in stops) {
        val a = s.action
        val task = a.task
        if (a.loadChange > 0) {
            aboard += a.loadChange
            if (aboard > capacity) return false
            if (task != null) collected.add(System.identityHashCode(task))
        } else if (a.loadChange < 0) {
            // A set-down whose pickup is not in this sequence belongs to a load already aboard,
            // which is legitimate: the vehicle collected it before this tour was replanned.
            if (task != null &&
                stops.any { it.action.task === task && it.action.loadChange > 0 } &&
                !collected.contains(System.identityHashCode(task))
            ) {
                return false
            }
            aboard += a.loadChange
        }
    }
    return true
}

/** Refuses a stop order that breaks precedence or capacity, naming the policy and what went wrong. */
internal fun validateTour(
    policy: TourPolicyIfc,
    given: List<TourStop>,
    returned: List<TourStop>,
    capacity: Int,
    aboardAlready: Int,
    vehicleName: String
) {
    if (returned.size != given.size || !given.all { g -> returned.any { it === g } }) {
        throw FleetTourException(
            "Tour policy ($policy) planning for vehicle ($vehicleName) returned ${returned.size} " +
                    "stops from the ${given.size} it was given. A policy decides the *order* of the " +
                    "stops it is handed; it may not add one, drop one, or invent one."
        )
    }
    var aboard = aboardAlready
    val collected = mutableSetOf<Int>()
    for ((i, s) in returned.withIndex()) {
        val a = s.action
        val task = a.task
        if (a.loadChange > 0) {
            aboard += a.loadChange
            if (aboard > capacity) {
                throw FleetTourException(
                    "Tour policy ($policy) planning for vehicle ($vehicleName) put $aboard " +
                            "loads aboard at stop $i (${s.location}), which holds $capacity. An " +
                            "insertion can be the cheapest available and still infeasible, so a " +
                            "policy has to check capacity as it searches rather than afterwards."
                )
            }
            if (task != null) collected.add(System.identityHashCode(task))
        } else if (a.loadChange < 0) {
            if (task != null) {
                val hasPickupHere = returned.any {
                    it.action.task === task && it.action.loadChange > 0
                }
                if (hasPickupHere && !collected.contains(System.identityHashCode(task))) {
                    throw FleetTourException(
                        "Tour policy ($policy) planning for vehicle ($vehicleName) put the set-down " +
                                "of task (${task.name}) at stop $i, before its pickup. A vehicle " +
                                "cannot put down what it has not collected."
                    )
                }
            }
            aboard += a.loadChange
        }
    }
}
