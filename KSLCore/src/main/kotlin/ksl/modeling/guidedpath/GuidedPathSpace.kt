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

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.exceptions.GuidedPathObstructionException
import ksl.modeling.guidedpath.internal.DeadlockDetector
import ksl.modeling.guidedpath.internal.MovementEngine
import ksl.modeling.guidedpath.rules.FIFOZoneContentionRule
import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc
import ksl.modeling.guidedpath.internal.ZoneInvariantChecker
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.KSLEvent
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simulation.ModelElement

/**
 * What a waiter on a transporter's journey is waiting *for*, which decides the queue that holds it.
 *
 * Three kinds, and they are genuinely different situations rather than three phases of one. The
 * first two are the passive paradigm's, and describe an entity that is not driving: it is standing
 * somewhere while a transporter comes for it, or it is aboard one. The third is the active
 * paradigm's, and describes the vehicle's own agent waiting for a leg its body is making -- which
 * may have nothing aboard at all, and so is neither of the first two.
 */
internal enum class MovementWait {

    /** Standing where it is, while a transporter travels to collect it. */
    AWAITING_PICKUP,

    /** Aboard a transporter that is carrying it. */
    RIDING,

    /** Driving a transporter, and waiting for the leg it is making to end. */
    DRIVING
}

/**
 * The runtime half of a guide path: it owns everything about the network that changes during a run.
 *
 * The network describes the guide path and never changes. This owns which transporter holds which
 * zone, resets all of it at the start of every replication, hosts the transporters, and reports how
 * congested the path is. Splitting the two is not tidiness. A spatial model and a model element are
 * both abstract classes, so one object cannot be both, and the split falls naturally along the line
 * between what is fixed and what is not: nothing about the guide path itself can differ between
 * replications, because the network has nothing to differ.
 *
 * A network belongs to one running system. A second system attaching to the same network would
 * share its zones and quietly corrupt both, so the attempt is refused rather than left to produce
 * an inexplicable run.
 *
 * ## Why this is its own class
 *
 * Everything here is about **space**: who holds which zone, who is blocked behind whom, how far and
 * how long anyone travelled. None of it is about *how a modeller asks for a vehicle*, and that is
 * the one thing the two subsystems built on it disagree about. The passive
 * [GuidedPathTransportSystem] has the entity hold a transporter and steer it; the active
 * `AgvSystem` has the entity state a need and a dispatcher decide. Both move vehicles over zones in
 * exactly the same way, and this is that way, named.
 *
 * Neither subsystem owns the other. Each composes this layer, so the physics has one home and a
 * per-carry statistic registered here is fed by whichever protocol delivered the load. A third
 * consumer -- a rail network, a stacker crane, an AS/RS aisle -- needs this and none of the passive
 * protocol, and can say so in its type.
 *
 * @param parent the containing model element
 * @param network the guide path to operate, already built
 * @param name a name for the system
 */
open class GuidedPathSpace @JvmOverloads constructor(
    parent: ModelElement,
    val network: GuidedPathNetwork,
    val zoneContentionRule: ZoneContentionRuleIfc = FIFOZoneContentionRule(),
    collectLinkStatistics: Boolean = false,
    collectZoneStatistics: Boolean = false,
    name: String? = null
) : ModelElement(parent, name) {

    init {
        network.attachTo(this.name)
        spatialModel = network
    }

    private val myTransporters = mutableListOf<GuidedTransporter>()

    /** The transporters on this guide path, in the order they were declared. */
    val transporters: List<GuidedTransporter>
        get() = myTransporters

    internal val engine: MovementEngine = MovementEngine(this)

    private val myInvariantChecker: ZoneInvariantChecker = ZoneInvariantChecker(this)

    /**
     * The instant whose finished state has not yet been audited, or NaN when nothing is owing.
     *
     * The guide path audits the instant *before* the one it is in, and it does so from inside its
     * own work rather than by being swept for. That is the whole of the mechanism: at the top of any
     * of the places [auditFinishedInstant] is called from, before that place has done anything, the
     * state on view is precisely the state the previous instant left behind -- which is the only
     * state worth asserting about, since within an instant a zero-delay hand-off legitimately
     * leaves a woken transporter on no waiting list at all.
     */
    private var myUnauditedInstant: Double = Double.NaN

    /**
     * Audits the instant that has just finished, if one has and if auditing is on.
     *
     * Called first thing in every place the guide path can be made to change, which is a closed set
     * and demonstrably so: every mutator on [Zone] and [GuidedTransporter] is internal to this
     * package, every call to one is in `MovementEngine` or in `placeAtInitialPosition`, and every
     * call into `MovementEngine` from outside the engine is one of the handful of sites below.
     * `AuditGateTest` holds that list to the source, so another cannot be added without either
     * gating it or failing.
     *
     * Not a scan, and deliberately not one. An audit that asked the executive to sweep it would pay
     * for a hook it does not need, would be re-run whenever any unrelated model element's condition
     * fired, and would walk the guide path at instants in which the guide path did nothing. This
     * runs once per instant in which there was something to audit, and when checking is off it is
     * one boolean test -- which is what lets it sit on paths that run millions of times.
     */
    private fun auditFinishedInstant() {
        if (!checkInvariants) return
        val owed = myUnauditedInstant
        myUnauditedInstant = time
        if (owed.isFinite() && time > owed) {
            myInvariantChecker.check(owed)
        }
    }

    /**
     * Whether the space-exclusivity invariants are checked at the end of every instant in which the
     * guide path did anything.
     *
     * Off by default, because the check walks every zone and every transporter and a model that is
     * correct pays for nothing. Tests turn it on: it is the standing proof that no transporter ever
     * shares space with another, ever covers a broken run of zones, or ever loses track of what it
     * holds.
     *
     * The initial value comes from the [CHECK_INVARIANTS_PROPERTY] system property, so that a whole
     * suite -- or a whole model tree being debugged -- can be checked without every system in it
     * being found and switched on by hand. Setting this explicitly still decides for itself.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var checkInvariants: Boolean = defaultCheckInvariants()
        set(value) {
            require(model.isNotRunning) {
                "Invariant checking cannot be switched while the model is running."
            }
            field = value
        }

    /**
     * Whether the guide path audits itself once, as each replication ends.
     *
     * On by default, and unlike [checkInvariants] it is meant to stay on. The two differ in cost by
     * the whole length of a run: the continuous check walks every zone at the end of every instant
     * the guide path took part in, while this walks them once per replication, which no model will
     * notice. What it buys is that a
     * corruption which produced plausible-looking output announces itself at the end of the
     * replication that caused it, rather than at some later replication or not at all.
     *
     * It audits what is true *while the replication is still running*, which is the state this hook
     * sees. It is emphatically not a check that everything has been tidied away: a replication may
     * legitimately end with a transporter blocked, a load aboard and a queue full of work. What must
     * hold is that the model's account of that state is self-consistent.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var auditAtReplicationEnd: Boolean = true

    /**
     * Whether the wait-for graph is walked when a transporter blocks.
     *
     * On by default. A guide path that deadlocks and says nothing is the failure mode the whole
     * subsystem exists to improve on, so the cost is accepted: the walk happens only when a
     * transporter blocks, which in a well-designed network is rare, and it is proportional to the
     * fleet rather than to the number of events.
     *
     * Turning it off buys throughput and gives up `G5`. A run that then deadlocks stops advancing
     * and finishes normally, with nothing but the end-of-replication warning to say so.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var deadlockDetectionEnabled: Boolean = true
        set(value) {
            require(model.isNotRunning) {
                "Deadlock detection cannot be switched while the model is running."
            }
            field = value
        }

    /**
     * Whether an idle transporter obstructing another ends the replication instead of being warned
     * about and counted.
     *
     * Off by default, and the asymmetry with deadlock is deliberate. A cycle cannot resolve itself,
     * so it is always an error. An obstruction can: dispatching the idle transporter clears it, and
     * the condition is judged from a single instant, so raising by default would fail models that
     * are perfectly sound. Set this when a study needs the obstruction treated as a design failure
     * rather than as a warning, and expect occasional false alarms in exchange for certainty.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var strictObstructionPolicy: Boolean = false
        set(value) {
            require(model.isNotRunning) {
                "The obstruction policy cannot be changed while the model is running."
            }
            field = value
        }

    private val myDetector: DeadlockDetector = DeadlockDetector(this)

    // ---- what the guide path costs the executive -----------------------------------------------
    //
    // A zone traversal is one event, so discretizing a layout finely to make an animation look
    // smooth buys that smoothness in events, and a modeler who picks zone size for the picture
    // rather than for the control granularity can make a model far slower without meaning to. The
    // guide says so; these two make it measurable, and their ratio is what catches the failure that
    // matters most -- a regression into repeated wake-ups, where a transporter is woken, refused,
    // and rescheduled over and over. That shows up as events per traversal climbing while the model
    // still gives the right answers.

    private val myNumZoneTraversals = Counter(this, name = "${this.name}:NumZoneTraversals")

    /** How many zones were entered, across the whole fleet. */
    val numZoneTraversals: CounterCIfc
        get() = myNumZoneTraversals

    private val myNumEventsScheduled = Counter(this, name = "${this.name}:NumEventsScheduled")

    /** How many events the guide path put on the calendar: traversals, rear releases, and retries. */
    val numEventsScheduled: CounterCIfc
        get() = myNumEventsScheduled

    private val myEventsPerTraversal = Response(this, name = "${this.name}:EventsPerZoneTraversal")

    /**
     * Events scheduled per zone entered, computed when the replication ends.
     *
     * One is the floor: a transporter that never waits for anything schedules a single traversal
     * for each zone it enters. Distance-based zone control adds a second event per traversal by
     * design and lands near two. Anything much above that is transporters being woken and refused,
     * which is a performance defect rather than a modelling choice.
     */
    val eventsPerZoneTraversal: ResponseCIfc
        get() = myEventsPerTraversal

    private val myNumDeadlocks = Counter(this, name = "${this.name}:NumDeadlocksDetected")

    /**
     * How many circular waits were found. At most one per replication, since finding one ends it.
     *
     * Counted anyway, and the reason is the parameter sweep: a study that catches the exception
     * around each replication and records the design point as infeasible needs something in the
     * output that says which points those were. Reading it off the counter beats keeping a tally
     * beside the run.
     */
    val numDeadlocksDetected: CounterCIfc
        get() = myNumDeadlocks

    private val myNumObstructions = Counter(this, name = "${this.name}:NumObstructionsDetected")

    /**
     * How many times a transporter was found blocked behind an idle one that will not move.
     *
     * Counted rather than only logged so that the condition appears in the standard report, where
     * an analyst will see it. A model that produces a positive count here has almost certainly
     * stopped moving somewhere, and the run that produced it should not be believed until the
     * count is explained.
     */
    val numObstructionsDetected: CounterCIfc
        get() = myNumObstructions

    /**
     * Examines a transporter that has just become blocked, and is the only place either condition
     * is looked for.
     *
     * A cycle can only come into existence when somebody enters the blocked state, so checking
     * there is both necessary and sufficient; checking on a timer or at every event would cost in
     * proportion to the event count and find nothing extra.
     *
     * @throws GuidedPathDeadlockException when the transporter lies on a circular wait
     * @throws GuidedPathObstructionException when it is behind an idle transporter and the strict
     *   policy is set
     */
    internal fun transporterBlocked(transporter: GuidedTransporter) {
        if (!deadlockDetectionEnabled) return
        val cycle = myDetector.findCycle(transporter)
        if (cycle != null) {
            myNumDeadlocks.increment()
            logger.error { cycle.toString() }
            throw GuidedPathDeadlockException(cycle)
        }
        val obstruction = myDetector.findObstruction(transporter) ?: return
        myNumObstructions.increment()
        if (strictObstructionPolicy) {
            logger.error { obstruction.toString() }
            throw GuidedPathObstructionException(obstruction)
        }
        logger.warn { obstruction.toString() }
    }

    private val myNumMoving = TWResponse(this, name = "${this.name}:NumTransportersMoving")

    /** How many transporters are travelling. */
    val numTransportersMoving: TWResponseCIfc
        get() = myNumMoving

    private val myNumBlocked = TWResponse(this, name = "${this.name}:NumTransportersBlocked")

    /** How many transporters cannot claim the space ahead of them. */
    val numTransportersBlocked: TWResponseCIfc
        get() = myNumBlocked

    private val myNumIdle = TWResponse(this, name = "${this.name}:NumTransportersIdle")

    /** How many transporters are standing still with nothing to do. */
    val numTransportersIdle: TWResponseCIfc
        get() = myNumIdle

    private val myZoneUtilization = TWResponse(this, name = "${this.name}:ZoneUtilization")

    /** The fraction of the guide path's zones that are covered by a transporter. */
    val zoneUtilization: TWResponseCIfc
        get() = myZoneUtilization

    // ---- opt-in detail statistics -------------------------------------------------------------
    //
    // A guide path of a thousand zones would register a thousand time-weighted responses if
    // occupancy were collected automatically, and every report and every output-database table
    // would carry them whether or not anyone asked. So the detail is off unless requested, while
    // the system-level aggregates above -- which are O(1) in network size and answer the first
    // question anybody asks about congestion -- are always there.
    //
    // Both flags are settable up to the moment the model runs, and either direction takes effect
    // immediately: switching one on registers its responses, switching it off removes them. That
    // works because the model only refuses to add or remove a model element while it is *running*,
    // so everything either flag does is legal right up to the first replication. Turning the detail
    // off therefore genuinely shrinks the report rather than merely stopping the numbers being
    // updated, which matters -- a response left registered but never written would appear in every
    // report and every database table with nothing in it, which is worse than either honest answer.

    private var myLinkCoverage: Map<Link, TWResponse> = emptyMap()
    private var myLinkUtilization: Map<Link, Response> = emptyMap()
    private var myIntersectionCoverage: Map<GuidedPathNetwork.Intersection, TWResponse> = emptyMap()
    private var myZoneCoverage: Map<Zone, TWResponse> = emptyMap()

    /**
     * Whether coverage is collected for each link and each intersection.
     *
     * Settable until the model runs. Setting it registers or removes the responses there and then,
     * so the flag and the report always agree.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var collectLinkStatistics: Boolean = false
        set(value) {
            require(model.isNotRunning) {
                "Link statistics cannot be switched while the model is running."
            }
            if (value == field) return
            if (value) {
                myLinkCoverage = network.links.associateWith {
                    TWResponse(this, name = "${this.name}:${it.name}:NumZonesCovered")
                }
                myLinkUtilization = network.links.associateWith {
                    Response(this, name = "${this.name}:${it.name}:Utilization")
                }
                // Named for the tier rather than just for the place. An intersection *is* a zone,
                // so with both flags on it would otherwise be registered twice under one name and
                // the model would refuse to build -- which is exactly what happened the first time
                // the two tiers were switched on together.
                myIntersectionCoverage = network.intersections.associateWith {
                    TWResponse(this, name = "${this.name}:${it.name}:IntersectionCovered")
                }
            } else {
                discard(myLinkCoverage.values)
                discard(myLinkUtilization.values)
                discard(myIntersectionCoverage.values)
                myLinkCoverage = emptyMap()
                myLinkUtilization = emptyMap()
                myIntersectionCoverage = emptyMap()
            }
            field = value
        }

    /**
     * Whether coverage is collected for every individual zone.
     *
     * The finest tier and the most expensive: one response per zone. Settable until the model runs,
     * in either direction, as [collectLinkStatistics] is.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var collectZoneStatistics: Boolean = false
        set(value) {
            require(model.isNotRunning) {
                "Zone statistics cannot be switched while the model is running."
            }
            if (value == field) return
            if (value) {
                myZoneCoverage = network.zones.associateWith {
                    TWResponse(this, name = "${this.name}:${it.name}:ZoneCovered")
                }
            } else {
                discard(myZoneCoverage.values)
                myZoneCoverage = emptyMap()
            }
            field = value
        }

    /**
     * Takes responses back out of the model, so that switching a tier off shrinks the report
     * instead of leaving empty columns in it. The name each held becomes free again, which is what
     * lets a tier be switched off and on again.
     */
    private fun discard(responses: Collection<ModelElement>) {
        for (response in responses) {
            model.removeFromModel(response)
        }
    }

    init {
        // Applied through the setters, so that constructing with a flag and setting it afterwards
        // go down exactly the same path and cannot drift apart.
        this.collectLinkStatistics = collectLinkStatistics
        this.collectZoneStatistics = collectZoneStatistics
    }

    /** Zones of each link covered by a transporter, when link statistics were asked for. */
    val linkCoverage: Map<Link, TWResponseCIfc>
        get() = myLinkCoverage

    /**
     * The fraction of each link's zones covered over the replication, computed when the replication
     * ends in the same way a conveyor computes its cell utilization.
     */
    val linkUtilization: Map<Link, ResponseCIfc>
        get() = myLinkUtilization

    /** Whether each intersection was covered, when link statistics were asked for. */
    val intersectionCoverage: Map<GuidedPathNetwork.Intersection, TWResponseCIfc>
        get() = myIntersectionCoverage

    /** Whether each individual zone was covered, when zone statistics were asked for. */
    val zoneCoverage: Map<Zone, TWResponseCIfc>
        get() = myZoneCoverage

    // ---- what each completed transport cost ----------------------------------------------------
    //
    // These are returned to the process in a GuidedTransportResult as well as accumulated here. The
    // duplication is deliberate: a modeler who wants per-entity outcomes gets them without
    // attaching an observer, and one who wants the fleet-level summary gets it without writing any
    // collection code at all.
    //
    // The first two are named for the INTERVAL they measure and not for the vehicle's state during
    // it. An approach includes time the vehicle spent blocked and time it spent disengaging from a
    // repositioning move, neither of which is "moving empty"; and a vehicle carrying one load while
    // going to collect another is moving loaded throughout its approach to the second. The state
    // question is answered instead by the vehicle's own `fracTimeMovingEmpty` and
    // `fracTimeTransporting`, which are time-weighted and mean exactly what they say at every
    // capacity. These two answer the protocol question, and always can.

    private val myApproachTime = Response(this, name = "${this.name}:ApproachTime")

    /**
     * Per delivered load: from the transporter being committed to it until the load is aboard,
     * excluding the loading delay.
     *
     * A protocol interval, not a physical state. Whether the vehicle was empty during it is a
     * separate question, answered by [GuidedTransporter.fracTimeMovingEmpty].
     */
    val approachTime: ResponseCIfc
        get() = myApproachTime

    private val myRideTime = Response(this, name = "${this.name}:RideTime")

    /**
     * Per delivered load: from the load being aboard until it is set down, excluding the unloading
     * delay.
     *
     * A protocol interval, as [approachTime] is. A vehicle may be carrying other loads throughout,
     * and this figure is about this one.
     */
    val rideTime: ResponseCIfc
        get() = myRideTime

    private val myTransportBlockedTime = Response(this, name = "${this.name}:TransportBlockedTime")

    /**
     * How much of a transport was spent unable to claim the space ahead. The quantity a free-path
     * model cannot produce at all, which is why it is reported per transport and not only as a
     * fraction of each transporter's time.
     */
    val transportBlockedTime: ResponseCIfc
        get() = myTransportBlockedTime

    private val myZonesTraversed = Response(this, name = "${this.name}:ZonesTraversedPerTransport")

    /** How many zones a loaded transporter crossed. */
    val zonesTraversedPerTransport: ResponseCIfc
        get() = myZonesTraversed

    private val myRouteLength = Response(this, name = "${this.name}:RouteLengthPerTransport")

    /** How far a loaded transporter travelled. */
    val routeLengthPerTransport: ResponseCIfc
        get() = myRouteLength

    /**
     * Records what one completed carry cost the guide path, whichever paradigm drove it.
     *
     * Deliberately takes five numbers rather than a `GuidedTransportResult`. These five are
     * properties of *one load's journey* -- how long it waited to be collected, how long it rode, how
     * much of that it spent unable to claim the space ahead, and how far over how many zones -- and
     * the traversal is the one thing both paradigms genuinely share. Taking the passive protocol's
     * result type here would make the active subsystem depend on a type belonging to the paradigm it
     * is an alternative to, in order to report a figure about the layer underneath both. So the seam
     * is paradigm-neutral and each caller assembles its own numbers.
     *
     * `transportTime` is **not** among them, and that is not an oversight. The two paradigms mean
     * different things by it -- request to set-down on one side, aboard to set-down on the other --
     * so each publishes its own, and one row that meant two things would be worse than two rows.
     */
    internal fun collectCarry(
        approachTime: Double,
        rideTime: Double,
        blockedTime: Double,
        zonesTraversed: Int,
        routeLength: Double
    ) {
        myApproachTime.value = approachTime
        myRideTime.value = rideTime
        myTransportBlockedTime.value = blockedTime
        myZonesTraversed.value = zonesTraversed.toDouble()
        myRouteLength.value = routeLength
    }

    // ---- animation -----------------------------------------------------------------------------

    private val myAnimationEmitter = GuidedPathAnimationEmitter(this)

    /** Emits a transporter's state change, doing nothing when no animation sink is active. */
    internal fun emitTransporterState(transporter: GuidedTransporter, state: TransporterState) {
        myAnimationEmitter.emitTransporterState(transporter, state)
    }

    /** Emits a transporter's arrival in a zone, doing nothing when no animation sink is active. */
    internal fun emitTransporterMoved(transporter: GuidedTransporter, zone: Zone) {
        myAnimationEmitter.emitTransporterMoved(transporter, zone)
    }

    /** Records that a transporter entered a zone while travelling. */
    internal fun countZoneTraversal() {
        myNumZoneTraversals.increment()
    }

    // ---- the three movement queues -------------------------------------------------------------
    //
    // A journey spans many events, so whoever is waiting on one cannot simply be delayed for it:
    // they are held here for the whole journey and woken when the transporter announces that it has
    // arrived. Three queues rather than one, split by *what the wait is*, which is the division
    // `Conveyor` makes between accessing, riding and exiting for the same reason: a single queue
    // holding three unrelated kinds of waiter can be told apart only by reading a suspension name
    // out of a trace, and its size answers no question anybody asks.
    //
    // All three report nothing, for the reason in `statisticalReportingForHoldQueues`.

    private val myAwaitingPickupHoldQ = HoldQueue(this, "${this.name}:AwaitingPickupHoldQ")

    /** Entities standing where they are while a transporter travels to collect them. */
    val awaitingPickupHoldQ: QueueCIfc<ProcessModel.Entity>
        get() = myAwaitingPickupHoldQ

    private val myRidingHoldQ = HoldQueue(this, "${this.name}:RidingHoldQ")

    /** Entities aboard a transporter that is carrying them. */
    val ridingHoldQ: QueueCIfc<ProcessModel.Entity>
        get() = myRidingHoldQ

    private val myDrivingHoldQ = HoldQueue(this, "${this.name}:DrivingHoldQ")

    /**
     * Entities waiting on a transporter they are *driving* rather than riding.
     *
     * Empty under the passive paradigm, where nobody drives: a transporter fetches and carries on
     * an entity's behalf and the entity is in one of the two queues above. It is the active
     * paradigm's vehicle agents that wait here, for their own body to finish a leg -- including a
     * leg with nothing aboard, which is a wait neither of the other two describes.
     */
    val drivingHoldQ: QueueCIfc<ProcessModel.Entity>
        get() = myDrivingHoldQ

    init {
        statisticalReportingForHoldQueues(false)
    }

    /**
     * Switches reporting for the three movement queues. **Off by default.**
     *
     * A hold queue is how a suspended entity is found again; it is not a waiting line, and letting
     * it double as the statistic conflates a mechanism with a measurement. Left on, `RidingHoldQ`
     * would put a row on the report whose "time in queue" is the mean length of a loaded move and
     * whose "number in queue" is a count of moving carts -- read by anybody scanning the report as
     * a line of entities waiting for something. Both quantities are already reported properly and
     * separately, by [approachTime] and [rideTime], which is what a modeller should read.
     *
     * `Conveyor` makes the same call for the same three-way split, and turning these on is for
     * debugging a model that has stopped moving, not for analysis.
     *
     * @param option true means the three movement queues appear on the summary report
     */
    fun statisticalReportingForHoldQueues(option: Boolean) {
        for (q in listOf(myAwaitingPickupHoldQ, myRidingHoldQ, myDrivingHoldQ)) {
            q.waitTimeStatOption = option
            q.defaultReportingOption = option
        }
    }

    /** Which movement queue a waiter belongs in, and therefore what its wait is called. */
    internal fun holdQueueFor(wait: MovementWait): HoldQueue = when (wait) {
        MovementWait.AWAITING_PICKUP -> myAwaitingPickupHoldQ
        MovementWait.RIDING -> myRidingHoldQ
        MovementWait.DRIVING -> myDrivingHoldQ
    }

    internal fun addTransporter(transporter: GuidedTransporter) {
        require(model.isNotRunning) {
            "A transporter cannot be added while the model is running."
        }
        myTransporters.add(transporter)
    }

    /**
     * Wakes whoever was waiting on a transporter's journey.
     *
     * Called by the transporter itself when it arrives, rather than through a listener registered
     * from here. Registering one during `addTransporter` would mean reaching back into a
     * transporter that is still running its own constructor, whose properties are not all in place
     * yet -- an initialisation order that happens to work only while the declarations stay in the
     * order they are in today.
     */
    internal fun transporterArrived(transporter: GuidedTransporter) {
        val wait = transporter.journeyWait ?: return
        transporter.journeyWait = null
        wait.queue.removeAndResume(wait.waiter)
    }

    /**
     * Wakes whoever was waiting on a journey that a movement gate stopped part way.
     *
     * The same resumption as an arrival, and deliberately not the same thing: the transporter is
     * halted on a zone that is not where it was going, still holding its zones, with its route
     * abandoned. Whoever was waiting has to be told so that it can decide what to do -- which is why
     * this exists at all, and why a gate that refuses is not the same as a transporter that vanishes.
     *
     * A waiter woken this way must not assume it has arrived. [GuidedTransporter.isHalted] is what
     * distinguishes the two, and it stays set until the transporter is given a new journey.
     */
    internal fun transporterInterrupted(transporter: GuidedTransporter) {
        val wait = transporter.journeyWait ?: return
        transporter.journeyWait = null
        wait.queue.removeAndResume(wait.waiter)
    }

    /**
     * The transporters standing still right now because this one is in their way.
     *
     * Answered exactly rather than guessed: a blocked transporter records the zone or the link it is
     * waiting for, so being in the way is a fact about what this transporter holds, not an inference
     * from where it happens to be.
     *
     * What it is for is a policy that has to decide whether a stopped vehicle is worth moving. A
     * vehicle broken down on a spur nobody uses can be repaired where it stands; the same vehicle
     * across a main aisle cannot, and this is the difference between the two.
     */
    fun transportersHeldUpBy(transporter: GuidedTransporter): List<GuidedTransporter> {
        val held = transporter.heldZones
        if (held.isEmpty()) return emptyList()
        val heldLinks = held.filterIsInstance<LinkZone>().map { it.link }.toSet()
        return myTransporters.filter { other ->
            other !== transporter && other.transporterState == TransporterState.BLOCKED &&
                    (other.awaitedZone?.let { it in held } == true ||
                            other.awaitedLink?.let { it in heldLinks } == true)
        }
    }

    /**
     * Sets a transporter travelling and returns whether it actually has to go anywhere.
     *
     * Returning the queue rather than a flag is what keeps the two ends of a wait in agreement.
     * The caller has to be told whether to suspend at all, and it also has to suspend in the queue
     * this journey will be resumed from; making that one answer means a caller cannot suspend in
     * one queue while the arrival wakes it from another.
     *
     * @param waiter who to suspend for the journey: the entity being fetched or carried under the
     *   passive paradigm, and the vehicle's own agent under the active one
     * @param wait what kind of wait it is, which decides the queue
     * @return the queue to suspend the waiter in, or null when the transporter was already there
     */
    /**
     * Starts a transporter that a [TransporterMovementGateIfc] halted at a zone boundary.
     *
     * Nothing else will: a halted transporter has nothing scheduled and nobody waiting on it, which
     * is the point of halting rather than blocking. Whatever refused it passage is what decides it
     * may go on, and this is how it says so. Harmless on a transporter that is not halted.
     */
    fun resumeHaltedTransporter(transporter: GuidedTransporter) {
        require(transporter.system === this) {
            "Transporter (${transporter.name}) is not on guide path (${this.name})."
        }
        auditFinishedInstant()
        engine.resumeHalted(transporter)
    }

    internal fun beginJourney(
        transporter: GuidedTransporter,
        destinationName: String,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity,
        wait: MovementWait
    ): HoldQueue? {
        if (!startMove(transporter, destinationName, purpose)) return null
        val queue = holdQueueFor(wait)
        transporter.journeyWait = JourneyWait(waiter, queue)
        return queue
    }

    /**
     * Turns a declared placement into the zones a transporter covers, rear first.
     *
     * A transporter longer than one zone extends backwards from where it is placed, because the
     * zone named is where its front is. Backwards means against the direction of travel on the
     * link, so a transporter placed on a link is ready to move forward off it.
     */
    internal fun resolvePlacement(
        transporter: GuidedTransporter,
        placement: TransporterPlacement
    ): List<Zone> = when (placement) {
        is TransporterPlacement.At -> {
            val intersection = network.location(placement.locationName)
                ?: throw GuidedPathNetworkException(
                    "Transporter (${transporter.name}) is placed at (${placement.locationName}), " +
                            "which is neither an intersection nor a station alias of network " +
                            "${network.name}."
                )
            if (transporter.lengthInZones > 1) {
                throw GuidedPathNetworkException.multiZoneTransporterAtIntersection(
                    transporter.name, transporter.lengthInZones, intersection.name
                )
            }
            listOf(intersection.zone)
        }

        is TransporterPlacement.OnZone -> {
            val zone = network.zone(placement.zoneName)
                ?: throw GuidedPathNetworkException(
                    "Transporter (${transporter.name}) is placed on zone (${placement.zoneName}), " +
                            "which network ${network.name} does not have."
                )
            when (zone) {
                is IntersectionZone -> {
                    if (transporter.lengthInZones > 1) {
                        throw GuidedPathNetworkException.multiZoneTransporterAtIntersection(
                            transporter.name, transporter.lengthInZones, zone.intersection.name
                        )
                    }
                    listOf(zone)
                }

                is LinkZone -> {
                    val link = zone.link
                    if (transporter.lengthInZones > link.numZones) {
                        throw GuidedPathNetworkException.transporterTooLongForPlacement(
                            transporter.name, transporter.lengthInZones, link.name, link.numZones
                        )
                    }
                    val front = zone.positionOnLink
                    if (front < transporter.lengthInZones) {
                        throw GuidedPathNetworkException(
                            "Transporter (${transporter.name}) covers ${transporter.lengthInZones} " +
                                    "zones and was placed with its front at (${zone.name}), which is " +
                                    "only position $front along link (${link.name}). Its rear would " +
                                    "hang off the start of the link. Place its front further along."
                        )
                    }
                    (front - transporter.lengthInZones until front).map { link.zones[it] }
                }
            }
        }
    }

    /** Where a transporter is, expressed as a location the rest of the library understands. */
    internal fun locationOf(transporter: GuidedTransporter): LocationIfc {
        val front = transporter.frontZone
        return when (front) {
            is IntersectionZone -> front.intersection
            is LinkZone -> front.link.endIntersection
            null -> network.defaultLocation
        }
    }

    /**
     * Starts a transporter travelling toward a destination.
     *
     * @return true when a movement was started, false when it is already there
     */
    internal fun startMove(
        transporter: GuidedTransporter,
        destinationName: String,
        purpose: MovePurpose
    ): Boolean {
        val destination = network.requireLocation(destinationName)
        // A transporter given somewhere new to go is no longer halted, whatever stopped it before.
        // Clearing it here rather than at each call site is what keeps the flag meaning one thing:
        // stopped at a boundary with nothing scheduled and nowhere it is going.
        transporter.clearHalt()
        auditFinishedInstant()
        return engine.startMove(transporter, destination, purpose)
    }

    /**
     * Keeps the fleet-level counts current. Called whenever a transporter changes what it is doing.
     *
     * On the hot path, and by a wide margin the hottest thing in the subsystem: the movement engine
     * calls it at seven points, among them the completion of every zone traversal. So what it does
     * *not* do matters as much as what it does.
     *
     * It does not walk the zones. How many zones are covered is the number this needs, and it is
     * asked of the transporters rather than of the network -- a loop over a fleet of twenty instead
     * of a loop over four hundred zones, folded into the loop that was being run anyway. The two
     * are the same number, and not by coincidence: `checkCoverageIsConserved` exists to assert
     * exactly that equality, continuously under the test suite and once per replication everywhere
     * else. This is that invariant being spent rather than merely checked.
     *
     * The zones are walked only when somebody asked for per-zone, per-intersection or per-link
     * detail, which is off by default. Walking them unconditionally to maintain one ratio cost
     * roughly half of the reference benchmark's entire runtime.
     */
    internal fun refreshFleetCounts() {
        var moving = 0
        var blocked = 0
        var idle = 0
        var covered = 0
        var byVehicle = 0
        var byOccupier = 0
        var byPopulation = 0
        for (t in myTransporters) {
            when {
                t.transporterState == TransporterState.BLOCKED -> {
                    blocked++
                    // Which of the three things now in a vehicle's way is in this one's. Bucketed
                    // here because this loop is running anyway and each transporter already records
                    // what it awaits; a transporter held up by a link is held up by the vehicles on
                    // it, so it counts as the first.
                    val zone = t.awaitedZone
                    when {
                        t.awaitedLink != null -> byVehicle++
                        zone == null -> Unit
                        zone.holder is GuidedTransporter -> byVehicle++
                        zone.holder != null -> byOccupier++
                        zone.numPresent > 0 -> byPopulation++
                        else -> byOccupier++   // promised to an occupier, still draining
                    }
                }

                t.isMoving -> moving++
                else -> idle++
            }
            covered += t.coveredZones.size
        }
        myNumMoving.value = moving.toDouble()
        myNumBlocked.value = blocked.toDouble()
        myNumIdle.value = idle.toDouble()
        myNumBlockedByVehicle.value = byVehicle.toDouble()
        myNumBlockedByOccupier.value = byOccupier.toDouble()
        myNumBlockedByPopulation.value = byPopulation.toDouble()
        myZoneUtilization.value = covered.toDouble() / network.zones.size
        if (collectZoneStatistics || collectLinkStatistics) {
            refreshZoneDetail()
        }
    }

    /**
     * Writes the per-zone, per-intersection and per-link coverage responses.
     *
     * Separate from [refreshFleetCounts] so that the walk it needs is paid for only by the models
     * that asked for the detail. One walk serves all three, so having asked for any of them costs
     * the map lookups and nothing more.
     */
    private fun refreshZoneDetail() {
        val perLink = if (collectLinkStatistics) HashMap<Link, Int>(network.links.size) else null
        for (z in network.zones) {
            val isCovered = z.isCovered
            myZoneCoverage[z]?.value = if (isCovered) 1.0 else 0.0
            when (z) {
                is LinkZone -> if (perLink != null && isCovered) {
                    perLink[z.link] = (perLink[z.link] ?: 0) + 1
                }

                is IntersectionZone ->
                    myIntersectionCoverage[z.intersection]?.value = if (isCovered) 1.0 else 0.0
            }
        }
        if (perLink != null) {
            for ((link, response) in myLinkCoverage) {
                response.value = (perLink[link] ?: 0).toDouble()
            }
        }
    }

    // ---- event scheduling ---------------------------------------------------------------------
    //
    // The engine decides what happens and when; the scheduling lives here because a model element
    // is what the executive will accept events from. Each transporter has at most one traversal in
    // flight, so a traversal event needs no bookkeeping beyond the transporter and the zone.

    private inner class TraversalAction : EventActionIfc<Pair<GuidedTransporter, Zone>> {
        override fun action(event: KSLEvent<Pair<GuidedTransporter, Zone>>) {
            auditFinishedInstant()
            val (transporter, zone) = event.message!!
            engine.endZoneTraversal(transporter, zone)
        }
    }

    private inner class ClaimRetryAction : EventActionIfc<GuidedTransporter> {
        override fun action(event: KSLEvent<GuidedTransporter>) {
            auditFinishedInstant()
            engine.retryClaim(event.message!!)
        }
    }

    private inner class RearReleaseAction : EventActionIfc<GuidedTransporter> {
        override fun action(event: KSLEvent<GuidedTransporter>) {
            auditFinishedInstant()
            engine.releaseRearAfterDistance(event.message!!)
        }
    }

    private val myTraversalAction = TraversalAction()
    private val myClaimRetryAction = ClaimRetryAction()
    private val myRearReleaseAction = RearReleaseAction()

    /**
     * Admits one occupant to a zone, or refuses because a vehicle holds it.
     *
     * The population's half of the exclusion rule of [Zone.admit]. Nothing decides *whether* to
     * admit here -- how many is too many is a modelling statement and belongs to whatever governs
     * the population -- this only reports whether the space is free of vehicles to be entered.
     *
     * @return true when the occupant is now present in the zone
     */
    internal fun admitToZone(zone: Zone): Boolean {
        require(zone in network.zones) {
            "Zone (${zone.name}) is not on guide path (${this.name})."
        }
        auditFinishedInstant()
        return zone.admit()
    }

    /**
     * Releases one occupant from a zone, and wakes a vehicle if that emptied it.
     *
     * Paired with the scheduling here rather than left to the zone, exactly as
     * `MovementEngine.releaseZone` is: the zone decides *who* should be woken, and the space is
     * what the executive accepts events from, so it does the waking. Keeping the two together in
     * one call is what stops a departure from silently leaving a vehicle waiting for a zone that
     * is now empty.
     */
    internal fun departFromZone(zone: Zone) {
        require(zone in network.zones) {
            "Zone (${zone.name}) is not on guide path (${this.name})."
        }
        auditFinishedInstant()
        handOver(zone.depart(zoneContentionRule))
    }

    /**
     * Tells whoever a zone has just been offered to that they may take it.
     *
     * The one place the two kinds of handover are told apart, and the reason `Zone` answers a
     * [ZoneHolderIfc] rather than a transporter. A woken vehicle retries a claim it was refused; a
     * granted request takes a zone that has finished draining. Both are scheduled rather than done
     * here, so that their order against everything else at that instant is explicit.
     */
    internal fun handOver(offeredTo: ZoneHolderIfc?) {
        // Nothing can be handed over once the replication has ended: there is nobody left to wake,
        // no time left to wake them in -- the executive refuses the event outright -- and the state
        // this would hand over is about to be thrown away by initialize().
        //
        // This is reachable rather than theoretical. The end of a replication terminates every
        // suspended entity, and an entity terminated while holding an aisle gives that aisle back,
        // which funnels through here and finds whatever vehicle was waiting for it. Guarding the
        // one funnel rather than each of its three callers is why the funnel exists.
        if (executive.isEnded) return
        when (offeredTo) {
            null -> Unit
            is GuidedTransporter -> scheduleClaimRetry(offeredTo)
            // Anything else was offered the zone *because it holds a reservation on it*, and the
            // reservation carries the way to tell it. So there is no type switch to keep up to
            // date and no third kind of holder to enumerate; scheduleZoneGrant asserts that the
            // reservation is really there, which is the condition that makes this arm sound.
            else -> scheduleZoneGrant(offeredTo)
        }
    }

    // ---- general occupancy: a holder that is not a vehicle -------------------------------------
    //
    // The space owns these rather than the holder, and for the same reason the resource layer owns
    // allocations rather than entities: exclusivity can only be guaranteed if nothing outside can
    // mint a claim on the space. A holder asks; the space decides, records and schedules.
    //
    // Keyed on the interface rather than on a class, which is what lets the cast of holders be
    // decided at run time. A crew, a spill, a picker entity made by an arrival process -- none of
    // them can be enumerated before the run, and none of them needs to be: what a holder supplies
    // is a name and an awaited zone, and the space supplies everything else. These two maps are
    // the single owner of who is waiting and who holds, and nothing outside keeps a second copy.

    /**
     * Numbers the requests, so that the escape rule on `ZoneClosureIfc` has a strict order to work
     * with. Simulated time will not do: two closures asked for in the same instant still have to be
     * ordered, and in the case that found this they were.
     */
    private var myNextRequestSequence = 0L

    private val myZoneRequests = mutableMapOf<ZoneHolderIfc, ZoneRequest>()
    private val myZoneAllocations = mutableMapOf<ZoneHolderIfc, ZoneAllocation>()

    /** What this holder has asked for and not yet been given, or null. */
    fun requestFor(holder: ZoneHolderIfc): ZoneRequest? = myZoneRequests[holder]

    /** The space this holder currently holds, or null when it holds none. */
    fun allocationFor(holder: ZoneHolderIfc): ZoneAllocation? = myZoneAllocations[holder]

    /** True while space is draining for this holder. */
    fun isWaitingForZones(holder: ZoneHolderIfc): Boolean = myZoneRequests.containsKey(holder)

    /** True while this holder holds guide-path space. */
    fun isHoldingZones(holder: ZoneHolderIfc): Boolean = myZoneAllocations.containsKey(holder)

    /**
     * Asks for a zone, which closes to new traffic at once and is held as soon as it has drained.
     *
     * Returns without waiting, whether or not the zone was free. What the zone does from this
     * instant is refuse every new claim and every new admission; what was already in it finishes
     * and leaves in its own time. [ZoneHoldActionIfc.holdBegan] fires when the hold actually
     * begins, which is this same instant when the zone was already empty.
     *
     * @param holder who is taking the space -- anything at all that can be named and that says
     *   what it is waiting for, which for a holder that never queues is nothing
     * @param zone the zone to take, which must be on this guide path
     * @param action told when the hold begins and when it ends
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun requestZone(holder: ZoneHolderIfc, zone: Zone, action: ZoneHoldActionIfc): ZoneRequest =
        requestZones(holder, listOf(zone), action)

    /**
     * Asks for a set of zones, which all close to new traffic at once and are held **together**.
     *
     * All or nothing, and that is a rule rather than a convenience. Taking the zones one by one as
     * they drain would let the holder hold part of a region while waiting for the rest, and a
     * vehicle inside the region could then be waiting for a zone the holder holds while the holder
     * waits for the zone the vehicle is standing in. That is a deadlock, and an invisible one: a
     * holder that never queues has no [ZoneHolderIfc.awaitedZone], so the wait-for graph has no
     * edge to close a cycle with and the detector would never report it. Holding nothing until
     * every zone has drained keeps such a holder a sink, which makes the deadlock impossible
     * rather than undetectable.
     *
     * Traffic already inside the region is let out rather than trapped -- see `ZoneClosureIfc` --
     * which is what makes the drain terminate however busy the region is. The cost is that a
     * closure over busy space begins later, and that delay is measured rather than hidden.
     *
     * The extent is chosen per occurrence, at run time, and the sources cost nothing: a link's
     * zones by name, a junction's zone, a zone at a station, or a sample drawn from the network.
     *
     * @param holder who is taking the space
     * @param zones the zones to take, all on this guide path, distinct, at least one
     * @param action told when the hold begins and when it ends
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun requestZones(
        holder: ZoneHolderIfc,
        zones: List<Zone>,
        action: ZoneHoldActionIfc
    ): ZoneRequest {
        validateRequest(holder, zones)
        firstPromisedZone(zones)?.let { require(false) { overlapMessage(it, holder) } }
        return requestSpace(holder, zones, Double.NaN, action)
    }

    /**
     * Asks for a set of zones, and answers **null** when some zone of it is already promised.
     *
     * [requestZones] with the one refusable condition turned into an answer. A zone carries one
     * promise at a time, so two holders cannot queue for the same zone, and a model whose closures
     * land where they land -- spills, most obviously -- has to say what an overlap means. This is
     * how it says it, in one call that cannot be got wrong.
     *
     * The hand-written alternative is the reason this exists. The test that matters is a *promise*,
     * not a hold: asking for a zone another holder already **holds** is perfectly ordinary and
     * simply waits for the hold to end. A guard written by hand tends to test the hold as well,
     * and then refuses closures that would have worked.
     *
     * ```
     * // A spill landing where one is already being dealt with is part of that spill.
     * val request = space.tryRequestZones(crew, extent, action)
     * if (request == null) {
     *     absorbed.increment()
     *     return
     * }
     * ```
     *
     * Everything else a request must satisfy still raises: an empty set, a repeated zone, a zone of
     * another guide path, or a holder that already has a request are programming errors rather than
     * conditions of the guide path, and answering null to those would hide a defect.
     *
     * One zone is `tryRequestZones(holder, listOf(zone), action)`; there is no separate verb for it,
     * because the answer a modeller has to handle is what matters here rather than the spelling.
     *
     * @param holder who is taking the space
     * @param zones the zones to take, all on this guide path, distinct, at least one
     * @param action told when the hold begins and when it ends
     * @return the request, or null when some zone of the set is already promised to another holder
     */
    fun tryRequestZones(
        holder: ZoneHolderIfc,
        zones: List<Zone>,
        action: ZoneHoldActionIfc
    ): ZoneRequest? {
        validateRequest(holder, zones)
        if (firstPromisedZone(zones) != null) return null
        return requestSpace(holder, zones, Double.NaN, action)
    }

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
     * @param holder who is taking the space
     * @param zone the zone to take, which must be on this guide path
     * @param duration how long to hold it once the hold begins, strictly positive
     * @param action told when the hold begins and when the clock gives it back
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun holdZoneFor(
        holder: ZoneHolderIfc,
        zone: Zone,
        duration: Double,
        action: ZoneHoldActionIfc
    ): ZoneRequest {
        require(duration > 0.0) {
            "Holder (${holder.name}) was asked to hold zone (${zone.name}) for $duration, which " +
                    "is not a duration. To take a zone until told otherwise, use requestZone."
        }
        return holdZonesFor(holder, listOf(zone), duration, action)
    }

    /**
     * Takes a set of zones together for a stated duration, and gives them back without being asked.
     *
     * [requestZones] for the all-or-nothing rule, [holdZoneFor] for why the duration runs from the
     * instant the hold begins rather than from the request.
     *
     * @param holder who is taking the space
     * @param zones the zones to take, all on this guide path, distinct, at least one
     * @param duration how long to hold them once the hold begins, strictly positive
     * @param action told when the hold begins and when the clock gives it back
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun holdZonesFor(
        holder: ZoneHolderIfc,
        zones: List<Zone>,
        duration: Double,
        action: ZoneHoldActionIfc
    ): ZoneRequest {
        require(duration > 0.0) {
            "Holder (${holder.name}) was asked to hold ${zones.size} zone(s) for $duration, which " +
                    "is not a duration. To take space until told otherwise, use requestZones."
        }
        validateRequest(holder, zones)
        firstPromisedZone(zones)?.let { require(false) { overlapMessage(it, holder) } }
        return requestSpace(holder, zones, duration, action)
    }

    /**
     * Takes a set of zones for a stated duration, and answers **null** when some zone of it is
     * already promised.
     *
     * [tryRequestZones] for why this exists and what null means, [holdZoneFor] for why the duration
     * runs from the instant the hold begins rather than from the request.
     *
     * @param holder who is taking the space
     * @param zones the zones to take, all on this guide path, distinct, at least one
     * @param duration how long to hold them once the hold begins, strictly positive
     * @param action told when the hold begins and when the clock gives it back
     * @return the request, or null when some zone of the set is already promised to another holder
     */
    fun tryHoldZonesFor(
        holder: ZoneHolderIfc,
        zones: List<Zone>,
        duration: Double,
        action: ZoneHoldActionIfc
    ): ZoneRequest? {
        require(duration > 0.0) {
            "Holder (${holder.name}) was asked to hold ${zones.size} zone(s) for $duration, which " +
                    "is not a duration. To take space until told otherwise, use tryRequestZones."
        }
        validateRequest(holder, zones)
        if (firstPromisedZone(zones) != null) return null
        return requestSpace(holder, zones, duration, action)
    }

    private val myNumBlockedByVehicle =
        TWResponse(this, name = "${this.name}:NumBlockedByVehicle")
    private val myNumBlockedByOccupier =
        TWResponse(this, name = "${this.name}:NumBlockedByOccupier")
    private val myNumBlockedByPopulation =
        TWResponse(this, name = "${this.name}:NumBlockedByPopulation")

    /**
     * Vehicle blocked time, decomposed by what was in the way: another vehicle, an occupier, or a
     * population.
     *
     * **This split is what makes the whole construct validatable.** A model with no spills, no
     * closures and no picking interference must still match observed throughput, so that time is
     * fitted into inflated task times or a depressed velocity -- the model then matches the
     * aggregate and is wrong about the mechanism, and will give bad advice about any change that
     * alters the obstruction rate, which is the change a study is usually commissioned to evaluate.
     * Three separately observable quantities turn one fitted fudge into a testable claim.
     *
     * Time-weighted counts, so each one's time-average is the mean number of vehicles held up by
     * that cause, and the three sum to [numTransportersBlocked]. On the space rather than on the
     * transporters, because three responses per vehicle would multiply the response count of every
     * fleet to report what three responses per guide path report just as well.
     */
    val numBlockedByVehicle: TWResponseCIfc
        get() = myNumBlockedByVehicle

    /** Vehicles held up by an occupier: see [numBlockedByVehicle]. */
    val numBlockedByOccupier: TWResponseCIfc
        get() = myNumBlockedByOccupier

    /** Vehicles held up by a zone's population: see [numBlockedByVehicle]. */
    val numBlockedByPopulation: TWResponseCIfc
        get() = myNumBlockedByPopulation

    private var myClosedZoneCount = 0
    private val myNumZonesClosed = TWResponse(this, name = "${this.name}:NumZonesClosed")

    /**
     * How many zones are held by something other than a vehicle.
     *
     * The statistic that makes the whole construct validatable, and the reason it is here rather
     * than on the zones: there are thousands of zones and a handful of occupiers, so this is one
     * response for the space instead of one per zone. Its time-average is the mean amount of guide
     * path closed to traffic over the run, which is exactly the quantity a model without spills or
     * closures has to hide inside inflated task times.
     *
     * Separate from [zoneUtilization], which counts vehicle bodies. Three different things can now
     * make a zone unavailable -- a vehicle, an occupier, a population -- and collapsing them into
     * one number would lose the decomposition that this work exists to expose.
     */
    val numZonesClosed: TWResponseCIfc
        get() = myNumZonesClosed

    private val myNumWaitingForZones =
        TWResponse(this, name = "${this.name}:NumWaitingForZones")

    /**
     * How many holders are waiting for guide-path space to drain.
     *
     * The cost of draining rather than evicting, measured. A closure that is wanted *now* and
     * begins late because an aisle was busy is a real effect on whatever the holder represents, and
     * it is invisible unless it is counted. Its time-average is the mean number of closures pending
     * over the run.
     *
     * Here rather than on the holders, and that is the point of this whole construct rather than a
     * detail of it: a model may make as many holders as the run turns out to need -- an arrival
     * stream of spills, a picker entity per rack face -- so a response per holder would be a
     * response count that nobody can state before the run. Four numbers describe any model.
     */
    val numWaitingForZones: TWResponseCIfc
        get() = myNumWaitingForZones

    private val myTimeToCloseZones = Response(this, name = "${this.name}:TimeToCloseZones")

    /**
     * How long each request waited for its space to drain. Zero when the space was already free.
     *
     * An observation per request, which is why pooling it across holders loses nothing: a mean
     * drain delay over every closure in the run is the quantity a modeller wants, and one holder's
     * share of it is [ZoneAllocation.timeToEngage] on that holder's own allocations.
     */
    val timeToCloseZones: ResponseCIfc
        get() = myTimeToCloseZones

    private val myNumZoneEngagements = Counter(this, name = "${this.name}:NumZoneEngagements")

    /** How many times guide-path space was taken by something that is not a vehicle. */
    val numZoneEngagements: CounterCIfc
        get() = myNumZoneEngagements

    /**
     * Closes zones for a holder, and grants them at once when there was nothing to drain.
     *
     * The zones refuse every new claim and every new admission from this instant. What was already
     * in them leaves in its own time, and the grant follows through the same handover that wakes a
     * waiting vehicle -- which is the point of routing both through one place.
     *
     * **One request per holder.** A holder is the identity of a closure, so two overlapping
     * closures are two holders -- which costs nothing, because a holder is whatever implements
     * [ZoneHolderIfc] and a model may make as many as the run turns out to need.
     */
    /**
     * The zone of this set that is already promised to somebody else, or null when none is.
     *
     * Public because it is the one condition a model may legitimately have to react to, and the
     * hand-written version of it is easy to get wrong: the interesting test is a *promise*, not a
     * hold. Asking for a zone another holder already holds is allowed and simply waits for the
     * hold to end. [tryRequestZones] and [tryHoldZonesFor] are this test and the request made
     * together, and are the better way to use it; this is here for a model that wants to report
     * the conflict rather than react to it.
     *
     * @param zones the zones a closure would cover
     * @return the first zone already promised, or null when the whole set could be asked for
     */
    fun firstPromisedZone(zones: List<Zone>): Zone? = zones.firstOrNull { it.closingFor != null }

    /**
     * Everything a request must satisfy regardless of whether an overlap refuses it or answers null.
     *
     * These are all programming errors rather than conditions of the guide path -- a malformed set,
     * a foreign zone, a holder that already has a request -- so they raise in both forms of the
     * verb. Only the overlap is a condition, and only the overlap is what the two forms differ on.
     */
    private fun validateRequest(holder: ZoneHolderIfc, zones: List<Zone>) {
        require(zones.isNotEmpty()) {
            "Holder (${holder.name}) asked for no zones at all."
        }
        require(zones.distinct().size == zones.size) {
            "Holder (${holder.name}) asked for the same zone twice: " +
                    zones.joinToString { it.name }
        }
        for (zone in zones) {
            require(zone in network.zones) {
                "Zone (${zone.name}) is not on guide path (${this.name})."
            }
        }
        check(!isWaitingForZones(holder) && !isHoldingZones(holder)) {
            "Holder (${holder.name}) already " +
                    (if (isHoldingZones(holder)) "holds" else "has asked for") + " space on guide " +
                    "path (${this.name}). One request at a time: give it back before asking for more."
        }
    }

    private fun overlapMessage(zone: Zone, holder: ZoneHolderIfc): String =
        "Zone (${zone.name}) is already promised to holder " +
                "(${zone.closingFor?.name ?: "no one"}), which is still waiting for it to drain, " +
                "so holder (${holder.name}) cannot be promised it as well. A zone carries one " +
                "promise at a time. Note that this is not the same as asking for a zone somebody " +
                "already *holds*: that is allowed and simply waits for the hold to end. What " +
                "cannot be expressed is two holders queued for the same zone. A model whose " +
                "closures can land on the same zone -- spills at random locations, most obviously " +
                "-- has to say what an overlap means: absorbed into the closure already there, " +
                "deferred until it ends, or placed elsewhere. Use tryRequestZones or " +
                "tryHoldZonesFor to be answered null instead of refused."

    /**
     * Makes the reservation, having already established that it may be made.
     *
     * Split from the checks because the overlap test has to happen **before anything is reserved**,
     * and that is the point rather than tidiness: the reservations are made in a loop, so a failure
     * part way along would leave some zones of the set closed for a request that never came into
     * being -- closed to traffic for the rest of the replication with nothing holding them and
     * nothing coming to release them. A refusal has to leave the guide path exactly as it found it.
     */
    private fun requestSpace(
        holder: ZoneHolderIfc,
        zones: List<Zone>,
        holdFor: Double,
        action: ZoneHoldActionIfc
    ): ZoneRequest {
        auditFinishedInstant()
        val request = ZoneRequest(holder, zones, time, holdFor, myNextRequestSequence++, action)
        myZoneRequests[holder] = request
        myNumWaitingForZones.value = myZoneRequests.size.toDouble()
        // A holder whose lifetime somebody else ends needs a back-pointer from here, or space it
        // asked for would never be given back. Told at the request rather than at the grant: a
        // holder killed while its aisle is still draining holds nothing yet, and the reservation it
        // leaves behind would close that aisle for the rest of the replication. It records which
        // guide path to ask and nothing else -- what is held stays this map's.
        (holder as? ZoneHolderRecordIfc)?.zoneSpaceEngaged(this)
        for (zone in zones) {
            zone.closeFor(request)
        }
        // Nothing to drain: the grant is this instant, and takes the ordinary path rather than a
        // shortcut, so that an immediate grant and a grant after a drain are the same code.
        if (request.isDrained) {
            grantZonesTo(holder)
        }
        return request
    }

    /**
     * Takes a zone that has finished draining, on behalf of the occupier it was closing for.
     *
     * Separated from the offer by a scheduled event exactly as a woken vehicle's claim is: the zone
     * is not handed over inside the release that freed it, so the state is sound at every instant
     * the clock could be observed. Nothing can get in between, because the reservation stands until
     * this runs.
     */
    private fun grantZonesTo(holder: ZoneHolderIfc) {
        val request = myZoneRequests[holder] ?: return
        // The reservation may have been given up between the offer and this event, and on a set the
        // offer arrives as each zone drains, so most of those offers are premature: the grant is
        // all or nothing and waits for the last one.
        if (request.zones.any { it.closure !== request }) return
        if (!request.isDrained) return
        for (zone in request.zones) {
            check(zone.claim(holder)) {
                "Zone (${zone.name}) was offered to (${holder.name}) and then refused its " +
                        "claim, which cannot happen: the reservation admits the holder it is for."
            }
        }
        myZoneRequests.remove(holder)
        myNumWaitingForZones.value = myZoneRequests.size.toDouble()
        val allocation = ZoneAllocation(request, time)
        myZoneAllocations[holder] = allocation
        request.allocation = allocation
        myClosedZoneCount += request.zones.size
        myNumZonesClosed.value = myClosedZoneCount.toDouble()
        myTimeToCloseZones.value = allocation.timeToEngage
        myNumZoneEngagements.increment()
        // A hold taken for a stated duration is given back on a clock that starts *now*, not when
        // the space was asked for. Measuring from the request would silently shorten every closure
        // by however long the drain happened to take, which depends on traffic and so differs
        // between replications -- a closure that is not the one the modeller asked for, and nowhere
        // an error.
        if (request.isTimed) {
            scheduleTimedZoneRelease(allocation, request.holdFor)
        }
        // The statistics and the release are settled before anybody is told, because an action may
        // give the space straight back -- which is legitimate, and would otherwise be recorded
        // against a hold that had not yet been counted as having started.
        allocation.action.holdBegan(allocation)
    }

    /**
     * Gives back whatever a holder holds, or gives up what it asked for and never got.
     *
     * Harmless when it holds and wants nothing, which is what lets a process release
     * unconditionally rather than asking first.
     *
     * A request given up while it was **still draining** tells nobody, and that is the contract on
     * [ZoneHoldActionIfc] rather than an omission: abandonment is always the caller's own act, so
     * there is nothing the caller could learn from being told about it.
     */
    fun releaseZones(holder: ZoneHolderIfc) {
        auditFinishedInstant()
        myZoneRequests.remove(holder)?.let { request ->
            // Asked for, still draining, and no longer wanted: the aisle was going to be closed
            // and now is not. The zones reopen without ever having been held.
            request.isAbandoned = true
            myNumWaitingForZones.value = myZoneRequests.size.toDouble()
            (holder as? ZoneHolderRecordIfc)?.zoneSpaceFinished(this)
            for (zone in request.zones) {
                zone.abandonReservation(request)
            }
            // Offered only after every reservation is gone, so that a vehicle woken for one zone
            // does not find the next one still closed and go straight back to waiting.
            for (zone in request.zones) {
                handOver(zone.reopen(zoneContentionRule))
            }
            return
        }
        val allocation = myZoneAllocations.remove(holder) ?: return
        allocation.releasedAt = time
        myClosedZoneCount -= allocation.zones.size
        myNumZonesClosed.value = myClosedZoneCount.toDouble()
        (holder as? ZoneHolderRecordIfc)?.zoneSpaceFinished(this)
        for (zone in allocation.zones) {
            handOver(zone.release(holder, zoneContentionRule))
        }
        // Told last, so that an action sees a settled state: the allocation is released, the zones
        // are open, and whoever was waiting has already been chosen and scheduled. An action that
        // runs mid-release would see a zone that is neither held nor handed on.
        //
        // It does *not* give the waiting vehicles a turn, and that is worth being plain about. A
        // reservation beats a waiting vehicle by design -- without that, a closure on a busy aisle
        // would never happen at all -- and an action that asks again here makes an ordinary
        // reservation, which wins as any other would. A closure re-taken every time it ends
        // therefore holds the zone indefinitely. That is a modelling error rather than a mechanism
        // defect, and it reports itself: the zone shows as closed for the whole run in
        // numZonesClosed, the vehicle behind it in numBlockedByOccupier, and the end-of-replication
        // report names both.
        allocation.action.holdEnded(allocation)
    }

    private inner class ZoneGrantAction : EventActionIfc<ZoneHolderIfc> {
        override fun action(event: KSLEvent<ZoneHolderIfc>) {
            auditFinishedInstant()
            grantZonesTo(event.message!!)
        }
    }

    private val myZoneGrantAction = ZoneGrantAction()

    /** Schedules a holder's taking of space that has finished draining. */
    private fun scheduleZoneGrant(holder: ZoneHolderIfc) {
        // The offer came from a zone that is closing, and a zone closes only for a request, so the
        // request is there. Asserting it is what lets handOver treat every non-vehicle holder
        // alike instead of enumerating the kinds it knows how to notify.
        check(isWaitingForZones(holder)) {
            "Zone offered to (${holder.name}) on guide path (${this.name}), which has asked for " +
                    "no space, so there is no way to tell it that it may take the zone."
        }
        myNumEventsScheduled.increment()
        schedule(
            myZoneGrantAction, 0.0, holder, ProcessModel.ZONE_CLAIM_PRIORITY,
            "${holder.name}:takeZones"
        )
    }

    private inner class ZoneTimedReleaseAction : EventActionIfc<ZoneAllocation> {
        override fun action(event: KSLEvent<ZoneAllocation>) {
            val allocation = event.message!!
            // Tied to the allocation it was scheduled for, not merely to its holder. A hold may
            // already have been given back by hand, or by the action that was told of its
            // beginning, and a *second* hold may since have begun -- in which case a release
            // guarded only on "this holder still holds something" would end the wrong one, early,
            // and silently.
            if (myZoneAllocations[allocation.holder] === allocation) {
                releaseZones(allocation.holder)
            }
        }
    }

    private val myZoneTimedReleaseAction = ZoneTimedReleaseAction()

    /** Schedules the giving back of space taken for a stated duration. */
    private fun scheduleTimedZoneRelease(allocation: ZoneAllocation, holdFor: Double) {
        myNumEventsScheduled.increment()
        schedule(
            myZoneTimedReleaseAction, holdFor, allocation,
            name = "${allocation.holder.name}:releaseZones"
        )
    }

    /** Schedules a transporter's arrival in the zone it is travelling into. */
    internal fun scheduleTraversal(transporter: GuidedTransporter, zone: Zone, delay: Double) {
        myNumEventsScheduled.increment()
        schedule(
            myTraversalAction, delay, transporter to zone, ProcessModel.MOVE_PRIORITY,
            "${transporter.name}:enter:${zone.name}"
        )
    }

    /**
     * Schedules a waiting transporter's fresh attempt at what it was waiting for.
     *
     * Scheduled rather than called directly, so that its order against everything else happening at
     * that instant is explicit, and so that one release cannot set off an unbounded chain of
     * wake-ups inside a single event. The priority puts the attempt ahead of other transporters'
     * arrivals at the same instant and behind a process resumption already in flight.
     */
    internal fun scheduleClaimRetry(transporter: GuidedTransporter) {
        myNumEventsScheduled.increment()
        schedule(
            myClaimRetryAction, 0.0, transporter, ProcessModel.ZONE_CLAIM_PRIORITY,
            "${transporter.name}:retryClaim"
        )
    }

    /**
     * Schedules the release of the zone behind, for a control rule that gives it up part way into
     * the zone ahead rather than at one end or the other.
     */
    internal fun scheduleRearRelease(transporter: GuidedTransporter, delay: Double) {
        myNumEventsScheduled.increment()
        schedule(
            myRearReleaseAction, delay, transporter, ProcessModel.MOVE_PRIORITY,
            "${transporter.name}:releaseRear"
        )
    }

    override fun initialize() {
        // Nothing is owed from the previous replication: its last instant was audited by
        // checkClosing, and the state it left is about to be thrown away.
        myUnauditedInstant = Double.NaN
        // The space's own belief about what holders hold, which is a separate copy from the zones'
        // and would otherwise describe the previous replication for the whole of the next. This is
        // the only copy there is: a holder keeps none, so there is nothing else to clear.
        myNextRequestSequence = 0L
        myZoneRequests.clear()
        myZoneAllocations.clear()
        myClosedZoneCount = 0
        for (zone in network.zones) {
            zone.resetZone()
        }
        for (link in network.links) {
            link.resetLink()
        }
        for (transporter in myTransporters) {
            transporter.placeAtInitialPosition()
            // Nothing is owed yet, so this audits nothing; what it does is record that placement
            // happened in this instant, which makes the first instant there is anything to audit
            // about. Going through the gate rather than setting the field keeps one mechanism.
            auditFinishedInstant()
            engine.acquirePlacementHolds(transporter)
        }
        refreshFleetCounts()
        // Emitted every replication rather than once per run, so that a viewer joining at any
        // replication boundary has the structure before anything moves on it.
        myAnimationEmitter.emitGuidedPathDefined()
        for (transporter in myTransporters) {
            transporter.frontZone?.let { myAnimationEmitter.emitTransporterMoved(transporter, it) }
            myAnimationEmitter.emitTransporterState(transporter, transporter.transporterState)
        }
    }

    /** The transporters currently unable to proceed, with what each is waiting for. */
    val blockedTransporters: List<GuidedTransporter>
        get() = myTransporters.filter { it.transporterState == TransporterState.BLOCKED }

    /**
     * Reports any transporter still waiting when a replication ends.
     *
     * A waiting transporter schedules nothing, so a guide path that has stopped moving does not
     * announce itself: the clock simply runs on to the end of the replication with nobody going
     * anywhere, and the output looks like a system that merely had no work to do. This is the point
     * at which that becomes visible, and it names what each transporter holds and what it is
     * waiting for, which is what a modeler needs in order to see the cycle or the obstruction.
     */
    override fun replicationEnded() {
        if (auditAtReplicationEnd) {
            myInvariantChecker.checkClosing(myUnauditedInstant)
        }
        val traversals = myNumZoneTraversals.value
        if (traversals > 0.0) {
            myEventsPerTraversal.value = myNumEventsScheduled.value / traversals
        }
        // The same derivation a conveyor uses for cell utilization: the time-weighted average
        // number of zones covered, over how many zones the link has.
        for ((link, coverage) in myLinkCoverage) {
            myLinkUtilization[link]?.value =
                coverage.withinReplicationStatistic.weightedAverage / link.numZones
        }
        val stuck = blockedTransporters
        if (stuck.isEmpty()) return
        logger.warn {
            buildString {
                // Named by whatever the element actually is, so an active model's diagnostic does
                // not tell its reader to look for a transport system that is not in the model.
                append("${this@GuidedPathSpace::class.simpleName} ($name): ${stuck.size} ")
                append("transporter(s) were still ")
                append("waiting when replication ${model.currentReplicationNumber} ended. ")
                append("The guide path may have stopped moving rather than run out of work.")
                for (t in stuck) {
                    append(System.lineSeparator())
                    append("  (${t.name}) holds [${t.heldZones.joinToString { z -> z.name }}] ")
                    append("and waits for ")
                    val link = t.awaitedLink
                    if (link != null) {
                        append("link (${link.name})")
                    } else {
                        val zone = t.awaitedZone
                        append("zone (${zone?.name})")
                        // Why the zone is unavailable, not merely which zone it is. A vehicle behind
                        // another vehicle and a vehicle waiting for a crossing to clear are
                        // different situations with different remedies, and the reader cannot tell
                        // them apart from the zone's name.
                        val holder = zone?.holder
                        val occupants = zone?.numPresent ?: 0
                        when {
                            holder != null -> append(", which is held by (${holder.name})")
                            occupants > 0 -> append(", which has $occupants occupant(s) in it")
                        }
                    }
                }
            }
        }
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}

        /**
         * The system property that switches [checkInvariants] on for every guide path built in this
         * JVM: `-Dksl.guidedpath.checkInvariants=true`.
         *
         * It exists because continuous checking is worth most exactly where it is least likely to be
         * asked for -- every model in a test suite, including the ones written after whoever decided
         * to check has stopped looking. Setting one flag on the command line covers models that do
         * not exist yet, which no amount of editing existing tests can do.
         */
        const val CHECK_INVARIANTS_PROPERTY: String = "ksl.guidedpath.checkInvariants"

        internal fun defaultCheckInvariants(): Boolean =
            System.getProperty(CHECK_INVARIANTS_PROPERTY)?.toBooleanStrictOrNull() ?: false
    }

    override fun toString(): String = buildString {
        // The subclass's own name, so a passive system does not describe itself as a bare space.
        appendLine("${this@GuidedPathSpace::class.simpleName} : $name")
        appendLine("network = ${network.name}")
        appendLine("transporters:")
        for (t in myTransporters) appendLine("  $t")
    }
}
