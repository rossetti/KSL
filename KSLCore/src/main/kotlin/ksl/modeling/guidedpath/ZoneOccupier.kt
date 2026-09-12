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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 * Told when an occupier's hold on guide-path space begins.
 *
 * Attachable rather than only overridable, because the alternative is that finding out when a
 * closure took effect requires declaring a class. A grant may be the instant it was asked for or
 * much later, so a model that has anything to do once it has the space -- a cleaning time to
 * schedule, a picker to start picking -- needs to be told, and needing a subclass for that is the
 * wrong default. The transporter's arrival listener is the same shape for the same reason.
 */
fun interface ZoneEngagementListenerIfc {

    /**
     * @param occupier the occupier whose hold has just begun
     * @param allocation the hold, which names the zone and when it started
     */
    fun engaged(occupier: ZoneOccupier, allocation: ZoneAllocation)
}

/**
 * Something that takes guide-path space without being a vehicle.
 *
 * A spill. An aisle closed for a safety walk. A picker at a rack face. A lift car out of service. A
 * dropped pallet, a cleaning window, staging overflow at shift change. Each of these denies a zone
 * to traffic in exactly the way a parked vehicle does, and none of them is a vehicle — which was
 * the whole finding behind this construct: `Zone.holder` being typed to a transporter was the only
 * thing standing between the subsystem and that entire family of problems.
 *
 * **Without it, obstruction time is fitted into the wrong parameter.** A model with no spills, no
 * picking interference and no closures must still match observed throughput, so that time goes into
 * inflated task times or a depressed velocity. The model then fits the aggregate and is wrong about
 * the mechanism — and it will give bad advice about any change that alters the obstruction rate,
 * which is precisely the change a study is commissioned to evaluate.
 *
 * ## What an occupier is, mechanically
 *
 * It **never waits for space it cannot have**, and that is its defining property rather than an
 * incidental one. It asks for a zone; the zone drains; it takes it. While it waits it holds nothing,
 * so it has no outgoing edge in the wait-for graph and cannot lie on a circular wait — which is why
 * [awaitedZone] is always null and why deadlock detection treats it as a terminal node. Whatever is
 * stuck behind an occupier is *obstructed*, not deadlocked, and those want different remedies.
 *
 * It is also why an occupier is not a `GuidedTransporter` with the movement left out. A transporter
 * has a route and gives up zones one at a time as it moves; an occupier has an allocation and gives
 * up its zone as a whole. That asymmetry is real and is stated here rather than smoothed over.
 *
 * ## Using one
 *
 * The commonest case is a closure of known duration, and it has its own verb because the arithmetic
 * is a trap otherwise -- the clock must start when the hold *begins*, not when it was asked for, or
 * a two-minute drain silently eats two minutes out of a twenty-minute closure:
 *
 * ```
 * val spill = ZoneOccupier(space, "Spill")
 * spill.holdZoneFor(network.zone("Aisle3.Zone2")!!, cleanupTime.value)
 * ```
 *
 * When the duration is not known in advance -- it depends on what is found, or on a crew arriving --
 * ask for the zone and give it back when done, and be told when the hold began:
 *
 * ```
 * spill.attachEngagementListener { occupier, allocation ->
 *     // the space is ours from `allocation.engagedAt`; decide what happens next
 * }
 * spill.requestZone(network.zone("Aisle3.Zone2")!!)   // the zone begins draining at once
 * // …later…
 * spill.releaseZone()
 * ```
 *
 * `requestZone` returns immediately whether or not the zone was free: the zone is closed to new
 * traffic from that instant, and the hold begins when whatever was already there has left.
 * [ZoneEngagementListenerIfc] and the [onEngaged] override are the two ways to be told which.
 *
 * A whole aisle rather than one zone is [requestZones] or [holdZonesFor], which take the set
 * **together or not at all** -- the rule is on [requestZones], and the reason it is a rule is that
 * the alternative deadlocks invisibly.
 *
 * **One request at a time**: a second while one is outstanding is refused. An occupier is the
 * identity of a closure, so two overlapping closures are two occupiers.
 *
 * ## Statistics
 *
 * Here rather than on the zones, and deliberately: there are thousands of zones and a handful of
 * occupiers, so a response per zone would cost every model dearly to report nothing. An occupier
 * collects the time it spent holding space, the time it spent waiting for space to drain, and how
 * many times each happened — which is what makes the decomposition of vehicle blocked time a
 * testable claim rather than an assumption.
 *
 * @param space the guide path whose zones this occupier takes
 * @param name the occupier's name, unique in the model
 */
open class ZoneOccupier(
    val space: GuidedPathSpace,
    name: String? = null
) : ModelElement(space, name), ZoneHolderIfc {

    /**
     * Always null: an occupier never waits for space it does not have.
     *
     * The one thing [ZoneHolderIfc] asks of a holder beyond its name, because it is the outgoing
     * edge of the wait-for graph. An occupier waiting for a zone to drain is not waiting *on* the
     * zone in this sense — it holds nothing while it waits, so no cycle can run through it, and the
     * deadlock walk stops here.
     */
    final override val awaitedZone: Zone?
        get() = null

    // Nothing about what is asked for or held is kept here. The space owns that, and asking it is
    // what keeps a second copy from drifting -- the defect this subsystem has already met with a
    // manifest, a position and a zone population. It is also why this class needs no initialize():
    // there is nothing of its own left to clear between replications.

    /** What this occupier has asked for and not yet been given, or null. */
    val request: ZoneRequest?
        get() = space.requestFor(this)

    /** The space this occupier currently holds, or null when it holds none. */
    val allocation: ZoneAllocation?
        get() = space.allocationFor(this)

    /** True while a zone is draining for this occupier. */
    val isWaitingForSpace: Boolean
        get() = space.isWaitingForZones(this)

    /** True while this occupier holds a zone. */
    val isHoldingSpace: Boolean
        get() = space.isHoldingZones(this)

    // ---- statistics: on the holder, because there are few holders and many zones ---------------

    private val myFracTimeHolding = TWResponse(this, name = "${this.name}:FracTimeHoldingSpace")

    /** The fraction of time this occupier held guide-path space. */
    val fracTimeHoldingSpace: TWResponseCIfc
        get() = myFracTimeHolding

    private val myFracTimeWaiting = TWResponse(this, name = "${this.name}:FracTimeWaitingForSpace")

    /**
     * The fraction of time this occupier spent waiting for space to drain.
     *
     * The cost of draining rather than evicting, measured. A closure that is wanted *now* and
     * arrives late because an aisle was busy is a real effect on whatever the occupier represents,
     * and it is invisible unless it is counted.
     */
    val fracTimeWaitingForSpace: TWResponseCIfc
        get() = myFracTimeWaiting

    private val myTimeToEngage = Response(this, name = "${this.name}:TimeToEngage")

    /** How long each request waited for its zone to drain. Zero when the zone was already free. */
    val timeToEngage: ResponseCIfc
        get() = myTimeToEngage

    private val myNumEngagements = Counter(this, name = "${this.name}:NumEngagements")

    /** How many times this occupier took a zone. */
    val numEngagements: CounterCIfc
        get() = myNumEngagements

    // ---- the two things a modeller does --------------------------------------------------------

    /**
     * Asks for a zone, which closes to new traffic at once and is held as soon as it has drained.
     *
     * Returns without waiting, whether or not the zone was free. What the zone does from this
     * instant is refuse every new claim and every new admission; what was already in it finishes
     * and leaves in its own time. [onEngaged] fires when the hold actually begins, which is this
     * same instant when the zone was already empty.
     *
     * @param zone the zone to take, which must be on this occupier's guide path
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun requestZone(zone: Zone): ZoneRequest = ask(listOf(zone), Double.NaN)

    /**
     * Asks for a set of zones, which all close to new traffic at once and are held **together**.
     *
     * All or nothing, and that is the rule rather than a convenience. Taking the zones one by one
     * as they drain would let the occupier hold part of a region while waiting for the rest, and a
     * vehicle inside the region could then be waiting for a zone the occupier holds while the
     * occupier waits for the zone the vehicle is standing in. That is a deadlock, and an invisible
     * one: an occupier has no [awaitedZone], so the wait-for graph has no edge to close a cycle
     * with and the detector would never report it. Holding nothing until every zone has drained
     * keeps the occupier a sink, which makes the deadlock impossible rather than undetectable.
     *
     * Traffic already inside the region is let out rather than trapped -- see `ZoneClosureIfc` --
     * which is what makes the drain terminate however busy the region is. The cost is that a
     * closure over busy space begins later, and [fracTimeWaitingForSpace] is that delay, measured.
     *
     * The extent is chosen per occurrence, at run time, and the sources cost nothing: a link's
     * zones by name, a junction's zone, a zone at a station, or a sample drawn from the network.
     *
     * @param zones the zones to take, all on this occupier's guide path, distinct, at least one
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun requestZones(zones: List<Zone>): ZoneRequest = ask(zones, Double.NaN)

    /**
     * Takes a zone for a stated duration, and gives it back without being asked again.
     *
     * The commonest case, and the one with the trap in it. The duration is measured **from the
     * instant the hold begins**, not from the request -- so a closure of twenty minutes on an aisle
     * that takes two minutes to drain occupies the zone for twenty minutes and is outstanding for
     * twenty-two. Measuring from the request instead would silently shorten every closure by
     * however long the drain happened to take, which depends on traffic and so varies between
     * replications: a defect that shows up as a closure duration that is not the one the modeller
     * asked for, and nowhere as an error.
     *
     * @param zone the zone to take, which must be on this occupier's guide path
     * @param duration how long to hold it once the hold begins, strictly positive
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun holdZoneFor(zone: Zone, duration: Double): ZoneRequest {
        require(duration > 0.0) {
            "Occupier ($name) was asked to hold zone (${zone.name}) for $duration, which is not a " +
                    "duration. To take a zone until told otherwise, use requestZone."
        }
        return ask(listOf(zone), duration)
    }

    /**
     * Takes a set of zones together for a stated duration, and gives them back without being asked.
     *
     * [requestZones] for the all-or-nothing rule, [holdZoneFor] for why the duration runs from the
     * instant the hold begins rather than from the request.
     *
     * @param zones the zones to take, all on this occupier's guide path, distinct, at least one
     * @param duration how long to hold them once the hold begins, strictly positive
     */
    fun holdZonesFor(zones: List<Zone>, duration: Double): ZoneRequest {
        require(duration > 0.0) {
            "Occupier ($name) was asked to hold ${zones.size} zone(s) for $duration, which is not " +
                    "a duration. To take space until told otherwise, use requestZones."
        }
        return ask(zones, duration)
    }

    /**
     * The one way a request is made, so that the refusal below runs before anything is recorded.
     *
     * How long the hold is to last travels *with* the request rather than being set on this object
     * first, because an immediate grant happens inside the call -- so anything set this side of it
     * would be recorded too late, and anything set before it would survive a refusal.
     */
    private fun ask(zones: List<Zone>, duration: Double): ZoneRequest {
        val request = space.requestZonesFor(this, zones, duration, myHoldAction)
        // Recorded here rather than by the space, and only when the space did not grant it
        // outright: an immediate grant has already run holdBegan, which settles both clocks.
        if (request.isWaiting) {
            myFracTimeWaiting.value = 1.0
        }
        return request
    }

    /**
     * Gives the zone back, and wakes whoever was waiting for it.
     *
     * Harmless on an occupier that holds nothing. On one that has asked for a zone still draining,
     * this gives up the request instead, which is what a closure cancelled before it took effect
     * does.
     */
    fun releaseZone() {
        // Asked before, because the space will have forgotten by the time it returns. A request
        // given up while it was still draining is told to nobody -- that is the contract, since
        // abandonment is always the caller's own act -- so its clock is stopped here.
        val wasWaiting = isWaitingForSpace
        space.releaseZonesFrom(this)
        if (wasWaiting) {
            myFracTimeWaiting.value = 0.0
        }
    }

    /**
     * Called when the hold begins, which may be the same instant the request was made or much
     * later. Does nothing by default.
     *
     * The hook for a subclass. [attachEngagementListener] is the same notification for a model that
     * would rather not declare one, and both are told: this first, then the listeners.
     */
    protected open fun onEngaged(allocation: ZoneAllocation) {}

    private val myEngagementListeners = mutableListOf<ZoneEngagementListenerIfc>()

    /** Starts telling a listener when this occupier's holds begin. */
    fun attachEngagementListener(listener: ZoneEngagementListenerIfc) {
        myEngagementListeners.add(listener)
    }

    /** Stops telling a listener about holds. */
    fun detachEngagementListener(listener: ZoneEngagementListenerIfc) {
        myEngagementListeners.remove(listener)
    }

    // ---- what the space tells this occupier ----------------------------------------------------

    /**
     * The one thing the space drives, and the only place the statistics are settled.
     *
     * A private object rather than this class implementing the interface, so that `holdBegan` and
     * `holdEnded` stay off an occupier's public surface: they are the space's to call and nobody
     * else's.
     */
    private val myHoldAction = object : ZoneHoldActionIfc {

        override fun holdBegan(allocation: ZoneAllocation) {
            myFracTimeWaiting.value = 0.0
            myFracTimeHolding.value = 1.0
            myTimeToEngage.value = allocation.timeToEngage
            myNumEngagements.increment()
            onEngaged(allocation)
            // Copied, so that a listener may detach itself, or attach another, while being told.
            for (listener in myEngagementListeners.toList()) {
                listener.engaged(this@ZoneOccupier, allocation)
            }
        }

        override fun holdEnded(allocation: ZoneAllocation) {
            myFracTimeHolding.value = 0.0
        }
    }

    override fun toString(): String = buildString {
        append("ZoneOccupier($name, ")
        append(
            allocation?.let { "holding ${it.zone.name} since ${it.engagedAt}" }
                ?: request?.let { "waiting for ${it.zone.name} since ${it.requestedAt}" }
                ?: "holding nothing"
        )
        append(")")
    }
}
