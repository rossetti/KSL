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

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ShiftControl
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.routing.Route
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.spatial.SpatialElement
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.toDouble

/**
 * Where a transporter stands at the start of every replication.
 *
 * Placement is declared rather than remembered, and re-applied on every replication, so a run that
 * ends with the fleet scattered -- or deadlocked -- cannot bias the next one. Where a transporter
 * starts matters more on a guide path than in an ordinary resource pool, because a stationary
 * transporter occupies space that others may need.
 */
sealed class TransporterPlacement {

    /** Standing at a junction, addressed by its name or by a station alias. */
    data class At(val locationName: String) : TransporterPlacement()

    /**
     * Standing on a link, covering the named zone and the zones behind it.
     *
     * @param zoneName the zone the transporter's front is in
     */
    data class OnZone(val zoneName: String) : TransporterPlacement()
}

/**
 * What a transporter is doing.
 *
 * The states divide into those that involve an entity and those that do not, and blocking can
 * happen from either side: a transporter returning to its home base with nothing aboard can be
 * blocked just as a loaded one can. That is why blocking remembers what was interrupted rather than
 * inferring it from whether an entity is present.
 */
enum class TransporterState {

    /** Allocated to no one and going nowhere. Still occupying its zones. */
    IDLE,

    /** Travelling without an entity aboard, to collect one. */
    MOVING_EMPTY,

    /** Travelling with an entity aboard. */
    MOVING_LOADED,

    /** An entity is being loaded. */
    LOADING,

    /** An entity is being unloaded. */
    UNLOADING,

    /** Travelling to a home base or staging area, allocated to no one. */
    RETURNING_HOME,

    /**
     * Being moved by something other than itself -- pushed or towed off the guide path by a person.
     *
     * Its own state rather than a borrowed one. A towed transporter is moving, so it holds and gives
     * up zones exactly as a driving one does and obstructs traffic exactly as much. But it is
     * neither transporting nor running empty *under its own power*, and counting it as either would
     * put a vehicle that has broken down into a utilization figure meant to describe vehicles that
     * are working.
     */
    TOWED,

    /** Unable to claim the next zone, and waiting for whoever holds it. */
    BLOCKED
}

/**
 * How often a transporter's velocity is drawn when its velocity is random.
 */
enum class VelocitySampling {

    /**
     * Drawn once when a movement begins and held for the whole route. Matches how a free-path
     * movable resource behaves, which is what makes a free-path model and a guide-path model of the
     * same system comparable: the difference between them is then congestion, not a change in how
     * velocity was sampled.
     */
    PER_MOVE,

    /**
     * Drawn again for every zone. The right model of a drive whose speed genuinely fluctuates over
     * short distances.
     */
    PER_ZONE
}

/**
 * Told when a transporter reaches the end of a route.
 */
fun interface TransporterArrivalListenerIfc {

    /** @param transporter the transporter that has just arrived */
    fun arrived(transporter: GuidedTransporter)
}

/**
 * Asked, at every zone boundary, whether a transporter may carry on.
 *
 * A zone boundary is the only place a transporter can be stopped without leaving the guide path in
 * a state the space layer cannot describe. Part way into a zone it is physically between two
 * places: it has claimed the zone ahead and there is nothing there to arrive at, so a stop would
 * leave a claimed zone with no arrival. Answering `false` here instead lets the transporter
 * complete the entry it is committed to and halt on a zone it holds -- stationary, still occupying
 * space, and still owning the rest of its route.
 *
 * The space layer neither knows nor asks why. A flat battery, a breakdown, a shift ending, an
 * operator stopping the line: all of them are the same event to the guide path, which is that a
 * vehicle stopped where it stands. A halted transporter is [GuidedTransporter.isHalted] and reads
 * as [TransporterState.IDLE], because that is what it is doing; the reason belongs to whatever
 * installed the gate and is reported there.
 *
 * **A halted transporter does not resume by itself.** Nothing is scheduled for it and nothing waits
 * on it, which is the honest model of a vehicle stopped mid-aisle: it obstructs everyone behind it
 * for the rest of the run. [GuidedPathSpace.resumeHaltedTransporter] is what starts it again, and
 * whatever halted it is responsible for calling that.
 *
 * One gate per transporter, because a gate is a veto: two of them would need a rule for what to do
 * when they disagree, and the only sensible rule -- any veto stops the vehicle -- is something the
 * installer can write in one line.
 */
fun interface TransporterMovementGateIfc {

    /**
     * @param transporter the transporter that has just completed entry into [zone]
     * @param zone the zone it now covers
     * @return true to continue along the route, false to halt here
     */
    fun mayContinue(transporter: GuidedTransporter, zone: Zone): Boolean
}

/**
 * A vehicle on a guide path: a resource of capacity one that occupies space and must claim the
 * space ahead of it before moving into it.
 *
 * A transporter is a resource in every respect, so it is seized and released like any other, and
 * the allocation machinery, request queues, and utilization statistics apply unchanged. What sets
 * it apart is that it has extent. It covers a contiguous run of zones, and while it stands still it
 * denies that space to everyone else -- which is why an idle transporter left on the guide path is
 * a hazard rather than merely an idle asset.
 *
 * It deliberately does **not** extend the free-path movable resource. That class moves by
 * scheduling a single delay computed from distance and velocity, with no notion of the space in
 * between; inheriting it would expose a way to move that ignores every rule this subsystem exists
 * to enforce. The two share the abstractions that genuinely mean the same thing -- position and
 * velocity -- and nothing else.
 *
 * @param system the runtime this transporter belongs to
 * @param initialPlacement where it stands at the start of every replication
 * @param velocity how fast it travels, sampled per movement by default
 * @param lengthInZones how many zones it covers when fully on the guide path
 * @param zoneControlRule when it gives up the zone behind it
 * @param name a name for it
 */
/**
 * Who is waiting on a transporter's journey, and the queue holding them.
 *
 * @param waiter the entity suspended until the journey ends
 * @param queue the movement queue it is suspended in, which decides how the wait is described
 */
internal class JourneyWait(
    val waiter: ProcessModel.Entity,
    val queue: HoldQueue
)

class GuidedTransporter @JvmOverloads constructor(
    val system: GuidedPathSpace,
    initialPlacement: TransporterPlacement,
    velocity: RVariableIfc,
    val lengthInZones: Int = 1,
    val zoneControlRule: ZoneControlRuleIfc = EndOfZoneControl(),
    name: String? = null,
    val physicalLength: Double? = null,
    val loadCapacity: Int = 1
) : Resource(system, name, 1), ksl.modeling.spatial.VehicleMovementIfc, ZoneHolderIfc {

    init {
        if (physicalLength != null) {
            require(physicalLength > 0.0) {
                "A transporter's physical length must be > 0.0, but ($name) was given $physicalLength."
            }
            require(lengthInZones == 1) {
                "Transporter ($name) was given both a physical length ($physicalLength) and a size " +
                        "of $lengthInZones zones. A transporter is sized one way or the other -- by " +
                        "whole zones or by length -- so that there is one answer to how much of the " +
                        "guide path it covers."
            }
            // Deliberately narrow. A body longer than a zone raises questions this subsystem has no
            // validated answer to -- how many zones it covers on a link discretized differently from
            // the one it is leaving, and what its extent means while it spans two links at once --
            // and guessing at them would ship semantics nothing has checked. A vehicle that fits a
            // zone needs none of that: it covers exactly one everywhere, so the length changes only
            // what it is here to change, which is the distance it owes itself when it backs out of a
            // dead end. Longer vehicles are sized in zones, as they always were.
            val smallest = system.network.links.minOf { it.zoneLength }
            require(physicalLength <= smallest) {
                "Transporter ($name) has a physical length of $physicalLength, which is longer than " +
                        "the smallest zone on network (${system.network.name}) at $smallest. Size a " +
                        "transporter by length only when it fits within a zone; a longer one is " +
                        "sized in zones with the lengthInZones argument."
            }
        }
        require(lengthInZones >= 1) {
            "A transporter must cover at least one zone, but ($name) was given $lengthInZones."
        }
        system.addTransporter(this)
    }

    /** Where this transporter stands at the start of every replication. */
    var initialPlacement: TransporterPlacement = initialPlacement
        set(value) {
            require(model.isNotRunning) {
                "The initial placement cannot be changed while the model is running."
            }
            field = value
        }

    /**
     * Where this transporter waits when it has nothing to do, or null when it waits where it stops.
     *
     * Named rather than resolved, so that a network can be rebuilt from data without the fleet
     * holding stale references into the old one.
     */
    var homeBase: String? = null
        set(value) {
            require(model.isNotRunning) {
                "The home base cannot be changed while the model is running."
            }
            field = value
        }

    /** How often the velocity is drawn when it is random. */
    var velocitySampling: VelocitySampling = VelocitySampling.PER_MOVE
        set(value) {
            require(model.isNotRunning) {
                "The velocity sampling policy cannot be changed while the model is running."
            }
            field = value
        }

    private val myVelocity: RandomVariable =
        RandomVariable(this, velocity, name = "${this.name}:VelocityRV")

    /** The velocity source. */
    val velocityRV: RandomVariableCIfc
        get() = myVelocity

    private val mySpatialElement: SpatialElement =
        SpatialElement(this, system.network.defaultLocation, this.name)

    /** Where the transporter is, for animation and for updating what it carries. */
    var currentLocation: LocationIfc
        get() = mySpatialElement.currentLocation
        internal set(value) {
            mySpatialElement.currentLocation = value
        }

    private val myCoveredZones = mutableListOf<Zone>()

    /**
     * The zones the transporter covers, from the rear of the vehicle to its front. Contiguous along
     * its direction of travel, and no longer than its length once it is fully on the guide path.
     */
    val coveredZones: List<Zone>
        get() = myCoveredZones

    /**
     * The zone the transporter has reserved and is travelling into, or null when it is not moving.
     *
     * A transporter holds this zone as surely as it holds the ones it covers -- no one else may
     * enter it -- but its body is not in it yet. The distinction matters under a control rule that
     * gives up the zone behind at the moment travel begins: a transporter one zone long is then
     * briefly between zones, covering none and holding only this one.
     */
    var claimedZone: Zone? = null
        internal set

    /** Every zone the transporter denies to others: the ones it covers, plus the one it is entering. */
    val heldZones: List<Zone>
        get() = claimedZone?.let { myCoveredZones + it } ?: myCoveredZones

    /** The zone at the leading edge, or null before the transporter has been placed. */
    val frontZone: Zone?
        get() = myCoveredZones.lastOrNull()

    /** The zone at the trailing edge, or null before the transporter has been placed. */
    val rearZone: Zone?
        get() = myCoveredZones.firstOrNull()

    /**
     * What the transporter is doing. Named apart from the resource state it inherits, which says
     * whether the resource is busy or idle rather than what the vehicle is up to.
     */
    var transporterState: TransporterState = TransporterState.IDLE
        internal set(value) {
            // Blocked time is accumulated here rather than read back off the time-weighted
            // statistic, because that statistic reports an average over the post-warm-up interval
            // and only moves when the state does. A journey needs the elapsed blocked time between
            // two instants, which is a different quantity.
            if (field == TransporterState.BLOCKED && value != TransporterState.BLOCKED) {
                // Accumulate only against a start instant that was actually recorded. The reset
                // in `placeAtInitialPosition` is what makes this hold -- it sets the state before
                // clearing the clocks, so a transporter still blocked when a replication ended
                // leaves that state through this setter while its start instant is still good.
                // Reversing those two lines is what once made this accumulate `time - NaN`, with
                // the NaN travelling into the next replication's first transport result and
                // failing the run thousands of simulated minutes from its cause.
                // `BlockedTransporterTransitionTest` is what holds the ordering in place now.
                if (!blockedSince.isNaN()) {
                    myCumulativeBlockedTime += time - blockedSince
                }
                blockedSince = Double.NaN
            } else if (field != TransporterState.BLOCKED && value == TransporterState.BLOCKED) {
                blockedSince = time
            }
            // The operating clock, kept the same way and for the same reason. It runs whenever the
            // transporter is anything other than idle -- moving, blocked, loading, unloading -- so
            // that a wear or service model can be written against time in operation rather than
            // time on the wall. The same guard against a replication that ended mid-state applies.
            if (field == TransporterState.IDLE && value != TransporterState.IDLE) {
                operatingSince = time
            } else if (field != TransporterState.IDLE && value == TransporterState.IDLE) {
                if (!operatingSince.isNaN()) {
                    myCumulativeOperatingTime += time - operatingSince
                }
                operatingSince = Double.NaN
            }
            field = value
            myFracTimeMoving.value = isMoving.toDouble()
            myFracTimeBlocked.value = (value == TransporterState.BLOCKED).toDouble()
            myFracTimeTransporting.value = (value == TransporterState.MOVING_LOADED).toDouble()
            if (value == TransporterState.MOVING_LOADED) {
                val r = myLoadsPerLoadedMove
                if (r != null) r.value = myManifest.size.toDouble()
            }
            myFracTimeMovingEmpty.value =
                (value == TransporterState.MOVING_EMPTY || value == TransporterState.RETURNING_HOME).toDouble()
            system.emitTransporterState(this, value)
        }

    /**
     * What the transporter was doing when it blocked, so that it resumes the movement it was making
     * rather than a guess. A transporter returning home and one collecting an entity are both empty
     * and both blockable, so being empty says nothing about which it was.
     */
    internal var stateBeforeBlocking: TransporterState = TransporterState.IDLE

    /** True while the transporter is travelling, whatever the reason. */
    val isMoving: Boolean
        get() = transporterState == TransporterState.MOVING_EMPTY ||
                transporterState == TransporterState.MOVING_LOADED ||
                transporterState == TransporterState.RETURNING_HOME ||
                transporterState == TransporterState.TOWED

    /** The route being followed, or null when the transporter is not travelling. */
    var currentRoute: Route? = null
        internal set

    /**
     * Where the transporter has been told to go instead, once it finishes entering the zone it is
     * already travelling into.
     *
     * A vehicle part way into a zone cannot stop and turn round: it is physically between two
     * places. So a redirection given while it is moving takes effect at the next zone boundary
     * rather than immediately, which is also what keeps the reservation it is holding honest --
     * the zone it reserved is the zone it will enter.
     */
    internal var pendingDestination: GuidedPathNetwork.Intersection? = null

    /** What the transporter will be doing once a pending redirection takes effect. */
    internal var pendingPurpose: MovePurpose = MovePurpose.HOME

    /**
     * Who is suspended until this transporter finishes its current movement, and where, or null.
     *
     * A transporter travels over many events, so an entity riding it -- or waiting for it to come
     * and collect it, or driving it -- has to be held for the whole journey rather than for a
     * single delay. This is who to wake when the journey ends, and which queue to wake them from.
     *
     * The two are one object rather than two fields deliberately. The subsystem holds several
     * movement queues, one per kind of wait, and an entity resumed out of the wrong one would be
     * left enqueued for ever while its process ran on -- a failure that shows up as a queue that
     * never empties, a long way from the line that caused it. Recording the pair together makes
     * the two impossible to get out of step.
     */
    internal var journeyWait: JourneyWait? = null

    /**
     * The spur this transporter has taken over, or null.
     *
     * Held separately from the zones it covers, because a transporter parked at the dead end of a
     * spur covers only the junction there and no part of the spur itself -- yet it has plainly not
     * left, since the only way out is back down the spur. Deciding it had left by looking at link
     * zones alone would let a second transporter in behind it, and the two would then face each
     * other with neither able to move.
     */
    internal var reservedSpur: Link? = null

    /**
     * The zone the transporter is waiting for, or null when it is not waiting.
     *
     * This is the one thing [ZoneHolderIfc] asks of a holder beyond its name, because it is the
     * queuing edge of the wait-for graph. A transporter is the holder that actually has a zone to
     * report; a holder that only occupies space answers null, and the walk ends at one that is
     * merely holding space. What a closure still *waiting* to drain is held up by is reached
     * through its reservation instead, not through this property.
     */
    override var awaitedZone: Zone? = null
        internal set

    /**
     * The link holding the transporter up, or null when it is waiting for a zone instead, or not
     * waiting at all.
     *
     * A transporter can be stopped by a link whose zone is perfectly free: the link may be running
     * the other way, or be a spur that another transporter is down. Recording which of the two is
     * in the way is what lets it be woken by the right event.
     */
    var awaitedLink: Link? = null
        internal set

    /** The direction it faces on a link, which decides where it may go next. */
    var travellingForward: Boolean = true
        internal set

    /** The velocity in force for the current movement, held when sampling is per movement. */
    internal var currentVelocity: Double = 1.0

    /**
     * How fast the transporter is being moved, or null when it is driving itself.
     *
     * Overrides the sampling policy entirely while it is set, because a transporter under tow is not
     * drawing from its own velocity distribution -- somebody is pushing it, and how fast they push
     * has nothing to do with how fast it drives.
     */
    internal var towVelocity: Double? = null
        set(value) {
            val wasUnderTow = field != null
            field = value
            if (wasUnderTow && value == null) announceDispatchable()
        }

    /** True while something other than this transporter is moving it. */
    val isUnderTow: Boolean
        get() = towVelocity != null

    /** The velocity to use for the next zone, drawn according to the sampling policy. */
    internal fun velocityForNextZone(): Double = towVelocity ?: when (velocitySampling) {
        VelocitySampling.PER_MOVE -> currentVelocity
        VelocitySampling.PER_ZONE -> sampleVelocity()
    }

    internal fun sampleVelocity(): Double {
        val v = myVelocity.value
        check(v > 0.0) {
            "Transporter (${this.name}) drew a velocity of $v. A velocity must be > 0.0, so the " +
                    "velocity random variable must not be able to produce zero or a negative value."
        }
        return v
    }

    private val myArrivalListeners = mutableListOf<TransporterArrivalListenerIfc>()

    /**
     * Asks to be told whenever this transporter reaches the end of a route.
     *
     * Arrival is the moment several unrelated concerns care about at once: an entity being carried
     * must be resumed, an animation must be told, a dispatcher may want to send the transporter on.
     * Announcing it leaves the engine ignorant of all of them.
     */
    fun attachArrivalListener(listener: TransporterArrivalListenerIfc) {
        myArrivalListeners.add(listener)
    }

    /** Stops telling a listener about arrivals. */
    fun detachArrivalListener(listener: TransporterArrivalListenerIfc) {
        myArrivalListeners.remove(listener)
    }

    internal fun notifyArrival() {
        // The system first, so that an entity being carried is released before anything a modeler
        // has attached runs and possibly sends this transporter somewhere else.
        system.transporterArrived(this)
        // Copied, so that a listener may detach itself, or attach another, while being told.
        for (listener in myArrivalListeners.toList()) {
            listener.arrived(this)
        }
    }

    // ---- statistics ---------------------------------------------------------------------------

    private val myFracTimeMoving = TWResponse(this, name = "${this.name}:FracTimeMoving")

    /** The fraction of time spent travelling, for or without an entity. */
    val fracTimeMoving: TWResponseCIfc
        get() = myFracTimeMoving

    private val myFracTimeTransporting = TWResponse(this, name = "${this.name}:FracTimeTransporting")

    /** The fraction of time spent travelling with an entity aboard. */
    val fracTimeTransporting: TWResponseCIfc
        get() = myFracTimeTransporting

    private val myFracTimeMovingEmpty = TWResponse(this, name = "${this.name}:FracTimeMovingEmpty")

    /** The fraction of time spent travelling with nothing aboard. */
    val fracTimeMovingEmpty: TWResponseCIfc
        get() = myFracTimeMovingEmpty

    private val myFracTimeBlocked = TWResponse(this, name = "${this.name}:FracTimeBlocked")

    /**
     * The fraction of time spent unable to claim the space ahead. The statistic a free-path model
     * cannot produce, and the one that says how much a fleet is getting in its own way.
     */
    val fracTimeBlocked: TWResponseCIfc
        get() = myFracTimeBlocked

    private val myNumTimesBlocked = Counter(this, name = "${this.name}:NumTimesBlocked")

    /** How many times the transporter has been unable to claim the space ahead. */
    val numTimesBlocked: CounterCIfc
        get() = myNumTimesBlocked

    internal fun countBlocking() {
        myNumTimesBlocked.increment()
    }

    private var blockedSince: Double = Double.NaN
    private var myCumulativeBlockedTime: Double = 0.0

    /**
     * How long this transporter has spent blocked so far in this replication, including any block
     * still in progress. Differences of this between two instants give the blocked time within a
     * journey.
     */
    internal val cumulativeBlockedTime: Double
        get() = if (blockedSince.isNaN()) myCumulativeBlockedTime
        else myCumulativeBlockedTime + (time - blockedSince)

    // ---- odometers ------------------------------------------------------------------------------
    //
    // Two running totals, read on demand rather than stepped by events, in exactly the pattern
    // `cumulativeBlockedTime` above already uses: an accumulator plus whatever is in progress. A
    // feature built on these -- a battery, a wear model, a service interval -- therefore costs the
    // event calendar nothing, which matters for a subsystem that publishes its events per zone
    // traversal as a performance figure.

    private var myCumulativeOperatingTime: Double = 0.0
    private var operatingSince: Double = Double.NaN

    /**
     * How long this transporter has been anything other than idle in this replication.
     *
     * Moving, blocked, loading and unloading all count; standing with nothing to do does not. This
     * is the clock a service interval or a wear model runs on, and it is deliberately not the same
     * as elapsed time: a fleet with long quiet periods ages differently by the two, and choosing
     * between them is the modeller's decision rather than one this class should make for them.
     */
    override val operatingTime: Double
        get() = if (operatingSince.isNaN()) myCumulativeOperatingTime
        else myCumulativeOperatingTime + (time - operatingSince)

    private var myCumulativeDistance: Double = 0.0
    private var traversalDistance: Double = 0.0
    private var traversalDuration: Double = 0.0
    private var traversalStartedAt: Double = Double.NaN

    /**
     * How far this transporter has travelled in this replication, in the network's own length
     * units, including the part of a traversal still under way.
     *
     * The distance is the ground actually covered, which is not always the length of the zones
     * entered: a transporter that backs out of a spur is credited its own length against the way
     * out (see [physicalLength]), and it does not travel the distance that credit pays for. The
     * in-progress term is linear in elapsed time, which is exact -- a traversal runs at one
     * velocity from the moment it is scheduled.
     */
    override val distanceTravelled: Double
        get() {
            if (traversalStartedAt.isNaN() || traversalDuration <= 0.0) return myCumulativeDistance
            val fraction = ((time - traversalStartedAt) / traversalDuration).coerceIn(0.0, 1.0)
            return myCumulativeDistance + traversalDistance * fraction
        }

    private var myZonesEntered: Int = 0

    /**
     * How many zones this transporter has entered in this replication.
     *
     * The odometer's companion, and the count to take differences of when something wants to know
     * how many zones a particular journey crossed. The route knows how many it *intends* to cross,
     * which is a different number the moment a journey is redirected or interrupted part way.
     */
    val zonesEntered: Int
        get() = myZonesEntered

    /** Opens a traversal for the odometer. Called by the engine as it schedules the arrival. */
    internal fun beginTraversal(distance: Double, duration: Double) {
        traversalDistance = distance
        traversalDuration = duration
        traversalStartedAt = time
    }

    /** Credits a completed traversal and closes it. Called first thing on entering the zone. */
    internal fun endTraversal() {
        myZonesEntered++
        if (traversalStartedAt.isNaN()) return
        myCumulativeDistance += traversalDistance
        traversalDistance = 0.0
        traversalDuration = 0.0
        traversalStartedAt = Double.NaN
    }

    // ---- what is aboard --------------------------------------------------------------------------

    private val myManifest = mutableListOf<ProcessModel.Entity>()

    /**
     * The loads currently aboard, in the order they were taken on.
     *
     * This is what makes carrying a **fact** rather than a claim. The transporter's moving state is
     * derived from it (see [movingStateFor]), so `MOVING_LOADED` cannot be asserted by a protocol
     * that is mistaken about what it is carrying -- and `FracTimeTransporting`, which is computed
     * from that state, cannot be wrong in a way nothing would notice.
     *
     * It is on the physical layer rather than in either protocol because a load aboard a vehicle is
     * physical and because every protocol has one: the passive one carries a single entity, the
     * active one carries whatever its tour has picked up, and a fixed-route one carries whoever
     * boarded. One list, one derivation, one set of statistics.
     */
    val manifest: List<ProcessModel.Entity>
        get() = myManifest

    /** How many loads are aboard. */
    val numLoadsAboard: Int
        get() = myManifest.size

    /** How many more this transporter could take. */
    val spareCapacity: Int
        get() = loadCapacity - myManifest.size

    /** True when anything at all is aboard. */
    val isCarryingLoad: Boolean
        get() = myManifest.isNotEmpty()

    /** True when it can take no more. */
    val isAtCapacity: Boolean
        get() = myManifest.size >= loadCapacity

    // ---- what the capacity is doing --------------------------------------------------------------
    //
    // Registered only for a transporter that can carry more than one, on the convention this
    // subsystem keeps throughout: a row measuring something the model does not have is a question
    // its reader has to answer for themselves every time. They live here, with the manifest, rather
    // than in either protocol, so that a dispatched fleet and a fixed-route service report the same
    // numbers without either of them computing them.

    private val myNumLoadsAboard: TWResponse? =
        if (loadCapacity <= 1) null else TWResponse(this, "${this.name}:NumLoadsAboard")

    /** The mean number of loads aboard, over the replication. Null for a single-load transporter. */
    val numLoadsAboardResponse: TWResponseCIfc?
        get() = myNumLoadsAboard

    private val myCapacityUtilization: TWResponse? =
        if (loadCapacity <= 1) null else TWResponse(this, "${this.name}:CapacityUtilization")

    /**
     * How much of the capacity was used, as a time-weighted fraction.
     *
     * **This is the row `FracTimeTransporting` is mistaken for.** That one reads 1.0 whether a
     * vehicle carries one load or four: it is a fraction of *time*, not of *capacity*, and reading
     * it as utilization on a multi-load fleet reports a vehicle moving one pallet at a time in a
     * four-pallet body as fully utilised. This is the one that answers the question people mean.
     */
    val capacityUtilization: TWResponseCIfc?
        get() = myCapacityUtilization

    private val myFracTimeAtCapacity: TWResponse? =
        if (loadCapacity <= 1) null else TWResponse(this, "${this.name}:FracTimeAtCapacity")

    /**
     * The fraction of time the transporter was full.
     *
     * **Mean utilization cannot answer this**, and that is why it is a separate row. A fleet at 50%
     * mean utilization could be alternately empty and full, which wants more vehicles, or steadily
     * half full, which wants smaller ones. The two call for opposite decisions and the mean is the
     * same.
     */
    val fracTimeAtCapacity: TWResponseCIfc?
        get() = myFracTimeAtCapacity

    private val myLoadsPerLoadedMove: Response? =
        if (loadCapacity <= 1) null else Response(this, "${this.name}:LoadsPerLoadedMove")

    /**
     * How many loads were aboard each time a loaded movement began.
     *
     * **Is consolidation actually happening?** A capacity-four fleet whose loaded moves average 1.02
     * has bought nothing: it has the room and is not using it, and no time-weighted row says so as
     * plainly. Observed when a movement begins rather than per journey, so a transporter redirected
     * mid-move records the new movement as well -- which is right, because it is a different move.
     */
    val loadsPerLoadedMove: ResponseCIfc?
        get() = myLoadsPerLoadedMove

    /** Republishes what the manifest currently is. Called whenever it changes. */
    private fun observeCapacity() {
        myNumLoadsAboard?.value = myManifest.size.toDouble()
        myCapacityUtilization?.value = myManifest.size.toDouble() / loadCapacity
        myFracTimeAtCapacity?.value = isAtCapacity.toDouble()
    }

    /** Takes a load aboard. Refuses one that is already aboard, which would corrupt the count. */
    internal fun board(load: ProcessModel.Entity) {
        require(myManifest.none { it === load }) {
            "Load (${load.name}) is already aboard transporter (${this.name})."
        }
        myManifest.add(load)
        require(myManifest.size <= loadCapacity) {
            "Transporter (${this.name}) was given ${myManifest.size} loads but holds $loadCapacity."
        }
        observeCapacity()
    }

    /** Sets a load down. Refuses one that is not aboard. */
    internal fun alight(load: ProcessModel.Entity) {
        val removed = myManifest.removeAll { it === load }
        require(removed) {
            "Load (${load.name}) is not aboard transporter (${this.name}) and cannot be set down."
        }
        observeCapacity()
    }

    /**
     * The state a journey for this purpose puts the transporter in.
     *
     * Carrying is read from the manifest; the caller supplies only the purpose. A towed transporter
     * is `TOWED` whatever it holds, because it is moving under somebody else's power and counting
     * it as transporting would put a broken vehicle into a figure meant to describe working ones.
     */
    internal fun movingStateFor(purpose: MovePurpose): TransporterState = when (purpose) {
        MovePurpose.TOW -> TransporterState.TOWED
        MovePurpose.HOME -> TransporterState.RETURNING_HOME
        MovePurpose.SERVICE ->
            if (isCarryingLoad) TransporterState.MOVING_LOADED else TransporterState.MOVING_EMPTY
    }

    // ---- the movement seam -----------------------------------------------------------------------

    /**
     * Where it is now, taken from the zone it holds rather than from [currentLocation].
     *
     * `currentLocation` is written at placement and at arrival, so it names where a moving
     * transporter *set off from* for the whole of its journey. Every distance-based decision reads
     * this instead, which is what stops a rule scoring a fleet on where it used to be.
     */
    override val positionNow: LocationIfc
        get() = system.locationOf(this)

    /** Distance along the guide path, which on a one-way network is not separation in space. */
    override fun pathDistanceTo(destination: LocationIfc): Double =
        system.network.distance(positionNow, destination)

    override fun isReachable(destination: LocationIfc): Boolean =
        system.network.isReachable(positionNow, destination)

    /**
     * Commands a journey with this transporter's own agent or driver as the waiter.
     *
     * The wait is always the *driving* one. A load being carried waits in whatever queue its own
     * protocol suspends it in, and passing it here would attribute its riding time to the space
     * layer instead -- a model that still works while every transport statistic lands in the wrong
     * place.
     */
    override fun beginTravelTo(
        destination: LocationIfc,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity
    ): HoldQueue? = system.beginJourney(this, destination.name, purpose, waiter, MovementWait.DRIVING)

    override fun resumeHalted() {
        system.resumeHaltedTransporter(this)
    }

    // ---- halting at a zone boundary -------------------------------------------------------------

    private var myMovementGate: TransporterMovementGateIfc? = null

    /**
     * Installs the gate that is asked, at every zone boundary, whether this transporter may carry
     * on. Replaces any gate already installed; pass null to remove it.
     *
     * See [TransporterMovementGateIfc] for what a refusal means and who is then responsible for
     * starting the transporter again.
     */
    fun attachMovementGate(gate: TransporterMovementGateIfc?) {
        myMovementGate = gate
    }

    internal fun mayContinuePast(zone: Zone): Boolean = myMovementGate?.mayContinue(this, zone) ?: true

    /**
     * True while the transporter is stopped at a zone boundary with route left to run.
     *
     * It still holds its zones and still owns its route; nothing is scheduled for it. Distinct from
     * blocked, which is a transporter waiting for space that someone else will eventually give up:
     * a halted transporter is waiting for whatever halted it to release it, and if nothing does, it
     * stands there for the rest of the replication.
     */
    override var isHalted: Boolean = false
        private set

    private var stateBeforeHalt: TransporterState = TransporterState.IDLE

    internal fun halt() {
        if (isHalted) return
        stateBeforeHalt = transporterState
        isHalted = true
        transporterState = TransporterState.IDLE
    }

    /** Restores the state the transporter was halted out of. The engine restarts the movement. */
    internal fun releaseHalt() {
        if (!isHalted) return
        isHalted = false
        transporterState = stateBeforeHalt
        announceDispatchable()
    }

    /**
     * Drops the halt without restoring anything, for a transporter that is being given a new journey
     * rather than resuming the one it stopped in the middle of. The move sets the state itself.
     */
    internal fun clearHalt() {
        if (!isHalted) return
        isHalted = false
        announceDispatchable()
    }

    // ---- being available to a pool ---------------------------------------------------------------

    /**
     * Whether this transporter could be sent somewhere if it were asked right now.
     *
     * Distinct from being unallocated, and the distinction is the point. A transporter is
     * *unallocated* when nobody holds it, which is a fact about ownership. It is **dispatchable**
     * when nobody holds it *and* it is able to move, which is a fact about the vehicle. The two
     * differ for a transporter that is repositioning under an idle disposition rule and is halted
     * by its movement gate part way, or one that something else is towing: it belongs to nobody, and
     * it is going nowhere.
     *
     * A pool sends only dispatchable transporters, and an allocation rule is offered only these, so
     * a rule never has to know this property exists.
     */
    val isDispatchable: Boolean
        get() = numBusy == 0 && !isHalted && !isUnderTow

    private val myPools = mutableListOf<GuidedTransporterPoolWithQ>()

    /** Records a pool this transporter belongs to, so it can be re-offered when it can move again. */
    internal fun joinPool(pool: GuidedTransporterPoolWithQ) {
        if (myPools.none { it === pool }) myPools.add(pool)
    }

    /**
     * Tells every pool holding this transporter that it can move again.
     *
     * Without this the fix for the blindness would create the failure it was written to prevent. A
     * pool that declines to offer a halted transporter has, at that moment, nothing to offer; the
     * ordinary wake path runs on release, and a transporter that is released from a halt is not
     * being released by anybody. So the moment that makes it dispatchable has to say so, or an
     * entity waits for a transporter that is standing free in front of it.
     */
    private fun announceDispatchable() {
        if (!isDispatchable) return
        for (pool in myPools) pool.transporterBecameDispatchable()
    }

    // ---- placement and movement ---------------------------------------------------------------

    /**
     * Puts the transporter where its placement says, claiming and occupying the zones it covers.
     * Called for every transporter at the start of every replication.
     */
    internal fun placeAtInitialPosition() {
        myCoveredZones.clear()
        currentRoute = null
        claimedZone = null
        pendingDestination = null
        myManifest.clear()
        observeCapacity()
        awaitedZone = null
        awaitedLink = null
        reservedSpur = null
        journeyWait = null
        travellingForward = true
        isHalted = false
        transporterState = TransporterState.IDLE
        stateBeforeBlocking = TransporterState.IDLE
        stateBeforeHalt = TransporterState.IDLE
        lengthCreditRemaining = 0.0
        // After the state, so that a transporter still blocked when the previous replication ended
        // leaves that state through the setter above before its running totals are cleared.
        blockedSince = Double.NaN
        myCumulativeBlockedTime = 0.0
        operatingSince = Double.NaN
        myCumulativeOperatingTime = 0.0
        myCumulativeDistance = 0.0
        myZonesEntered = 0
        towVelocity = null
        traversalDistance = 0.0
        traversalDuration = 0.0
        traversalStartedAt = Double.NaN
        currentVelocity = sampleVelocity()
        val zones = system.resolvePlacement(this, initialPlacement)
        for (zone in zones) {
            if (!zone.claim(this)) {
                throw GuidedPathNetworkException.placementOverlap(
                    zone.holder?.name ?: "another transporter", this.name, zone.name
                )
            }
            zone.cover(this)
            myCoveredZones.add(zone)
        }
        currentLocation = system.locationOf(this)
    }

    /** Adds a zone at the leading edge. Called only by the movement engine. */
    internal fun addFrontZone(zone: Zone) {
        myCoveredZones.add(zone)
    }

    /** Removes the trailing zone. Called only by the movement engine. */
    internal fun removeRearZone(): Zone? =
        if (myCoveredZones.isEmpty()) null else myCoveredZones.removeAt(0)

    /**
     * True when the transporter covers more zones than it is long, so a zone at the rear is surplus
     * and may be given up. This is the test that applies once a zone has been entered.
     */
    /**
     * How much of this transporter's own length is still to be credited against the distance ahead.
     *
     * Zero except immediately after a reversal. A transporter with a [physicalLength] is a body
     * rather than a point: standing at the end of a spur, it already covers its own length of that
     * spur, so when it turns round the end that now leads is that much further along the way out.
     * The credit is spent over the zones it re-crosses and is then done with.
     */
    internal var lengthCreditRemaining: Double = 0.0

    internal val hasSurplusZones: Boolean
        get() = myCoveredZones.size > lengthInZones

    /**
     * True when the transporter is fully on the guide path, so that giving up the zone behind
     * leaves it still covering its own length together with the zone it is entering.
     *
     * This is the test that applies at the moment travel begins, and it is deliberately not the
     * same test as the one above. A transporter still driving onto the network covers fewer zones
     * than it is long and has nothing to spare; one that is fully on has already claimed the zone
     * ahead, so releasing the zone behind keeps the count right even though the claimed zone is not
     * yet covered.
     */
    internal val isFullyOnPath: Boolean
        get() = myCoveredZones.size >= lengthInZones

    /**
     * Sends an unallocated transporter to a destination, without an entity aboard.
     *
     * This is how a transporter is repositioned: to a home base, to a staging area, or simply out
     * of the way. It returns as soon as the movement has begun; the transporter travels over
     * simulated time and the caller does not wait.
     *
     * @param destinationName an intersection name or station alias
     * @return true when a movement was started, false when the transporter was already there
     * @throws IllegalStateException when the transporter is allocated to an entity
     */
    fun sendTo(destinationName: String): Boolean {
        check(numBusy == 0) {
            "Transporter (${this.name}) is allocated and cannot be sent somewhere on its own."
        }
        return system.startMove(this, destinationName, MovePurpose.HOME)
    }

    // ---- shifts ---------------------------------------------------------------------------------

    private val myShift: ShiftControl = ShiftControl(this)

    /** True while this transporter has been taken off shift. */
    val isOffShift: Boolean
        get() = myShift.isOffShift

    /**
     *  Takes this transporter off shift: it cannot be allocated again until [goOnShift], and
     *  requests for it wait in whatever pool queue they were made against.
     *
     *  **It stays where it is and keeps the space it occupies.** A transporter off shift is not
     *  gone from the guide path; it is a vehicle standing in an aisle, which is what a real one
     *  parked at the end of a shift is, and the blocking that follows is a fact about the layout
     *  rather than an artefact. Park it somewhere out of the way first, with [sendTo], if that is
     *  what the site does.
     */
    fun goOffShift() = myShift.goOffShift()

    /** Puts this transporter back on shift. */
    fun goOnShift() = myShift.goOnShift()

    override fun initialize() {
        super.initialize()
        myShift.initialize()
    }

    override fun toString(): String =
        "GuidedTransporter($name, state=$transporterState, at=${frontZone?.name ?: "unplaced"}, " +
                "length=$lengthInZones zones)"
}
