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
 * - [holdEnded] is called **after** the zones have been given back and the handovers scheduled.
 *   Otherwise an action that immediately asks for the same space again would reserve it ahead of
 *   the vehicles that have been waiting for the drain, and a repeating closure could starve traffic
 *   indefinitely.
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
 * drain. Neither can move. It is not a circular wait the detector can see, either -- a holder that
 * never queues has no [ZoneHolderIfc.awaitedZone], so there is no edge to close a cycle with -- so
 * the run would simply stop advancing with nothing to say why.
 *
 * The fix is the one a real closure uses: **stop letting traffic in, and let the traffic already
 * inside get out.** A closing zone admits the holder it was promised to, which is how the grant is
 * taken up, and it admits a vehicle that is already holding some other zone of the same closure,
 * which is how that vehicle leaves. The drain then terminates for any set, because every vehicle
 * inside can always continue -- the zones beyond the region are not reserved, and once a vehicle is
 * fully out it holds nothing in the set and so cannot get back in.
 *
 * A vehicle whose route *ends* inside the region is the one case that still hangs, and it is a
 * modelling error rather than a mechanism defect: it is the same trap as sending a vehicle to a
 * junction another vehicle is parked on. The end-of-replication report names the zone and its
 * holder.
 */
internal interface ZoneClosureIfc {

    /** Who the reserved space is for. */
    val holder: ZoneHolderIfc

    /** Every zone this closure has reserved. */
    val zones: List<Zone>

    /** True when this claimant may take a zone the closure has reserved. */
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
 * claiming them together keeps the holder holding nothing while it waits, which keeps it a sink
 * in the wait-for graph and makes a deadlock impossible rather than undetectable. The cost is that
 * a closure over a busy region begins later, and that delay is reported rather than hidden: the
 * space keeps it as a response of its own.
 *
 * @param holder who asked
 * @param zones what was asked for, one or more
 * @param requestedAt when it was asked for
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
    internal val action: ZoneHoldActionIfc
) : ZoneClosureIfc {

    /**
     * The holder it is promised to may take a reserved zone, and so may a vehicle that is already
     * inside the region -- see [ZoneClosureIfc] for why the second is what makes a drain terminate.
     */
    override fun admits(claimant: ZoneHolderIfc): Boolean =
        claimant === holder || zones.any { it.holder === claimant }

    /** The single zone asked for, when exactly one was. */
    val zone: Zone
        get() = zones.single()

    /** True when the space is to be given back on a clock rather than by hand. */
    val isTimed: Boolean
        get() = holdFor.isFinite()

    /** True when every zone asked for has drained and could now be taken together. */
    internal val isDrained: Boolean
        get() = zones.all { it.isDrained }

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
