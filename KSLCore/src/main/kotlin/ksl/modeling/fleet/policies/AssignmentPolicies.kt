package ksl.modeling.fleet.policies

import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.AssignmentProposal
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.fleet.TaskBoard
import ksl.modeling.agent.contractNet
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.spatial.FleetSpaceIfc
import ksl.utilities.random.rng.RNStreamIfc

/**
 * The subsystem's principal extension point: who gets sent where.
 *
 * `suspend` is the whole design in one keyword. A rule that answers immediately is a dispatching
 * rule of the ordinary kind; a policy that first waits ten minutes is batching; a policy that calls
 * for proposals and waits for a deadline is an auction. All three are "decide assignments", and
 * they differ only in whether deciding takes simulated time. An interface that could not suspend
 * would admit only the first, which is exactly the limitation this subsystem exists to remove.
 *
 * A policy **decides only** (`A7`). It cannot move a vehicle, claim a zone, post a task, or mutate
 * the board -- the board it is given is read-only and [AssignmentProposal] is inert, so this is
 * enforced by the types rather than by a rule. It may read anything.
 *
 * A policy must also be a function of what it is given, drawing any randomness from a
 * model-controlled stream (`A8`). Consulting wall-clock time or unmanaged global state would make
 * a run unreproducible in a way no test would catch.
 *
 * ## Why `assign` is written as an extension on the process builder
 *
 * `KSLProcessBuilder` is annotated `@RestrictsSuspension`, which means a process body may only
 * invoke suspending functions that are members or extensions **of the builder itself**. A plain
 * `suspend fun assign(context)` on this interface would therefore not compile at the one call site
 * that matters -- the dispatcher's process -- however sound it looked in isolation.
 *
 * Declaring it as a member extension satisfies the restriction and buys something as well: an
 * implementation receives the real process builder as its receiver, so it may `delay` for a
 * batching window, `hold`, or call `contractNet` to run an auction, rather than being confined to
 * whatever a context object thought to expose. The dispatcher calls it as
 * `with(policy) { assign(context) }`.
 */
interface AssignmentPolicyIfc {
    suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal>
}

/**
 * What a policy is given, and the only things it may do.
 *
 * Grows in later phases -- an auction in the negotiated phase, a feasible-action set in the
 * re-tasking phase -- but [AssignmentPolicyIfc.assign]'s signature never changes, so a policy
 * written today keeps compiling.
 */
class DispatchContext internal constructor(
    val board: TaskBoard,
    val available: List<FleetVehicle>,
    val space: FleetSpaceIfc,
    val time: Double,
    internal val dispatcher: ksl.modeling.fleet.Dispatcher
) {

    /**
     * The vehicle-to-task pairings available at this instant, as an object to enumerate and search.
     *
     * Built on demand rather than in the constructor: most policies never look at it, and building
     * it eagerly would make every dispatching pass pay for reachability queries that nothing reads.
     */
    val feasible: FeasibleAssignments
        get() = FeasibleAssignments(board.unassigned, available, space)

    // There is deliberately no `waitFor` here. A policy receives the process builder as its
    // receiver, so it consumes simulated time with `delay` directly -- the same verb it would use
    // anywhere else, with no wrapper to learn and nothing this object has to anticipate.

    /**
     * Runs a Contract-Net negotiation over the available vehicles and returns the winning bid.
     *
     * A real auction, not a distance rule wearing an auction's clothes. The dispatcher broadcasts
     * the call and each vehicle answers with whatever its own [BidPolicyIfc] makes of it, so two
     * fleets with the same layout and different bidding rules reach different awards. A vehicle may
     * decline, and declining is silence rather than a message -- a dispatcher receiving an "I
     * decline" would have to tell it apart from a bid.
     *
     * **The deadline consumes simulated time**, and that is the point of expressing an auction as
     * something a policy does rather than as a rule it evaluates. Negotiation is not free; a model
     * that charges for it is more faithful than one that awards instantaneously, and the cost is
     * visible in the loads' waiting time rather than hidden in an assumption.
     *
     * A deadline of zero is well defined and is *not* a trap. `contractNet` collects the proposals
     * that reached it before the deadline elapsed, and a bid is computed by a non-suspending mailbox
     * handler that answers inside the broadcast itself -- so at zero every vehicle has still bid.
     * That safety rests on [BidPolicyIfc.bid] not being a suspending function, which the type system
     * enforces rather than a convention: a bidding rule that consumed simulated time could not be
     * written, so a zero deadline cannot silently start collecting nothing.
     *
     * Ties are broken by **vehicle name**, not by position in the fleet. Elsewhere in this
     * subsystem declaration order is the tiebreaker, and here it would be wrong: an auction treats
     * its bidders as symmetric except for what they offer, so two vehicles quoting the same number
     * should get the same answer however the fleet happened to be declared. Name is stable under
     * reordering, total, and explicable to a modeller looking at a result they did not expect.
     * A supplied [selectBest] takes on that responsibility for itself.
     *
     * @param cfp what the vehicles are being asked to bid on
     * @param deadline how long to wait for proposals, in simulated time. Zero is instantaneous.
     * @return the best bid by [selectBest], or null when every vehicle declined
     */
    suspend fun KSLProcessBuilder.auction(
        cfp: CallForProposals,
        deadline: Double,
        selectBest: (List<Bid>) -> Bid? = ::lowestBidByName
    ): Bid? {
        require(deadline >= 0.0) { "An auction deadline cannot be negative." }
        // The bidder list comes from the fleet the dispatcher was given, mapped to this
        // replication's agents. Never from `AgentModel.agents`, which does not contain them: they
        // are created inside initialize() and so are runtime agents by construction.
        val bidders = available.mapNotNull { it.agent }
        if (bidders.isEmpty()) return null
        dispatcher.auctionRun()
        val outcome = contractNet<CallForProposals, Bid>(
            bidders, cfp, deadline,
            selectBest = { proposals ->
                val best = selectBest(proposals.map { it.proposal })
                // Map the winning bid back to the proposal that carried it, by identity: two
                // vehicles may legitimately bid the same value, and comparing by value would then
                // award to whichever proposal happened to be first in the list.
                proposals.firstOrNull { it.proposal === best }
            }
        )
        if (outcome == null) {
            dispatcher.auctionUnfilled()
            return null
        }
        return outcome.winningProposal.proposal
    }

    /**
     * Distance from where a vehicle is now to a location, **along the guide path**.
     *
     * Never straight-line: see the warning on [NetworkDistanceBid]. Returns
     * [Double.POSITIVE_INFINITY] when the location is unreachable from where the vehicle stands, so
     * a policy comparing distances naturally never picks a vehicle that cannot get there.
     */
    fun distanceTo(vehicle: FleetVehicle, location: String): Double {
        val there = space.location(location) ?: return Double.POSITIVE_INFINITY
        if (!vehicle.movement.isReachable(there)) return Double.POSITIVE_INFINITY
        return vehicle.movement.pathDistanceTo(there)
    }
}

/**
 * The first available vehicle takes the first unassigned task.
 *
 * Deliberately the degenerate case: vehicles pulling from a shared queue, expressed as a policy
 * rather than as an architecture. It is what the passive subsystem's pool does, which is why it is
 * the policy the equivalence benchmark uses -- the two subsystems can only be compared on a
 * question where they are supposed to give the same answer.
 *
 * It never suspends, so under this policy the dispatcher is a synchronous rule and the active model
 * should reproduce the passive one.
 */
class PullFromBoardPolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        if (context.available.isEmpty()) return emptyList()
        val proposals = mutableListOf<AssignmentProposal>()
        val free = context.available.toMutableList()
        for (task in context.board.unassigned) {
            if (free.isEmpty()) break
            proposals.add(AssignmentProposal(free.removeAt(0), task))
        }
        return proposals
    }

    override fun toString(): String = "PullFromBoardPolicy"
}

/**
 * Send the vehicle nearest the pickup, measured **along the guide path**.
 *
 * The default from this phase on, and the one a modeller expects when they think "send the closest
 * cart". Distance is never straight-line separation: on a one-way loop a vehicle a few feet past the
 * pickup point has to travel all the way round, so a Euclidean rule would send precisely the wrong
 * one -- quietly, with no symptom other than a fleet that performs worse than it should and no
 * indication of why.
 *
 * Unreachable vehicles are excluded rather than ranked last. [DispatchContext.distanceTo] reports an
 * unreachable location as an infinite distance, which would sort correctly but would also make an
 * unreachable vehicle *win* whenever it is the only candidate -- and it would then be assigned a task
 * it cannot begin.
 */
class NearestVehiclePolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> =
        greedyByDistance(context) { d -> d }

    override fun toString(): String = "NearestVehiclePolicy"
}

/**
 * Send the vehicle *furthest* from the pickup.
 *
 * Deliberately poor, and useful for exactly that: a study needs a bad rule to measure a good one
 * against, and "how much does nearest-vehicle actually buy over the worst sensible alternative" is a
 * question no amount of arguing about the good rule can answer.
 */
class FurthestVehiclePolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> =
        greedyByDistance(context) { d -> -d }

    override fun toString(): String = "FurthestVehiclePolicy"
}

/**
 * Send whichever available vehicle has completed the fewest tasks so far.
 *
 * A workload-balancing rule rather than a travel-minimising one, and the two genuinely conflict: the
 * nearest vehicle is often the one that has just finished something nearby, so nearest-vehicle tends
 * to concentrate work on whichever vehicles are already busy in the active part of the layout. Which
 * is right depends on whether the cost being managed is time or wear.
 *
 * Ties break on declaration order, which for an untouched fleet at the start of a replication means
 * every vehicle is tied and the first is chosen -- so early in a run this behaves like
 * [PullFromBoardPolicy] and only separates once the counts diverge.
 */
class LeastUsedVehiclePolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val free = context.available.toMutableList()
        val proposals = mutableListOf<AssignmentProposal>()
        for (task in context.board.unassigned) {
            if (free.isEmpty()) break
            val best = free.minByOrNull { it.numTasksCompleted.value } ?: break
            free.remove(best)
            proposals.add(AssignmentProposal(best, task))
        }
        return proposals
    }

    override fun toString(): String = "LeastUsedVehiclePolicy"
}

/**
 * Send a uniformly chosen available vehicle.
 *
 * The stream is supplied rather than created so that it is one of the model's own, which is what
 * makes a run reproducible and lets a study put this policy on common random numbers with the rules
 * it is being compared against. A policy that reached for a global generator would be
 * irreproducible in a way no single run would reveal.
 */
class RandomAssignmentPolicy(private val stream: RNStreamIfc) : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val free = context.available.toMutableList()
        val proposals = mutableListOf<AssignmentProposal>()
        for (task in context.board.unassigned) {
            if (free.isEmpty()) break
            val pick = stream.randInt(0, free.size - 1)
            proposals.add(AssignmentProposal(free.removeAt(pick), task))
        }
        return proposals
    }

    override fun toString(): String = "RandomAssignmentPolicy"
}

/**
 * Wait for a window, then assign everything that accumulated during it, together.
 *
 * **This is the policy the interface exists for.** Every rule above answers immediately and could
 * have been a function; this one consumes simulated time, and while it is waiting the board keeps
 * filling. Deciding later over more information is the trade this makes: a load that arrives just
 * after a window opens waits the whole of it, and in exchange the fleet is allocated over a set of
 * tasks rather than one at a time in arrival order. Whether that pays depends on the layout and the
 * load, which is precisely why it is a policy a modeller can measure rather than a behaviour built
 * into a dispatcher.
 *
 * It is also the demonstration that this design needed a dispatcher with a process of its own. Under
 * the passive paradigm there is nowhere to put this: the decision is made inside an entity's own
 * process at the instant it asks, so "wait and see what else arrives" would mean making that entity
 * wait for reasons that have nothing to do with it.
 *
 * The window runs from when the dispatcher wakes, so an idle fleet still pays it. A model that wants
 * batching only under load should compose this behind a rule that checks the board first.
 *
 * @param window how long to accumulate, in simulated time
 * @param inner what to do with the batch once it has accumulated
 */
class BatchedAssignmentPolicy(
    val window: Double,
    val inner: AssignmentPolicyIfc = NearestVehiclePolicy()
) : AssignmentPolicyIfc {

    init {
        require(window > 0.0) { "A batching window must be positive; a zero window is the inner policy alone." }
    }

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        // SUSPENDS. Tasks posted during the window accumulate and are on the board when this
        // returns, which is the entire point of waiting.
        delay(window, suspensionName = "batchingWindow")
        // The context was built before the wait, so its `available` list and the board's contents
        // are as they were then. The board is a live view, so it has caught up on its own; the
        // vehicle list has not, and a vehicle that has since been given work must not be proposed
        // again. Rebuilding from the dispatcher's current state is what keeps this honest.
        val fresh = DispatchContext(
            context.board, context.dispatcher.availableVehicles, context.space,
            context.dispatcher.time, context.dispatcher
        )
        return with(inner) { assign(fresh) }
    }

    override fun toString(): String = "BatchedAssignmentPolicy(window=$window, inner=$inner)"
}

/**
 * Greedy pairing of tasks to vehicles by a score derived from the distance to the pickup.
 *
 * Shared by the nearest and furthest rules because they differ only in the sign of that score, and
 * because the part worth getting right once is the part neither is about: skipping unreachable
 * vehicles, taking tasks in the order the selection rule chose, and breaking ties on declaration
 * order so that a run is reproducible.
 */
private inline fun greedyByDistance(
    context: DispatchContext,
    score: (Double) -> Double
): List<AssignmentProposal> {
    val free = context.available.toMutableList()
    val proposals = mutableListOf<AssignmentProposal>()
    for (task in context.board.unassigned) {
        if (free.isEmpty()) break
        var best: FleetVehicle? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (v in free) {
            val d = context.distanceTo(v, task.pickupLocation)
            if (d.isInfinite()) continue          // cannot get there at all
            val sc = score(d)
            if (sc < bestScore) { bestScore = sc; best = v }
        }
        val chosen = best ?: continue             // nobody available can reach this pickup
        free.remove(chosen)
        proposals.add(AssignmentProposal(chosen, task, terms = bestScore))
    }
    return proposals
}

/**
 * Fill a vehicle that has room, rather than giving it one task and moving on.
 *
 * A decorator, because consolidating is orthogonal to choosing: whichever rule picks the vehicle
 * for a task -- nearest, least used, an auction -- the question "and could that vehicle also take
 * the next one?" is the same question. Wrapping keeps the two decisions separate and lets any inner
 * rule be made capacity-aware without being rewritten.
 *
 * **It changes nothing for a fleet of capacity one**, which is the property that makes it safe to
 * make a default later: with no spare capacity there is never a second task to add.
 *
 * The extra tasks are taken in board order — that is, in whatever order the task selection rule
 * put them — and vehicles are considered by name, so the answer does not depend on the order the
 * inner policy happened to return its proposals in (`C1`).
 *
 * What it does *not* do is decide the order the vehicle visits the stops in. That is the tour
 * policy's decision and it is made later, once the vehicle knows everything it has been given.
 */
class ConsolidatingPolicy @JvmOverloads constructor(
    val inner: AssignmentPolicyIfc = NearestVehiclePolicy()
) : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val proposals = with(inner) { assign(context) }.toMutableList()
        if (proposals.isEmpty()) return proposals
        val taken = proposals.mapTo(mutableSetOf<Dispatcher.Task>()) { it.task }
        val roomLeft = mutableMapOf<FleetVehicle, Int>()
        for (p in proposals) {
            roomLeft[p.vehicle] = (roomLeft[p.vehicle] ?: p.vehicle.spareCapacity) - 1
        }
        for (vehicle in roomLeft.keys.sortedBy { it.name }) {
            var remaining = roomLeft[vehicle] ?: 0
            if (remaining <= 0) continue
            for (task in context.board.unassigned) {
                if (remaining <= 0) break
                if (task in taken) continue
                if (!context.feasible.isFeasible(vehicle, task)) continue
                proposals.add(AssignmentProposal(vehicle, task))
                taken.add(task)
                remaining--
            }
        }
        return proposals
    }

    override fun toString(): String = "ConsolidatingPolicy(inner=$inner)"
}

/**
 * Award each task by Contract-Net auction: the dispatcher calls for proposals, the vehicles bid, the
 * best bid wins.
 *
 * The difference from every rule above is where the knowledge lives. `NearestVehiclePolicy` computes
 * a number *about* each vehicle from the outside; this asks each vehicle what it makes of the job
 * and lets it answer with whatever it knows about itself -- its speed, its charge, its faults, its
 * own view of what it is willing to take on. Swap the fleet's [BidPolicyIfc] and the awards change
 * without the dispatcher being touched, which is precisely what a passive resource cannot do,
 * because it has nothing with which to hold an opinion.
 *
 * Tasks are auctioned one at a time, in the order the selection rule chose, and a vehicle that wins
 * one is withdrawn before the next call goes out. That is a greedy sequence of auctions rather than
 * a combinatorial one: it can be beaten on a set of tasks that would be better matched jointly, and
 * a policy wanting that should collect bids for all of them and solve. The greedy form is here
 * because it is the one Contract-Net actually describes.
 *
 * @param deadline how long each call for proposals stays open, in simulated time. Charged per
 *   auction, so a fleet with several tasks outstanding pays it several times over -- which is the
 *   honest cost of negotiating each job separately.
 * @param selectBest which bid wins. Lower is better by convention, so the default takes the minimum.
 */
class ContractNetAssignmentPolicy(
    val deadline: Double,
    val selectBest: (List<Bid>) -> Bid? = ::lowestBidByName
) : AssignmentPolicyIfc {

    init {
        require(deadline >= 0.0) { "An auction deadline cannot be negative." }
    }

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val proposals = mutableListOf<AssignmentProposal>()
        val spokenFor = mutableSetOf<FleetVehicle>()
        // The board is a live view, so the list is taken once here: auctioning takes simulated time
        // and tasks posted during it belong to the next pass, not to a list being iterated.
        for (task in context.board.unassigned.toList()) {
            val stillFree = context.available.filter { it !in spokenFor }
            if (stillFree.isEmpty()) break
            val round = DispatchContext(
                context.board, stillFree, context.space, context.dispatcher.time, context.dispatcher
            )
            val cfp = CallForProposals(task, context.dispatcher.time)
            // SUSPENDS for the deadline. Written at the call site so the cost of negotiating is
            // visible in the policy that incurs it.
            val winning = with(round) { auction(cfp, deadline, selectBest) } ?: continue
            spokenFor.add(winning.vehicle)
            proposals.add(AssignmentProposal(winning.vehicle, task, terms = winning.value))
        }
        return proposals
    }

    override fun toString(): String = "ContractNetAssignmentPolicy(deadline=$deadline)"
}

/**
 * The default award rule: lowest bid, ties broken by vehicle name.
 *
 * The tiebreak is the part that matters. Exact ties are not a curiosity in this subsystem -- a
 * symmetric layout with identical vehicles produces them constantly -- and a rule that left them to
 * the order proposals happened to arrive in would make the winner depend on the order the fleet was
 * declared, which is not something a negotiation should be able to see. Name is intrinsic to the
 * vehicle, stable under any reordering, and total.
 */
fun lowestBidByName(bids: List<Bid>): Bid? =
    bids.minWithOrNull(compareBy({ it.value }, { it.vehicle.name }))

/**
 * Scores every feasible pairing and takes the best.
 *
 * Here to show that the feasible set is *usable* rather than merely present. It is also the shape a
 * cost-function or value-function policy has: enumerate the available actions, score each, choose
 * one. A rule like nearest-vehicle is a special case of it -- score by distance to the pickup and
 * the two agree exactly, which `ScoringPolicyTest` asserts, because two shapes that are supposed to
 * be interchangeable should be shown to be so rather than asserted to be.
 *
 * Greedy across tasks: the best pairing is taken, both parties removed, and the next best chosen
 * from what remains. That can be beaten on a set of tasks better matched jointly; a policy wanting
 * that has the same enumerable set to solve over and should do so rather than reaching for this.
 *
 * @param score lower is better. Given the candidate and the whole feasible set, so a score may be
 *   relative -- "how much worse than the best alternative for this task" is a legitimate thing to
 *   want, and it needs the set.
 */
class ScoringAssignmentPolicy(
    val score: (AssignmentProposal, FeasibleAssignments) -> Double
) : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val taken = mutableSetOf<FleetVehicle>()
        val done = mutableSetOf<Dispatcher.Task>()
        val proposals = mutableListOf<AssignmentProposal>()
        while (true) {
            val set = FeasibleAssignments(
                context.board.unassigned.filter { it !in done },
                context.available.filter { it !in taken },
                context.space
            )
            val best = set.best { score(it, set) } ?: break
            taken.add(best.vehicle)
            done.add(best.task)
            proposals.add(best)
        }
        return proposals
    }

    override fun toString(): String = "ScoringAssignmentPolicy"
}

/**
 * Takes a task back from a vehicle when a materially better pairing has become available.
 *
 * The capability the passive paradigm cannot express. There, a transporter belongs to the entity
 * that seized it until the journey ends, so a cart three-quarters of the way to a far pickup goes on
 * to it however good the alternative -- not because the movement machinery could not turn it round,
 * but because there is no object whose business it would be to decide.
 *
 * The threshold is what makes this usable rather than pathological. Without one, any improvement at
 * all justifies a swap, and a fleet under load will churn: revoke, redirect, revoke again as the
 * board shifts under it, with vehicles spending their time changing their minds. Requiring the
 * saving to exceed a stated distance means a swap has to be worth making. Set it in the same units
 * as the layout's own distances.
 *
 * Only assignments whose load is not yet aboard are considered; once a vehicle has the load it
 * finishes the delivery, which the assignment's own guard enforces rather than this policy
 * remembering to check.
 *
 * ## A stopped vehicle is taken off its task whatever the distances say
 *
 * A vehicle that has broken down or run out of charge is no *further* from its pickup than it was --
 * having driven part of the way, it is usually nearer -- so a rule that compares distances never
 * takes work back from it, however long it stands there. That is the wrong answer, and it is wrong
 * silently: the load waits on a vehicle that is not coming while a healthy one sits idle.
 *
 * So a stopped incumbent is treated as unable to collect at all, and any vehicle that can reach the
 * pickup takes the task. [FleetVehicle.isOutOfService] is the test. The threshold does not apply,
 * because the incumbent's cost is no longer a distance to compare against.
 *
 * Being told is a separate matter from acting. Nothing inside the subsystem wakes the dispatcher
 * when a vehicle stops, so this rule fires at the next pass rather than at the breakdown unless the
 * model attaches [ReconsiderOnInterruption].
 *
 * ## The inner policy must rank pairings, not tasks
 *
 * The default is a scoring policy over the feasible set, and that is not an arbitrary choice.
 * [NearestVehiclePolicy] walks the **tasks** in selection-rule order and picks the nearest vehicle
 * for each; with one vehicle and two tasks it therefore hands the first task in the queue whatever
 * is free -- including the vehicle that was just taken off it. A re-tasking policy wrapped around a
 * rule like that revokes and immediately re-awards the same pairing, does it again on the next pass,
 * and accomplishes nothing but a rising revocation count.
 *
 * A policy that ranks *pairings* has no such problem: it takes the globally best vehicle-and-task
 * together, which after a revocation is the near task the revocation was made for. Anything supplied
 * here should have that property, and the interaction is worth knowing about because the failure is
 * silent -- the model runs, the loads are delivered, and only the revocation counter says something
 * is wrong.
 *
 * @param improvementThreshold how much nearer, in guide-path distance, a swap must be before it is
 *   worth making
 * @param inner what to do with the tasks nobody is committed to. Must rank pairings; see above.
 */
class ReassigningPolicy(
    val improvementThreshold: Double,
    val inner: AssignmentPolicyIfc = ScoringAssignmentPolicy { p, f -> f.cost(p.vehicle, p.task) }
) : AssignmentPolicyIfc {

    init {
        require(improvementThreshold > 0.0) {
            "The improvement threshold must be positive; at zero any improvement justifies a swap " +
                    "and a loaded fleet will churn instead of working."
        }
    }

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val dispatcher = context.dispatcher
        // Taking work back happens before deciding what to do with what is free, so a vehicle freed
        // by a revocation is available to this same pass rather than the next. Revoking is not
        // itself an assignment, so it is done here rather than returned as a proposal: a policy
        // decides, and this is it acting on its decision through the one public operation that
        // exists for it.
        //
        // A vehicle committed to a task has been *withdrawn*, so it is not in `available` and cannot
        // be seen there. It is reached through the task it holds, which is why both tests below are
        // written over the assigned tasks rather than over the free vehicles.
        for (task in context.board.assigned.toList()) {
            val holder = dispatcher.assignmentFor(task) ?: continue
            if (!holder.isRevocable) continue
            val incumbentCost = context.distanceTo(holder.vehicle, task.pickupLocation)
            val stopped = holder.vehicle.isOutOfService
            if (!stopped && !incumbentCost.isFinite()) continue

            // Case one: someone else could collect this load materially sooner.
            val challenger = context.available
                .filter { it !== holder.vehicle }
                .minWithOrNull(
                    compareBy({ context.distanceTo(it, task.pickupLocation) }, { it.name })
                )
            val challengerCost =
                challenger?.let { context.distanceTo(it, task.pickupLocation) } ?: Double.POSITIVE_INFINITY

            // Case three, and it is the one a distance rule cannot see. A vehicle that has stopped
            // -- broken down, out of charge, being pushed out of an aisle -- is no nearer its pickup
            // than it was, and by driving part of the way it is usually *nearer*. So the two tests
            // above never fire for it, however long it stands there, and the load waits on a vehicle
            // that is not coming. Distance is the wrong question here: what matters is that it
            // cannot collect anything at all, so anyone who can, should.
            //
            // The case this gets wrong is a vehicle stopped briefly a few feet from its pickup while
            // the only alternative is across the site. That is real, and the threshold cannot help
            // with it because the incumbent's cost is not a distance any more. A model that cares
            // should say how long it is willing to wait, which is a rule of its own and belongs in
            // a policy of its own.
            if (stopped) {
                if (challengerCost.isFinite()) dispatcher.revoke(holder)
                continue
            }

            // Case two: this vehicle could collect a *different* load materially sooner. The two are
            // not the same test and a fleet of one has only the second -- which is precisely the
            // case the passive paradigm cannot express at all, since there the cart belongs to the
            // entity that seized it until the journey ends.
            val betterTask = context.board.unassigned
                .minWithOrNull(
                    compareBy({ context.distanceTo(holder.vehicle, it.pickupLocation) }, { it.name })
                )
            val betterTaskCost = betterTask
                ?.let { context.distanceTo(holder.vehicle, it.pickupLocation) }
                ?: Double.POSITIVE_INFINITY

            val bestAlternative = minOf(challengerCost, betterTaskCost)
            if (!bestAlternative.isFinite()) continue
            if (incumbentCost - bestAlternative <= improvementThreshold) continue
            dispatcher.revoke(holder)
        }
        return with(inner) { assign(context) }
    }

    override fun toString(): String =
        "ReassigningPolicy(threshold=$improvementThreshold, inner=$inner)"
}

/**
 * Refuses any assignment a vehicle could not finish and still reach a charger.
 *
 * A decorator: it does not decide anything, it removes proposals another policy made. That keeps
 * the reserve orthogonal to the dispatching rule, which is what it has to be -- nearest-vehicle,
 * least-used and an auction all need the same guard, and none of them should have to know about
 * batteries to get it.
 *
 * **Why this ships with the battery rather than being left to the modeller.** A vehicle that runs
 * flat on a guide path does not merely stop working: it stands on the zones it holds for the rest
 * of the replication and obstructs everything routed through them. That is a permanent, silent
 * change to the layout, and a fleet can lose its throughput to it without anything raising.
 *
 * **The reserve covers time as well as distance, and that is the whole difficulty.** Reaching a
 * charger costs both traction energy and hotel load, so a reserve computed from distance alone
 * under-reserves exactly when the trip is slow -- which on a guide path means exactly when it is
 * congested. A reserve that was correct for a model with no idle draw becomes incorrect the moment
 * one is added, and it fails in the direction that strands vehicles.
 *
 * The estimate is a free-running one: distance along the guide path, at the vehicle's last sampled
 * velocity, with no allowance for blocking, re-routing, or a queue at the charger. [safetyFactor]
 * is what covers all of that, which is why its default is generous rather than tight. A study that
 * wants it tighter should check [FleetVehicle.numTimesStranded] is still zero.
 *
 * @param inner the policy that actually decides
 * @param safetyFactor multiplies the estimated draw. Must be >= 1.0.
 */
class ChargeReservePolicy @JvmOverloads constructor(
    val inner: AssignmentPolicyIfc,
    val safetyFactor: Double = 1.25
) : AssignmentPolicyIfc {

    init {
        require(safetyFactor >= 1.0) {
            "A charge reserve's safety factor must be >= 1.0, but was $safetyFactor."
        }
    }

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val proposals = with(inner) { assign(context) }
        if (proposals.isEmpty()) return proposals
        return proposals.filter { affordable(it, context) }
    }

    private fun affordable(proposal: AssignmentProposal, context: DispatchContext): Boolean {
        val vehicle = proposal.vehicle
        val battery = vehicle.battery ?: return true
        // Refused loudly rather than passing everything through. A reserve policy with nowhere to
        // reserve for is a guard that silently does nothing, which is worse than not having one:
        // the model reads as protected and is not.
        check(vehicle.system.chargers.isNotEmpty()) {
            "FleetSystem (${vehicle.system.name}) has a ChargeReservePolicy but no chargers. The " +
                    "reserve is the charge a vehicle must keep in hand to reach one, so with none " +
                    "declared there is nothing to reserve for and the policy would pass every " +
                    "assignment through while appearing to guard them. Declare a charger with " +
                    "addCharger, or drop the policy."
        }
        val space = context.space
        val task = proposal.task
        val pickup = space.location(task.pickupLocation) ?: return false
        val setDown = space.location(task.destination) ?: return false
        val charger = vehicle.system.nearestCharger(task.destination)
            ?.let { space.location(it) }
            ?: return false
        if (!vehicle.movement.isReachable(pickup) || !space.isReachable(pickup, setDown) ||
            !space.isReachable(setDown, charger)
        ) {
            return false
        }
        val distance = vehicle.movement.pathDistanceTo(pickup) +
                space.distance(pickup, setDown) +
                space.distance(setDown, charger)
        val velocity = vehicle.nominalVelocity
        if (velocity <= 0.0) return false
        val needed = battery.drawFor(distance, distance / velocity) * safetyFactor
        return needed <= vehicle.stateOfCharge
    }

    override fun toString(): String = "ChargeReservePolicy(inner=$inner, safetyFactor=$safetyFactor)"
}

// ---- naming ------------------------------------------------------------------------------------
//
// A policy is offered by name only when a name is all it takes to define it. Six of the eleven are
// absent below: [RandomAssignmentPolicy] needs a stream, [BatchedAssignmentPolicy] a window,
// [ContractNetAssignmentPolicy] a deadline, [ReassigningPolicy] a threshold, [ChargeReservePolicy]
// an inner policy, and [ScoringAssignmentPolicy] a scoring function, which no string could carry at
// all. Each is set by assigning the object.
//
// The line is drawn there on purpose. "Batched" is not one policy: a thirty-second window and a
// five-minute window are different dispatching rules that happen to share an implementation, and a
// name that stood for one of them would let a study vary the label while freezing the number. A
// study over windows is a study over a *number*, and belongs in the model that chooses it -- which
// is why `DispatchingRuleComparison` keeps its own richer set of names, with the parameters written
// into them, rather than delegating to this one.

/** The assignment policies a name can select, in the order an interface should offer them. */
val assignmentPolicyNames: List<String> =
    listOf("PullFromBoard", "NearestVehicle", "FurthestVehicle", "LeastUsedVehicle", "Consolidating")

/**
 * The policy a name stands for, made fresh -- which matters for a policy that carries state between
 * decisions, so that one never inherits the end of a run it was not part of.
 *
 * @throws IllegalArgumentException if the name is not one of [assignmentPolicyNames]
 */
fun createAssignmentPolicy(name: String): AssignmentPolicyIfc = when (name) {
    "PullFromBoard" -> PullFromBoardPolicy()
    "NearestVehicle" -> NearestVehiclePolicy()
    "FurthestVehicle" -> FurthestVehiclePolicy()
    "LeastUsedVehicle" -> LeastUsedVehiclePolicy()
    "Consolidating" -> ConsolidatingPolicy()
    else -> throw IllegalArgumentException(
        "Unknown assignment policy '$name'; expected one of $assignmentPolicyNames. A policy that " +
                "takes an argument -- a window, a deadline, a threshold, a stream or an inner " +
                "policy -- is set by assigning it."
    )
}

/** The name of a policy, or null when it is one no name stands for. */
fun nameOfAssignmentPolicy(policy: AssignmentPolicyIfc): String? = when (policy) {
    is PullFromBoardPolicy -> "PullFromBoard"
    is NearestVehiclePolicy -> "NearestVehicle"
    is FurthestVehiclePolicy -> "FurthestVehicle"
    is LeastUsedVehiclePolicy -> "LeastUsedVehicle"
    is ConsolidatingPolicy -> "Consolidating"
    else -> null
}
