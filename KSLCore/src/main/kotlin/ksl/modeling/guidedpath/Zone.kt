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
package ksl.modeling.guidedpath

import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc

/**
 * Whether a zone is free, spoken for, or covered.
 *
 * Three states rather than a pair of booleans, because the invariants this subsystem must hold are
 * statements about states and are far easier to assert when the state is a single value. The
 * distinction between being claimed and being covered is the one that matters: a claimed zone is
 * already unavailable to everyone else, but nobody's body is in it yet, so it counts against
 * availability and not against coverage.
 *
 * **Covered, not occupied**, and the word is chosen rather than inherited. Plain English calls a
 * zone with three people standing in it occupied, so "occupied" is the wrong word for space a
 * vehicle's body fills -- and it becomes actively misleading once a zone can hold a population that
 * takes no exclusive claim at all. "Covers" is the word this subsystem's own prose has always used
 * for a body spanning zones; the identifiers now agree with it.
 */
enum class ZoneState {

    /** Nothing holds the zone. */
    FREE,

    /**
     * A holder has reserved the zone and is travelling into it, but does not yet cover it.
     * Reserving before entering is what prevents two transporters from both starting into the same
     * free zone and arriving together.
     */
    CLAIMED,

    /** A holder's body covers the zone. */
    COVERED
}

/**
 * The atom of contended space on a guide path: the unit that is claimed, covered, and released.
 *
 * A zone is the single most important concept in the subsystem. Every claim, release, block, and
 * wake-up is expressed in zones; a transporter's position is the contiguous run of zones it
 * covers; and every congestion statistic is a statistic about zones. Links and intersections are
 * both made of zones, which is what lets a route be a flat sequence rather than an alternating
 * structure the movement engine would have to special-case.
 *
 * Zones are created by the network and are geometrically immutable. What holds them is
 * per-replication state, mutated only by the movement engine, which is why every mutator is
 * internal to this package: exclusivity can only be guaranteed if nothing outside can claim,
 * cover, or release a zone.
 *
 * The type is sealed so that the engine handles every kind of zone exhaustively, and so that adding
 * a third kind later has to be a deliberate, reviewed change rather than a silent fall-through.
 */
sealed class Zone {

    /** Index of this zone within the network's zone list. Unique and stable. */
    abstract val id: Int

    /** Unique within the network, and the name used in messages, traces, and statistics. */
    abstract val name: String

    /**
     * The distance a transporter travels to cross this zone, in the modeler's units. Strictly
     * positive for a link zone. Zero is permitted for an intersection zone, which represents a
     * junction treated as a point: crossing it takes no time, but it is still held exclusively.
     */
    abstract val length: Double

    /** Multiplies transporter velocity while crossing this zone. Strictly positive. */
    abstract val velocityFactor: Double

    /** Whether the zone is free, spoken for, or covered. Reset at the start of every replication. */
    var state: ZoneState = ZoneState.FREE
        internal set

    /**
     * Whatever holds the zone, or null when it is free.
     *
     * Typed to [ZoneHolderIfc] rather than to [GuidedTransporter] because exclusive occupation of
     * space is not a vehicle's privilege: a closed aisle or a pedestrian crossing denies a zone to
     * traffic in exactly the same way, and the engine has no reason to care which it is. Only the
     * parts that genuinely reason about vehicles -- a transporter's own run of covered zones, and
     * the wait-for graph's outgoing edges -- ask what kind of holder this is.
     */
    var holder: ZoneHolderIfc? = null
        internal set

    /** True when nothing holds the zone. */
    val isAvailable: Boolean
        get() = state == ZoneState.FREE

    /** True when something has claimed or is covering the zone. */
    val hasHolder: Boolean
        get() = state != ZoneState.FREE

    /** True when a holder covers the zone, as opposed to merely having reserved it. */
    val isCovered: Boolean
        get() = state == ZoneState.COVERED

    /**
     * How many things are present in this zone without taking exclusive possession of it.
     *
     * A plain `Int`, not a response and not a list. Not a response because a network has thousands
     * of zones and almost none of them will ever see a population, so a statistic per zone would
     * cost every model a great deal to report nothing; the statistics belong to whatever construct
     * put the occupants there, of which there are a handful. Not a list because the zone does not
     * need to know *who* is present -- whatever admitted them knows that -- and it does need the
     * count to be free to read, since an admission policy asks "how crowded is that zone" and a
     * claim asks "is anything in the way" on the hot path.
     *
     * Zero for every zone in every model that does not use the population, which is all of them
     * today: nothing outside this package can put anything in a zone.
     */
    var numPresent: Int = 0
        internal set

    /** True when something is present without holding the zone. */
    val hasOccupants: Boolean
        get() = numPresent > 0

    /**
     * The closure that has reserved this zone, or null when it is open to whoever gets there first.
     *
     * Internal because what a closure permits is the space's business. [closingFor] is the readable
     * half of it.
     */
    internal var closure: ZoneClosureIfc? = null

    /**
     * Closures waiting for the ones ahead of them, or null when nothing has ever queued here.
     *
     * Two fields rather than one list, and the split is a cost decision rather than a stylistic
     * one. [claim] and [admit] run once per zone traversal -- 4.4 million times in the reference
     * benchmark -- and read the head as a bare field. Allocating a list for every zone in every
     * model would put that cost on every model, and almost no model queues closures at all: a
     * network has thousands of zones and the overlaps that need a queue happen on a handful of
     * them, in the handful of models that ask for queueing in the first place.
     *
     * So this stays null until something actually overlaps, and the three readers never consult it:
     * the **head is the promise in force** and the rest are waiting for their turn.
     */
    private var myQueuedClosures: MutableList<ZoneClosureIfc>? = null

    /**
     * Whom this zone is closing for, or null when it is open to whoever gets there first.
     *
     * A zone is *closing* between the moment something asks for it and the moment it can be given:
     * no new claim and no new admission succeeds, while whatever is already there finishes and
     * leaves. Draining rather than evicting is the whole of the discipline -- "the aisle is closed
     * now, vehicles must leave" is evacuation, which needs somewhere for them to go and a policy
     * for choosing it, and is a different problem.
     *
     * Reserving *for a named holder* rather than setting a flag is what makes the drain terminate.
     * A closed zone that merely refused everyone would be given to whichever waiting vehicle asked
     * next, and on a busy aisle the request would never be satisfied at all.
     *
     * Null in every model that does not close a zone, which is why the hot path pays a single null
     * test for it.
     */
    val closingFor: ZoneHolderIfc?
        get() = closure?.holder

    /** True when nothing holds this zone and nothing is present in it, whoever it is closing for. */
    internal val isDrained: Boolean
        get() = state == ZoneState.FREE && numPresent == 0

    /**
     * Why this zone would refuse a claim from [claimant], or null when [claimant] could take it.
     *
     * The question a modeller has to be able to ask, and the reason it is answered here rather than
     * left to be worked out: getting it right needs three facts in the right order *and* the
     * claimant's own identity, and a hand-written version will get the last part wrong. A zone
     * reserved for a closure refuses a stranger and admits both the holder it was promised to and a
     * vehicle escaping an older reservation -- so "is this zone reserved?" is not the same question
     * as "would it refuse me?", and only the second one predicts what happens.
     *
     * [claim] is this same function, which is what keeps the two from drifting. A check that
     * disagreed with the claim it predicts would be worse than no check at all.
     */
    fun refusalFor(claimant: ZoneHolderIfc): ZoneRefusal? {
        if (state != ZoneState.FREE) return ZoneRefusal.HELD
        if (numPresent > 0) return ZoneRefusal.OCCUPIED
        val closing = closure
        if (closing != null && !closing.admits(claimant)) return ZoneRefusal.RESERVED
        return null
    }

    /**
     * Reserves the zone for a closure, so that it drains rather than being handed to the next
     * vehicle along.
     */
    internal fun closeFor(closing: ZoneClosureIfc, queued: Boolean = false) {
        if (closure == null) {
            closure = closing
            return
        }
        check(queued) {
            "Zone ($name) is already closing for (${closingFor?.name}), so " +
                    "(${closing.holder.name}) cannot reserve it as well. One at a time."
        }
        val waiting = myQueuedClosures ?: mutableListOf<ZoneClosureIfc>().also {
            myQueuedClosures = it
        }
        check(waiting.none { it === closing }) {
            "Zone ($name) already has (${closing.holder.name}) waiting in its queue."
        }
        waiting.add(closing)
    }

    /**
     * Gives up a reservation without ever having taken the zone.
     *
     * The asymmetry here is the easiest thing in the queue to get wrong. Giving up **from the
     * middle** must be silent: nothing about the promise in force has changed, and offering the
     * zone would hand it to a vehicle over the head of the closure that is still draining it.
     * Giving up **the head** promotes whoever was next and the caller must then offer the zone,
     * because the newly promoted closure may already have everything it asked for.
     *
     * @return the closure promoted into the head, or null when the head did not change
     */
    internal fun abandonReservation(closing: ZoneClosureIfc): ZoneClosureIfc? {
        if (closure !== closing) {
            val waiting = myQueuedClosures
            check(waiting != null && waiting.removeAll { it === closing }) {
                "Zone ($name) is not closing for (${closing.holder.name}) and has it nowhere in " +
                        "its queue: it is closing for (${closingFor?.name ?: "no one"})."
            }
            return null
        }
        closure = myQueuedClosures?.removeFirstOrNull()
        return closure
    }

    /** Everything promised this zone after the one in force, oldest first. Empty in almost every model. */
    internal val queuedClosures: List<ZoneClosureIfc>
        get() = myQueuedClosures ?: emptyList()

    /**
     * Offers a zone that has stopped closing to whoever was waiting for it.
     *
     * The third way a zone becomes available, and it needs the same funnel as the other two. A
     * closure given up before it took effect leaves a zone that is free, empty and wanted -- and
     * every vehicle refused while it was closing is still waiting with nothing scheduled, so
     * reopening without offering it would strand them exactly as a lost release would.
     *
     * @return whom to hand the zone to, or null when nobody wanted it
     */
    internal fun reopen(rule: ZoneContentionRuleIfc? = null): ZoneHolderIfc? = becameAvailable(rule)

    /**
     * Reserves the zone for a holder about to take it.
     *
     * Reserving before entering is what stops two transporters both starting into the same free
     * zone and arriving together. The claim fails, without side effect, when someone else already
     * holds the zone, when **anything at all is present in it** -- which is what stops a vehicle
     * driving into a crossing somebody is walking over -- or when the zone is **closing for
     * somebody else**.
     *
     * @return true when the zone was available and is now claimed
     */
    internal fun claim(claimant: ZoneHolderIfc): Boolean {
        check(holder !== claimant) {
            "Zone ($name) is already held by (${claimant.name}), which cannot claim it a second time."
        }
        // The three refusals, in order, live on [refusalFor] so that the check a modeller makes
        // before claiming and the claim itself cannot disagree. A closure decides for itself who
        // may still take the zone: the holder it is promised to may, which is how a granted
        // reservation is taken up, and so may a vehicle already inside the region being closed,
        // which is what lets it get out. The enum entries are singletons and the call inlines, so
        // the hot path still pays one state test, one integer test and one null test.
        if (refusalFor(claimant) != null) return false
        val closing = closure
        state = ZoneState.CLAIMED
        holder = claimant
        // A closure ends when the holder it was promised to takes the zone, and not when somebody
        // it let out passes through: the promise must survive traffic leaving the region. Whoever
        // was queued behind it becomes the promise in force -- and cannot be granted yet, because
        // the zone it is waiting for has just been taken.
        if (closing != null && claimant === closing.holder) {
            closure = myQueuedClosures?.removeFirstOrNull()
        }
        return true
    }

    /**
     * Admits one occupant, which succeeds only when nothing holds the zone and it is not closing.
     *
     * The mirror of [claim]'s second condition, and between them they are the whole exclusion
     * mechanism: a vehicle cannot claim a zone with occupants, and an occupant cannot enter a zone
     * with a holder. Nothing else is needed to keep vehicles and crowds out of each other's way.
     *
     * **The count never refuses entry.** How many is too many is a modelling statement, not a
     * property of space, so it belongs to whatever admission policy governs the population; the
     * zone reports the number and takes no view on it.
     *
     * @return true when the zone was open and the occupant is now present
     */
    internal fun admit(under: ZonePopulationHostIfc? = null): Boolean {
        // A population host admits onto space it is already holding, which is the only way to say
        // "closed to vehicles, open to people". It has drained the vehicles off by holding the
        // zone; letting its own people on is the point of having done so.
        if (under != null && holder === under) {
            numPresent++
            return true
        }
        if (state != ZoneState.FREE) return false
        if (closure != null) return false
        numPresent++
        return true
    }

    /** Records that the claimant now covers the zone rather than merely having reserved it. */
    internal fun cover(claimant: ZoneHolderIfc) {
        check(state == ZoneState.CLAIMED && holder === claimant) {
            "Zone ($name) cannot be covered by (${claimant.name}): it is $state held by " +
                    "${holder?.name ?: "no one"}. A zone must be claimed before it is entered."
        }
        state = ZoneState.COVERED
    }

    private val myWaiters = mutableListOf<GuidedTransporter>()

    /**
     * The transporters waiting for this zone, in the order they began waiting.
     *
     * Ordered, and deliberately so. Which of several waiting transporters gets a zone changes the
     * whole course of a run, so the choice has to be made by a stated rule over a stated order. A
     * set would leave it to whatever order the collection happened to iterate in, which can differ
     * between platforms and would cost the model its reproducibility.
     */
    val waiters: List<GuidedTransporter>
        get() = myWaiters

    /** How many transporters are waiting for this zone. */
    val numWaiting: Int
        get() = myWaiters.size

    /**
     * Records that a transporter is waiting for this zone.
     *
     * Waiting is not something a zone infers from a failed claim: placing transporters at the start
     * of a replication also claims zones, and a clash there is a specification error rather than a
     * queue to join. The engine says which it is.
     */
    internal fun addWaiter(transporter: GuidedTransporter) {
        check(transporter !in myWaiters) {
            "Transporter (${transporter.name}) is already waiting for zone ($name)."
        }
        check(holder !== transporter) {
            "Transporter (${transporter.name}) holds zone ($name) and cannot wait for it."
        }
        myWaiters.add(transporter)
    }

    /** Stops a transporter waiting for this zone, whether or not it was. */
    internal fun removeWaiter(transporter: GuidedTransporter) {
        myWaiters.remove(transporter)
    }

    /**
     * Gives up a zone the transporter occupies, and hands it to at most one waiter.
     *
     * The waiter is chosen by the supplied rule rather than by anything the zone decides for
     * itself, and it is handed the zone by being *woken*, not by being given the claim: the zone
     * genuinely becomes free in between. That matters because it is the only arrangement in which
     * the state is sound at every moment the clock could be observed -- a direct hand-off would
     * leave a window where the zone belonged to two transporters at once, and the invariants could
     * then only be checked at some moments rather than all of them.
     *
     * @param rule chooses among the waiting transporters
     * @return the transporter to wake, or null when none was waiting
     */
    internal fun release(
        claimant: ZoneHolderIfc,
        rule: ZoneContentionRuleIfc? = null
    ): ZoneHolderIfc? {
        check(holder === claimant) {
            "Zone ($name) cannot be released by (${claimant.name}): it is held by " +
                    "${holder?.name ?: "no one"}."
        }
        state = ZoneState.FREE
        holder = null
        return becameAvailable(rule)
    }

    /**
     * Releases one occupant, and hands the zone on if that was the last of them.
     *
     * The mirror of [release], and it has to be: a vehicle refused because somebody was present is
     * waiting for the zone to *empty*, and nothing else will ever tell it that happened. No holder
     * ever held that zone, so no release can fire for it. Without this the vehicle waits out the
     * replication.
     *
     * @param rule chooses among the waiting transporters
     * @return whom to hand the zone to, or null when it is not yet available and nobody wanted it
     */
    internal fun depart(rule: ZoneContentionRuleIfc? = null): ZoneHolderIfc? {
        check(numPresent > 0) {
            "Zone ($name) has no occupant to release."
        }
        numPresent--
        return becameAvailable(rule)
    }

    /**
     * Hands a zone that has just become claimable to at most one waiter, and answers which.
     *
     * **The single place a zone stops being unavailable**, and that is the point of it rather than
     * tidiness. There are two ways it can happen -- a holder releasing, and the last occupant
     * leaving -- and neither a waiting transporter nor a waiting request has anything scheduled, so
     * a path that freed the zone without offering it would leave everyone waiting on it stuck for
     * the rest of the replication, with the run simply ceasing to advance and nothing to say why.
     * Routing both through one function is what makes "free the zone without offering it" not
     * separately expressible, which is a better guarantee than any amount of checking afterwards.
     *
     * Who it can be offered to is why this answers a [ZoneHolderIfc] and not a transporter: a zone
     * that has been promised goes to whoever it was promised to, and the caller tells the two apart
     * because waking a vehicle and granting a request are different things to schedule.
     *
     * The chosen waiter is handed the zone by being *woken*, not by being given the claim: the zone
     * genuinely becomes available in between. That matters because it is the only arrangement in
     * which the state is sound at every moment the clock could be observed -- a direct hand-off
     * would leave a window where the zone belonged to two transporters at once.
     */
    private fun becameAvailable(rule: ZoneContentionRuleIfc?): ZoneHolderIfc? {
        // Still not claimable: an occupant remains, or a holder does. Nobody is offered the zone,
        // and nobody needs to be, because whatever is still in the way will come through here when
        // it leaves.
        if (state != ZoneState.FREE || numPresent > 0) return null
        val closing = closure
        if (closing != null) {
            // A zone that is closing has been promised, and the promise comes first: handing the
            // zone to a waiting vehicle instead is how a request to close a busy aisle would never
            // be satisfied at all.
            //
            // **But only when the promise can actually be taken up.** A closure still waiting on
            // its other zones cannot use this one yet, and handing it over regardless leaves every
            // vehicle waiting here with nothing scheduled -- including, in the worst case, the very
            // vehicle whose departure the closure is waiting for, which is then stranded holding a
            // zone of the region that will now never drain.
            // Asked of the closure rather than of its zones one by one, because with queueing
            // "drained" is not only about emptiness: a closure that is behind another on any zone
            // of its set has not been promised that zone yet and cannot take any of them.
            if (closing.isDrained) return closing.holder
            // So the zone goes to a waiting vehicle instead -- but only one the closure admits,
            // which is a vehicle getting *out* of this region or out of an older one. An outsider
            // is still kept out, so the drain is not weakened: every vehicle admitted here was
            // going to be admitted anyway when it next tried.
            return chooseAdmittedWaiter(rule, closing)
        }
        // The common path, and it is a hot one -- every release and every departure comes through
        // here, once per zone traversal. No filtering and no allocation: the whole list goes to the
        // rule as it always did.
        if (myWaiters.isEmpty() || rule == null) return null
        val chosen = rule.selectWaiter(this, myWaiters)
        check(chosen in myWaiters) {
            "Zone contention rule ($rule) chose transporter (${chosen.name}), which is not waiting " +
                    "for zone ($name). A rule must choose from the transporters it is given."
        }
        myWaiters.remove(chosen)
        return chosen
    }

    /**
     * Picks a waiting transporter that a reservation on this zone would let through.
     *
     * Only reached for a zone that is closing and whose closure cannot yet be granted, so the
     * filtered copy costs nothing in a model without closures and nothing on the hot path of one
     * that has them.
     *
     * @param rule chooses among those still eligible
     * @param closing the reservation whose admission rule narrows the queue
     * @return whom to hand the zone to, or null when nobody eligible was waiting
     */
    private fun chooseAdmittedWaiter(
        rule: ZoneContentionRuleIfc?,
        closing: ZoneClosureIfc
    ): GuidedTransporter? {
        if (rule == null || myWaiters.isEmpty()) return null
        val candidates = myWaiters.filter { closing.admits(it) }
        if (candidates.isEmpty()) return null
        val chosen = rule.selectWaiter(this, candidates)
        check(chosen in candidates) {
            "Zone contention rule ($rule) chose transporter (${chosen.name}), which is not waiting " +
                    "for zone ($name) or is not admitted to it. A rule must choose from the " +
                    "transporters it is given."
        }
        myWaiters.remove(chosen)
        return chosen
    }

    /**
     * Gives up a zone that was claimed but never entered, which happens when a movement is
     * superseded before the transporter reaches the zone it was heading into. Without this the
     * abandoned claim would hold the zone against everyone for the rest of the replication.
     */
    internal fun abandonClaim(claimant: ZoneHolderIfc) {
        check(state == ZoneState.CLAIMED && holder === claimant) {
            "Zone ($name) has no claim by (${claimant.name}) to abandon: it is $state held by " +
                    "${holder?.name ?: "no one"}."
        }
        state = ZoneState.FREE
        holder = null
    }

    /** Returns the zone to its start-of-replication condition. */
    internal fun resetZone() {
        state = ZoneState.FREE
        holder = null
        numPresent = 0
        closure = null
        // Dropped rather than emptied: a model that queued closures in one replication need not do
        // so in the next, and leaving the list allocated would keep the cost of a feature the run
        // has stopped using.
        myQueuedClosures = null
        myWaiters.clear()
    }

    /**
     * The time for a transporter travelling at the given velocity to cross this zone. Exactly zero
     * for a dimensionless intersection, which schedules a zero-delay event rather than an
     * instantaneous state change, so that the executive keeps the ordering explicit.
     *
     * @param velocity the transporter's velocity, strictly positive
     */
    fun traversalTime(velocity: Double): Double {
        require(velocity > 0.0) {
            "Zone ($name): a transporter's velocity must be > 0.0 to cross a zone, but was $velocity."
        }
        return length / (velocity * velocityFactor)
    }

    /**
     * Whether traffic with business elsewhere has to pass through this zone.
     *
     * A fact about the layout and not about who happens to be standing here, so it is answerable at
     * any instant -- including the instant a transporter stops, when nobody has yet had time to
     * queue up behind it.
     *
     * A spur is the exception, and it is the exception the guide path already recognises: a dead end
     * is entered and left the same way, so nothing passes *through* it, which is what makes a spur
     * the standard device for keeping a stopped transporter out of everyone's way. Its zones are
     * refuges, and so is the junction it ends at, because that junction leads nowhere else.
     */
    abstract val isOnAThroughRoute: Boolean

    final override fun toString(): String = name
}

/**
 * One of the equal zones that divide a link.
 *
 * Its length and velocity factor come from the owning link, so that the geometry of a link is
 * stated once and cannot drift between its zones.
 *
 * @param id index within the network's zone list
 * @param link the owning link
 * @param positionOnLink the one-based position from the link's beginning intersection
 */
class LinkZone internal constructor(
    override val id: Int,
    val link: Link,
    val positionOnLink: Int
) : Zone() {

    override val name: String = "${link.name}.Zone$positionOnLink"

    /** True unless the owning link is a spur, which nothing passes through. */
    override val isOnAThroughRoute: Boolean
        get() = link.type != LinkType.SPUR

    override val length: Double
        get() = link.zoneLength

    override val velocityFactor: Double
        get() = link.velocityFactor

    /** True when this is the zone a forward traversal of the link enters first. */
    val isFirstOnLink: Boolean
        get() = positionOnLink == 1

    /** True when this is the last zone before a forward traversal reaches the ending intersection. */
    val isLastOnLink: Boolean
        get() = positionOnLink == link.numZones
}

/**
 * The space of a junction, held by one transporter at a time.
 *
 * An intersection is space as well as a place. Treating it as a zone is what makes spur semantics
 * expressible at all: a transporter sent to the end of a dead end has to keep hold of the mouth in
 * order to get back out, and "the mouth" is this zone.
 *
 * Its length defaults to zero, the usual modelling assumption that a junction is a point. A zero
 * length does not make the zone free to share; it remains exclusive.
 *
 * @param id index within the network's zone list
 * @param intersection the junction whose space this is
 * @param length the distance to cross the junction, zero or more
 * @param velocityFactor multiplies transporter velocity while crossing, strictly positive
 */
class IntersectionZone internal constructor(
    override val id: Int,
    val intersection: GuidedPathNetwork.Intersection,
    override val length: Double,
    override val velocityFactor: Double
) : Zone() {

    override val name: String = intersection.name

    /**
     * True unless this is a dead end.
     *
     * A junction with one incident link leads nowhere but back the way a transporter came, so the
     * only traffic that reaches it is traffic that wanted it. Two or more and it is a junction in
     * the ordinary sense: somebody's route runs through it.
     */
    override val isOnAThroughRoute: Boolean
        get() = intersection.incidentLinks.size > 1

    /** True when the junction is treated as a point, so that crossing it takes no time. */
    val isDimensionless: Boolean
        get() = length == 0.0
}
