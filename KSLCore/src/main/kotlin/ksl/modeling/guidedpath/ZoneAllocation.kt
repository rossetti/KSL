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

/**
 * Told when a hold on guide-path space begins and when it ends.
 *
 * Required rather than optional, and both members abstract rather than defaulted, because **both
 * facts are the space's to know and neither can be worked out by the holder.** A hold begins when
 * the region has drained, which depends on traffic and so varies between replications; a hold taken
 * for a stated duration ends at that same unknown instant plus the duration. A holder that is not
 * told has to keep a second copy of the duration and add it to a grant time it was also not told,
 * which is two owners of one fact -- the defect family this subsystem has already produced four
 * times.
 *
 * It is an *action* rather than a listener, and the distinction is the contract: whatever the
 * closure was holding up is expected to proceed from [holdEnded]. Nothing enforces that, but a model
 * that closes an aisle and never acts on its reopening is almost certainly missing the rest of
 * itself.
 *
 * Requiring one on a plain request closes a worse hole than the one it was introduced for. Without
 * a grant notification a holder never learns it has the space, so it can never give the space back,
 * and the zones stay closed to traffic for the rest of the run with nothing holding them -- silent,
 * and unrecoverable.
 *
 * ## The contract
 *
 * - [holdBegan] fires **exactly once** for every request that is granted, after the statistics are
 *   settled -- an action may give the space straight back, which is legitimate and would otherwise
 *   be recorded against a hold that had not yet been counted as having started.
 * - [holdEnded] fires **exactly once** for every hold that began, whichever ended it: the clock, on
 *   a hold taken for a stated duration, or the holder giving it back.
 * - A request abandoned *before* it was granted produces **neither**, because abandonment is always
 *   the caller's own act -- there is no path by which the space gives up a request on its own. That
 *   is why there are two members here and not three.
 * - [holdEnded] is called **after** the zones have been given back and the handovers scheduled, so
 *   that an action sees a settled state rather than a zone that is neither held nor handed on.
 *
 * Asking for the same space again from [holdEnded] takes it back at once, ahead of any vehicle that
 * has been waiting for it. That is the drain-priority rule doing its job rather than a flaw -- a
 * reservation has to beat a waiting vehicle or a closure on a busy aisle would never happen -- but
 * it means a closure re-taken every time it ends holds its zone for the rest of the run. The
 * statistics show it plainly; nothing raises.
 *
 * The usual implementer is the model element that drives the closures, so that the thing which
 * schedules them is the thing which acts on them:
 *
 * ```
 * class SpillDriver(parent: ModelElement) : ModelElement(parent, "SpillDriver"), ZoneHoldActionIfc {
 *     override fun holdBegan(allocation: ZoneAllocation) { }
 *     override fun holdEnded(allocation: ZoneAllocation) { dispatchNextSpill() }
 * }
 * ```
 */
interface ZoneHoldActionIfc {

    /**
     * Called once, when the space has drained and the hold has taken effect.
     *
     * @param allocation the hold, which names the holder, the zones and the instant it began
     */
    fun holdBegan(allocation: ZoneAllocation)

    /**
     * Called once, when the space has been given back -- by the holder, or by the clock.
     *
     * @param allocation the hold that has just ended, whose [ZoneAllocation.timeHeld] is now final
     */
    fun holdEnded(allocation: ZoneAllocation)
}

/**
 * A reservation over one or more zones, which the zones consult to decide who may still pass.
 *
 * The zone asks rather than decides, and the reason is the hazard a set closure has and a single
 * zone does not. Closing a *set* can trap a vehicle inside it: the vehicle's route needs a zone the
 * closure has reserved, and the zone the vehicle is standing in is one the closure is waiting to
 * drain. Neither can move, and nothing in the vehicle's own contention rules resolves it: the
 * vehicle is queued on a zone that is free and that stays free until the closure lets it go.
 *
 * The fix is the one a real closure uses: **stop letting traffic in, and let the traffic already
 * inside get out.** A closing zone admits the holder it was promised to, which is how the grant is
 * taken up, and it admits a vehicle that is already holding some other zone of the same closure,
 * which is how that vehicle leaves.
 *
 * **That is not enough on its own, and the design record was wrong to say it was.** It argued that
 * the drain terminates because "the zones beyond the region are not reserved". True of one pending
 * closure; false of two. Two closures reserving abutting regions each trap a vehicle in the other's
 * way, neither region drains, and neither is granted -- a permanent stall. The zones the vehicles
 * await are *free*, so there is no holder to follow, which is why the detector follows the
 * reservation instead and can name the closure that is in the way.
 *
 * So a closure admits one more thing: **a vehicle escaping an older reservation.** [sequence]
 * orders the closures strictly, and a closure lets through any vehicle standing in a region
 * reserved before its own. That terminates, by induction on the order: the oldest pending closure
 * is permeable only to its own occupants leaving, so it drains and is granted; then the next
 * oldest, and so on. And it cannot itself loop, because being let through only ever runs from an
 * older reservation to a younger one.
 *
 * A vehicle whose route *ends* inside a region is the one case that still hangs, and it is a
 * modelling error rather than a mechanism defect: it is the same trap as sending a vehicle to a
 * junction another vehicle is parked on. The end-of-replication report names the zone and its
 * holder, and a cycle that runs through a reservation -- which the escape rule does not remove,
 * because a vehicle blocked by another *vehicle* is not escaping anything -- is reported by the
 * deadlock detector.
 */
internal interface ZoneClosureIfc {

    /** Who the reserved space is for. */
    val holder: ZoneHolderIfc

    /** Every zone this closure has reserved. */
    val zones: List<Zone>

    /**
     * When this closure was asked for, relative to every other, as a strict total order.
     *
     * A sequence number rather than the request time, because two closures asked for in the same
     * instant must still be ordered: it is the ordering that breaks the trap described above, and
     * in the reproduction both requests were made at the same instant.
     */
    val sequence: Long

    /** True when this claimant may take a zone the closure has reserved. */
    /**
     * True when every zone of this closure is empty **and** promised to it rather than to somebody
     * ahead of it in that zone's queue. Both halves are needed once closures can queue.
     */
    val isDrained: Boolean

    fun admits(claimant: ZoneHolderIfc): Boolean
}

/**
 * A request for guide-path space: what was asked for, before it has been given.
 *
 * The middle term of a trio that mirrors the resource layer exactly, which is the analogy this
 * whole construct is built on. A [Zone] is the persistent thing with its own occupancy, as a
 * `Resource` is; a `ZoneRequest` is what somebody asked for and is waiting on, as an
 * `Entity.Request` is; and a [ZoneAllocation] is the transient record of the grant, as an
 * `Allocation` is.
 *
 * A request exists because a grant is not instant. Between asking and holding, the zone **drains**:
 * whatever vehicle is crossing it finishes crossing, whatever is present in it leaves, and only
 * then can the zone be given. Nothing is evicted. So there is a state here that the resource layer
 * has no equivalent of -- *asked for, promised, not yet held* -- and this is the object that has it.
 *
 * Requests are made by asking the [GuidedPathSpace], never by construction: the space has to stay
 * in charge of its own exclusivity, which is the invariant the whole subsystem rests on.
 *
 * **A set is taken all at once or not at all.** Taking the zones one by one as they drain is what
 * creates the trap described on [ZoneClosureIfc]; waiting until every zone has drained and then
 * claiming them together prevents that trap rather than leaving it to be detected. It does not
 * make the holder incapable of lying on a circular wait: [sequence] and the escape rule on
 * [ZoneClosureIfc] exist because two closures over abutting regions can wait on each other through
 * their reservations alone, whatever either one holds. The cost of the rule is that a closure over
 * a busy region begins later, and that delay is reported rather than hidden: the space keeps it as
 * a response of its own.
 *
 * @param holder who asked
 * @param zones what was asked for, one or more
 * @param requestedAt when it was asked for
 * @param sequence where this request falls in the strict order of every request the guide path has
 *   taken, which is what the escape rule on [ZoneClosureIfc] is ordered by
 * @param holdFor how long to hold the space once the hold begins, or NaN to hold it until the
 *   holder gives it back. Measured from the instant the hold *begins*, never from the request, or a
 *   two-minute drain would silently eat two minutes out of a twenty-minute closure.
 * @param action what to tell when the hold begins and when it ends
 */
class ZoneRequest internal constructor(
    override val holder: ZoneHolderIfc,
    override val zones: List<Zone>,
    val requestedAt: Double,
    val holdFor: Double,
    override val sequence: Long,
    internal val action: ZoneHoldActionIfc
) : ZoneClosureIfc {

    /**
     * Three things may take a zone this closure has reserved, and [ZoneClosureIfc] says why each
     * is needed: the holder it is promised to, which is how the grant is taken up; a vehicle
     * already inside this region, which is how that vehicle leaves; and a vehicle escaping a region
     * reserved *before* this one, which is what stops two closures trapping each other.
     */
    override fun admits(claimant: ZoneHolderIfc): Boolean {
        if (claimant === holder) return true
        if (zones.any { it.holder === claimant }) return true
        // Only a vehicle is ever *inside* a region and needing to get out of it. A holder does not
        // travel: it asks for space, waits, takes it and gives it back, so it is never somewhere it
        // has to be let out of. That is why this asks what the claimant is rather than adding a
        // member to ZoneHolderIfc that only one kind of holder could answer.
        if (claimant !is GuidedTransporter) return false
        return claimant.heldZones.any { held ->
            val reserving = held.closure
            reserving != null && reserving.sequence < sequence
        }
    }

    /** The single zone asked for, when exactly one was. */
    val zone: Zone
        get() = zones.single()

    /** True when the space is to be given back on a clock rather than by hand. */
    val isTimed: Boolean
        get() = holdFor.isFinite()

    /**
     * True when every zone asked for has drained and could now be taken together.
     *
     * Two conditions, not one, and the second only matters where closures queue: a zone this
     * request is *behind* somebody on has not been promised to it, however empty it happens to be.
     * Granting on emptiness alone would hand a queued closure space that the closure ahead of it is
     * still draining, which is the one thing the queue exists to order.
     */
    override val isDrained: Boolean
        get() = zones.all { it.isDrained && it.closure === this }

    /** The grant, once the zones have drained and been taken, or null while any is still draining. */
    var allocation: ZoneAllocation? = null
        internal set

    /** True once the zone has been taken. */
    val isGranted: Boolean
        get() = allocation != null

    /** True while the zone is still draining, or once the request was given up unsatisfied. */
    var isAbandoned: Boolean = false
        internal set

    /** True while the request is neither granted nor given up: asked for and still draining. */
    val isWaiting: Boolean
        get() = !isGranted && !isAbandoned

    override fun toString(): String = buildString {
        append("ZoneRequest(${holder.name} -> ${zones.joinToString { it.name }}, ")
        append("asked at $requestedAt")
        append(
            when {
                isGranted -> ", granted at ${allocation!!.engagedAt}"
                isAbandoned -> ", abandoned"
                else -> ", draining"
            }
        )
        append(")")
    }
}

/**
 * The record of guide-path space actually held: which holder, which zones, from when until when.
 *
 * Transient, as an `Allocation` is, and **created by the space rather than by the holder**. That is
 * not a stylistic preference: exclusivity can only be guaranteed if nothing outside the space can
 * mint a claim on it, which is why the constructor is internal and why a holder asks rather than
 * takes.
 *
 * What it is for is the statistics. A zone cannot usefully keep them -- a network has thousands of
 * zones and almost none will ever be held by anything but a vehicle -- so the space keeps four
 * aggregates and this object carries whatever a particular model wants to know about a particular
 * closure. [timeHeld] and [ZoneHoldActionIfc] together are how a modeller collects per-closure
 * numbers without the library guessing which closures are worth separating.
 *
 * @param request what was asked for, which is where the holder, the zones and the action come
 *   from -- an allocation keeps no second copy of any of them
 * @param engagedAt when the hold began
 */
class ZoneAllocation internal constructor(
    val request: ZoneRequest,
    val engagedAt: Double
) {

    /** Who holds the space. */
    val holder: ZoneHolderIfc
        get() = request.holder

    /** What is held, one or more zones, taken together and given back together. */
    val zones: List<Zone>
        get() = request.zones

    /** When the space was asked for, which is not when the hold began unless nothing had to drain. */
    val requestedAt: Double
        get() = request.requestedAt

    internal val action: ZoneHoldActionIfc
        get() = request.action

    /** The single zone held, when exactly one is. */
    val zone: Zone
        get() = zones.single()

    /**
     * How long the space took to drain: the hold's beginning, less the instant it was asked for.
     *
     * Zero when nothing had to drain. The cost of draining rather than evicting, measured -- a
     * closure that is wanted *now* and begins late because an aisle was busy is a real effect on
     * whatever the holder represents, and it is invisible unless it is counted.
     */
    val timeToEngage: Double
        get() = engagedAt - requestedAt

    /** When the hold ended, or NaN while it is still held. */
    var releasedAt: Double = Double.NaN
        internal set

    /** True once the zone has been given back. */
    val isReleased: Boolean
        get() = !releasedAt.isNaN()

    /**
     * How long the zone was held, or has been held so far.
     *
     * @param now the current simulated time, for a hold still in progress
     */
    fun timeHeld(now: Double): Double =
        if (isReleased) releasedAt - engagedAt else now - engagedAt

    override fun toString(): String =
        "ZoneAllocation(${holder.name} holds ${zones.joinToString { it.name }} from $engagedAt" +
                (if (isReleased) " until $releasedAt" else ", still held") + ")"
}
