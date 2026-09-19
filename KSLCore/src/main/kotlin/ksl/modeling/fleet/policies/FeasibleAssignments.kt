package ksl.modeling.fleet.policies

import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.AssignmentProposal
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.spatial.FleetSpaceIfc

/**
 * The vehicle-to-task matchings available at this instant, as something a policy can **enumerate and
 * search** rather than a predicate it applies after guessing.
 *
 * The distinction is not cosmetic. A rule like "send the nearest vehicle" only ever needs to score
 * the candidates it thought of; a policy that scores *every* available action and takes the best
 * needs the action set itself to be an object it can walk. That is the shape a cost-function or
 * value-function policy has, and it is the shape a decision epoch has in the sequential
 * decision-making sense — state in, feasible actions enumerated, one chosen. Providing it here is
 * what keeps a dispatcher's wake adoptable as a decision epoch later without the interface changing.
 *
 * **Feasibility is reachability, and nothing more.** A pairing is feasible when the vehicle can get
 * to the task's pickup along the guide path. Everything else a modeller might mean by "feasible" —
 * enough charge, the right attachment, a shift that has begun — belongs to a bidding rule or a
 * scoring function, because those are judgements about *desirability* that vary by model, while
 * reachability is a fact about the space that does not.
 *
 * Cheap to construct and lazily evaluated: [candidates] is a sequence, so a policy that wants the
 * first acceptable pairing does not pay for the rest. Distances are computed on demand rather than
 * precomputed, since a policy scoring by something other than distance should not pay for a matrix
 * it never reads.
 */
class FeasibleAssignments internal constructor(
    private val tasks: List<Dispatcher.Task>,
    private val vehicles: List<FleetVehicle>,
    private val space: FleetSpaceIfc
) {

    /** How many vehicle-to-task pairings are available. Counts feasibility, so it is not simply
     *  the product of the two list sizes. */
    val size: Int
        get() = tasks.sumOf { task -> vehicles.count { isFeasible(it, task) } }

    /** True when no pairing is available at all -- every vehicle unable to reach every task, or
     *  either side empty. A policy should check this before searching rather than after. */
    val isEmpty: Boolean
        get() = candidates().none()

    /** The tasks under consideration, in the order the selection rule chose. */
    val outstanding: List<Dispatcher.Task>
        get() = tasks

    /** The vehicles under consideration, in the order they declared availability. */
    val available: List<FleetVehicle>
        get() = vehicles

    /** Every feasible pairing, tasks in selection-rule order and vehicles in declaration order
     *  within each task, so a policy that breaks ties by taking the first gets a reproducible
     *  answer. */
    fun candidates(): Sequence<AssignmentProposal> =
        tasks.asSequence().flatMap { task -> candidatesFor(task) }

    /** Every vehicle that could take this task. */
    fun candidatesFor(task: Dispatcher.Task): Sequence<AssignmentProposal> =
        vehicles.asSequence()
            .filter { isFeasible(it, task) }
            .map { AssignmentProposal(it, task, terms = cost(it, task)) }

    /** Every task this vehicle could take. */
    fun candidatesFor(vehicle: FleetVehicle): Sequence<AssignmentProposal> =
        tasks.asSequence()
            .filter { isFeasible(vehicle, it) }
            .map { AssignmentProposal(vehicle, it, terms = cost(vehicle, it)) }

    /**
     * True when the vehicle can reach the task's pickup **and** has room for the load.
     *
     * Room belongs here, with reachability, and not in a bidding rule. This class's rule is that
     * feasibility is a *fact* and everything that is a *judgement* about desirability belongs
     * elsewhere; a full vehicle cannot take the task in exactly the sense that an unreachable
     * pickup cannot be reached. A capacity-one fleet is unaffected: every idle vehicle has room.
     */
    fun isFeasible(vehicle: FleetVehicle, task: Dispatcher.Task): Boolean =
        vehicle.spareCapacity > 0 && cost(vehicle, task).isFinite()

    /**
     * Distance from where the vehicle stands to the task's pickup, **along the guide path**, or
     * [Double.POSITIVE_INFINITY] when it cannot get there.
     *
     * Infinity rather than null, so that a policy comparing costs naturally never prefers an
     * unreachable pairing; and [isFeasible] is defined in terms of it, so the two cannot disagree
     * about what is available.
     */
    fun cost(vehicle: FleetVehicle, task: Dispatcher.Task): Double {
        val here = space.location(vehicle.currentLocationName) ?: return Double.POSITIVE_INFINITY
        val there = space.location(task.pickupLocation) ?: return Double.POSITIVE_INFINITY
        if (!space.isReachable(here, there)) return Double.POSITIVE_INFINITY
        return space.distance(here, there)
    }

    /**
     * The best candidate by a score of the policy's choosing, lower being better, or null when
     * nothing is feasible.
     *
     * Ties are broken by vehicle name and then task name, so that a scoring policy is reproducible
     * without every implementer having to think about it. A score that ranks two candidates equally
     * is saying it does not care between them, and the subsystem should not answer differently on
     * two runs of the same model because it was asked in a different order.
     */
    fun best(score: (AssignmentProposal) -> Double): AssignmentProposal? =
        candidates().minWithOrNull(
            compareBy({ score(it) }, { it.vehicle.name }, { it.task.name })
        )

    override fun toString(): String =
        "FeasibleAssignments(${tasks.size} tasks, ${vehicles.size} vehicles, $size pairings)"
}
