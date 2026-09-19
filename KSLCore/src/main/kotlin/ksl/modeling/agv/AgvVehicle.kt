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

import ksl.modeling.fleet.Battery
import ksl.modeling.fleet.FailureModel
import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.Interruption
import ksl.modeling.fleet.VehicleBodyIfc

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.MovementWait
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.VelocitySampling
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.spatial.MovePurpose
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A fleet vehicle that runs on a guide path.
 *
 * The binding: it composes a `GuidedTransporter` for its physical presence and supplies it to
 * [FleetVehicle] as a body. Everything else a modeller uses -- assignments, batteries, failures,
 * the manifest, every statistic -- is on the base class and is the same whatever the vehicle runs
 * on.
 *
 * What is left here is exactly what only a guide path has: zones, a physical length measured
 * against them, a velocity that may be redrawn at each one, and a tow that is a journey along a
 * route.
 *
 * @property battery the vehicle's energy store, or null for a vehicle whose charge is not modelled.
 * A vehicle with no battery reports no charging statistics, because a row that measures something
 * the model does not have is a question its reader has to answer for themselves every time.
 * @property failureModel when the vehicle breaks down and how long it takes to repair, or null for
 * a vehicle that does not fail.
 * @param loadCapacity how many loads the vehicle can carry at once. A vehicle given more than one
 * task in a dispatching pass plans a single tour over all of them, in an order the dispatcher's
 * tour policy chooses. The capacity statistics are registered only when this is above one.
 */
open class AgvVehicle @JvmOverloads constructor(
    /** The fleet this vehicle belongs to, as the guide path knows it. */
    val agvSystem: AgvSystem,
    initialPlacement: TransporterPlacement,
    velocity: RVariableIfc,
    lengthInZones: Int = 1,
    zoneControlRule: ZoneControlRuleIfc = EndOfZoneControl(),
    name: String? = null,
    physicalLength: Double? = null,
    loadCapacity: Int = 1,
    battery: Battery? = null,
    failureModel: FailureModel? = null
) : FleetVehicle(agvSystem, name, loadCapacity, battery, failureModel) {

    /**
     * The physical presence on the guide path: zone occupancy, movement, and the utilization
     * statistics that go with them. Composed rather than inherited, for the reason in
     * [FleetVehicle]'s class comment. A modeller never names this.
     */
    internal val transporter: GuidedTransporter = GuidedTransporter(
        agvSystem.spaceSystem, initialPlacement, velocity, lengthInZones, zoneControlRule,
        "${this.name}:Body", physicalLength, loadCapacity
    )

    /**
     * The same thing, as the fleet's machinery sees it.
     *
     * Everything above the substrate -- the control loop, the tour, the manifest, the statistics --
     * goes through this, and nothing above the substrate names a transporter. What is left naming
     * one is what only a guide path has, and it is all in this file.
     */
    override val body: VehicleBodyIfc = GuidedPathBody(
        transporter, agvSystem.spaceSystem.holdQueueFor(MovementWait.DRIVING)
    )

    /**
     * How much of the guide path the vehicle covers, in the network's own length units, when it is
     * sized by length rather than by whole zones. Null means it is sized in zones.
     *
     * Passed straight through to the body. It is here for the same reason the passive transporter
     * has it -- a vehicle that fits inside a zone gets its own length back when it reverses out of a
     * dead end -- and it is here at all so that the two paradigms can model the same vehicle. A
     * feature only one of them had would make any comparison between them a comparison of the
     * feature.
     */
    val physicalLength: Double?
        get() = transporter.physicalLength

    /**
     * How often the velocity is drawn when it is random: once per movement, or once per zone.
     *
     * Delegated to the body, which owns the movement. `PER_MOVE` by default, matching the passive
     * transporter and `MovableResource`.
     */
    var velocitySampling: VelocitySampling
        get() = transporter.velocitySampling
        set(value) {
            transporter.velocitySampling = value
        }

    /**
     * A tow along the guide path: the same routing, zone claiming and blocking an ordinary journey
     * has, at whatever speed somebody is pushing it.
     */
    override fun towJourney(location: String, waiter: ProcessModel.Entity): HoldQueue? =
        agvSystem.spaceSystem.beginJourney(
            transporter, location, MovePurpose.TOW, waiter, MovementWait.DRIVING
        )

    /**
     * Answered from the guide path: a blocked vehicle records the zone or the link it is waiting
     * for, so being in the way is a fact about what this vehicle holds rather than a guess.
     */
    override fun vehiclesObstructed(): List<FleetVehicle> {
        val bodies = agvSystem.spaceSystem.transportersHeldUpBy(transporter)
        if (bodies.isEmpty()) return emptyList()
        return agvSystem.vehicles.filterIsInstance<AgvVehicle>()
            .filter { v -> bodies.any { it === v.transporter } }
    }

    override fun failureInterruption(failureNumber: Int, repairTime: Double): Interruption.Failed =
        Interruption.Failed(
            this, time, currentLocationName, transporter.heldZones, transporter.transporterState,
            currentAssignment?.task, isCarryingALoad,
            failureNumber = failureNumber,
            repairTime = repairTime
        )

    override fun outOfChargeInterruption(): Interruption.OutOfCharge =
        Interruption.OutOfCharge(
            this, time, currentLocationName, transporter.heldZones, transporter.transporterState,
            currentAssignment?.task, isCarryingALoad
        )
}
