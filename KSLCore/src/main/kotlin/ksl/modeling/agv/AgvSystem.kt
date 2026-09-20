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
package ksl.modeling.agv

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.fleet.FleetSystem
import ksl.modeling.fleet.FleetVehicle

import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathSpace
import ksl.modeling.guidedpath.rules.FIFOZoneContentionRule
import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 * A fleet of self-directing vehicles **on a guide path**, and the dispatcher that tasks them.
 *
 * The binding: it builds the space layer, supplies the network as the fleet's layout, and publishes
 * the rows that only a guide path can fill -- zones entered, deadlocks, obstructions, and the five
 * per-carry figures both paradigms share. Everything else a modeller uses is on [FleetSystem] and
 * is the same whatever the vehicles run on.
 *
 * The same physical world as the passive subsystem -- the same network, zones, routing, blocking
 * and deadlock detection -- with the decision-making moved out of the entity's process and into
 * objects that have processes of their own.
 *
 * @param network the guide path the fleet runs on. It is also the fleet's [space]: its
 *   intersections and station aliases are already named places, so the two are one object rather
 *   than two that could disagree
 */
open class AgvSystem @JvmOverloads constructor(
    parent: ModelElement,
    val network: GuidedPathNetwork,
    zoneContentionRule: ZoneContentionRuleIfc = FIFOZoneContentionRule(),
    assignmentPolicy: AssignmentPolicyIfc = NearestVehiclePolicy(),
    name: String? = null
) : FleetSystem(parent, assignmentPolicy, name) {

    /**
     * The space layer's runtime. It owns zone occupancy; this subsystem owns nothing physical.
     *
     * A [GuidedPathSpace] and not a `GuidedPathTransportSystem`: the difference between the two is
     * the passive protocol's own transport time, which this subsystem does not use and publishes its
     * own version of. Composing the space alone keeps an active model free of a report row that no
     * active run could fill.
     */
    internal val spaceSystem: GuidedPathSpace =
        GuidedPathSpace(this, network, zoneContentionRule, name = "${this.name}:Space")

    /** The network, which is what the fleet's machinery asks for places and distances. */
    override val space: GuidedPathNetwork
        get() = network

    /**
     * Whether the space-exclusivity invariants are checked whenever the simulation clock advances.
     *
     * The same control the passive subsystem carries, and it has to be here as well as there: the
     * guide path this system runs on is one it builds and owns, so without this a modeller has no
     * way to reach it and an active model could not be checked at all. Off by default, and the
     * initial value comes from the same system property, so switching checking on for a run switches
     * it on for both paradigms rather than only for one of them.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var checkInvariants: Boolean
        get() = spaceSystem.checkInvariants
        set(value) {
            spaceSystem.checkInvariants = value
        }

    /**
     * Whether this system audits its own account of itself once, as each replication ends.
     *
     * On by default, and it covers the guide path underneath as well, because the two halves of an
     * active model are not separately auditable in any useful sense: a vehicle that has lost track
     * of its assignment and one that has lost track of the zone it is standing on are the same kind
     * of failure seen from two sides.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    override var auditAtReplicationEnd: Boolean
        get() = spaceSystem.auditAtReplicationEnd
        set(value) {
            spaceSystem.auditAtReplicationEnd = value
        }

    /**
     * Whether the guide path underneath watches for a circular wait and raises when it finds one.
     *
     * On by default, and it belongs on this facade as much as on the space: a deadlock among active
     * vehicles is a deadlock of the aisles they are standing in, detected by the layer that owns the
     * zones. Switching it off is for a study that means to run a design point into gridlock and read
     * the result rather than catch an exception -- see the two-lane warehouse example, where "this
     * layout cannot carry this fleet" is the finding.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var deadlockDetectionEnabled: Boolean
        get() = spaceSystem.deadlockDetectionEnabled
        set(value) {
            spaceSystem.deadlockDetectionEnabled = value
        }

    /**
     * Whether a vehicle obstructed by one that is idle is treated as an error rather than as
     * traffic.
     *
     * Off by default, because a fleet that parks on the guide path obstructs itself by design and a
     * model may mean it to. Switching it on turns the most common silent modelling mistake -- an
     * idle vehicle left standing where everything behind it must pass -- into a failure at the
     * moment it happens rather than a run that finishes looking reasonable.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var strictObstructionPolicy: Boolean
        get() = spaceSystem.strictObstructionPolicy
        set(value) {
            spaceSystem.strictObstructionPolicy = value
        }

    /**
     * Whether the space layer registers a response per **link**.
     *
     * Off by default, because a large network would otherwise put a row on every report and in every
     * output database for every aisle in it. The passive subsystem takes this as a constructor
     * argument; here it is a property, settable up to the moment the model runs, and switching it
     * off again takes the responses back out.
     *
     * A model that wants to know *where* its congestion is has no other way to ask. Fleet-level
     * rows say how much blocking there was; these say which aisles produced it.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var collectLinkStatistics: Boolean
        get() = spaceSystem.collectLinkStatistics
        set(value) {
            spaceSystem.collectLinkStatistics = value
        }

    /**
     * Whether the space layer registers a response per **zone**: the finest tier, and the most
     * expensive.
     *
     * The tier that answers questions about junctions, since a junction is a zone and its occupancy
     * is the only direct measurement of what crossing traffic costs. A thousand-zone network
     * registers a thousand responses, so this is off by default and worth switching on for a
     * diagnostic run rather than for a study.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var collectZoneStatistics: Boolean
        get() = spaceSystem.collectZoneStatistics
        set(value) {
            spaceSystem.collectZoneStatistics = value
        }

    /**
     * Whether the space layer's three movement hold queues appear on the summary report.
     *
     * Off before this is ever called: [GuidedPathSpace] silences them in its own initializer, and
     * this subsystem relies on that rather than repeating it. (It did repeat it, with a comment
     * arguing the repeat was necessary. Removing the line changed nothing, which is how the
     * repetition was found.)
     *
     * Worth switching on only for a model that has stopped moving and needs to be looked at,
     * because under this paradigm the row means something other than it appears to. The passive
     * subsystem's queue holds loads being carried, so its number in queue is a line of work
     * waiting. Here it holds *vehicle agents*, so the same row is a count of carts under way
     * wearing the name of a queue -- the most misleading row this subsystem could produce, and the
     * one a reader is least likely to question.
     *
     * Note that this cannot be driven from the base class's initializer: it reaches the space
     * layer, and that does not exist until this subclass's own properties are built.
     */
    override fun substrateReporting(option: Boolean) {
        spaceSystem.statisticalReportingForHoldQueues(option)
    }

    override fun collectCarry(
        approachTime: Double,
        rideTime: Double,
        blockedTime: Double,
        zonesTraversed: Int,
        routeLength: Double
    ) {
        spaceSystem.collectCarry(approachTime, rideTime, blockedTime, zonesTraversed, routeLength)
    }

    override fun spaceHeldBy(vehicle: FleetVehicle): String =
        (vehicle as? AgvVehicle)?.transporter?.heldZones?.joinToString { it.name } ?: ""

    /** How many zones the fleet entered, which is very nearly the engine's event count. */
    val numZoneTraversals: CounterCIfc get() = spaceSystem.numZoneTraversals

    /** Events the guide path put on the calendar: traversals, rear releases, and retries. */
    val numEventsScheduled: CounterCIfc get() = spaceSystem.numEventsScheduled

    /** Events scheduled per zone entered. One is the floor; much above two is wake-and-refuse. */
    val eventsPerZoneTraversal: ResponseCIfc get() = spaceSystem.eventsPerZoneTraversal

    /** Circular waits found. At most one per replication, since finding one ends it. */
    val numDeadlocksDetected: CounterCIfc get() = spaceSystem.numDeadlocksDetected

    /** Times a vehicle stopped behind an idle one that will not move on its own. */
    val numObstructionsDetected: CounterCIfc get() = spaceSystem.numObstructionsDetected

    /** How many vehicles are travelling. Distinct from [numVehiclesOnTask]: a vehicle
     *  repositioning is moving and is on no task. */
    val numVehiclesMoving: TWResponseCIfc get() = spaceSystem.numTransportersMoving

    /** How many vehicles cannot claim the space ahead. The figure a free-path model cannot produce. */
    val numVehiclesBlocked: TWResponseCIfc get() = spaceSystem.numTransportersBlocked

    /** The fraction of the guide path's zones that are held. */
    val zoneUtilization: TWResponseCIfc get() = spaceSystem.zoneUtilization

    /** Per completed carry: time spent travelling out empty to collect the load. */
    val approachTime: ResponseCIfc get() = spaceSystem.approachTime

    /** Per completed carry: time spent travelling with the load aboard. */
    val rideTime: ResponseCIfc get() = spaceSystem.rideTime

    /** Per completed carry: time unable to claim the space ahead. */
    val transportBlockedTime: ResponseCIfc get() = spaceSystem.transportBlockedTime

    /** Per completed carry: zones entered. */
    val zonesTraversedPerTransport: ResponseCIfc get() = spaceSystem.zonesTraversedPerTransport

    /** Per completed carry: distance covered along the guide path. */
    val routeLengthPerTransport: ResponseCIfc get() = spaceSystem.routeLengthPerTransport
}
