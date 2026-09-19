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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceCIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc

/**
 * Controlled access to a transporter on a guide path: what a modeller sets, where it is, and what
 * its time was spent on.
 *
 * The free-path counterpart is [ksl.modeling.spatial.MoveableResourceCIfc], and this is deliberately
 * its shape: a transporter **is** a [ksl.modeling.entity.Resource], so the capacity, the queueing
 * and the utilization arrive through [ResourceCIfc], and what is added here is the part that is
 * about travelling a guide path.
 *
 * What is **not** here is the engine's own bookkeeping -- which zone is claimed, which link is
 * awaited, the route in progress, the direction of travel. Those are readable on
 * [GuidedTransporter] itself, because a model that wants to watch a vehicle or write its own
 * dispatching rule needs them, but they are not part of the contract a study or a report holds a
 * transporter by, and nothing outside the library may write them.
 */
interface GuidedTransporterCIfc : ResourceCIfc {

    // ---- what the modeller decides ------------------------------------------------------------

    /** Where the transporter starts each replication. */
    var initialPlacement: TransporterPlacement

    /**
     * The station or intersection it returns to when the pool's disposition rule sends it home, or
     * null when it has none. A fleet whose members share a home base contends for it.
     */
    var homeBase: String?

    /** Whether a velocity is drawn once per move or once per zone. */
    var velocitySampling: VelocitySampling

    /** How fast it travels, so a study can vary the speed as an input. */
    val velocityRV: RandomVariableCIfc

    // ---- how much of the guide path it takes up ------------------------------------------------

    /** How many whole zones it covers; one unless it was sized in zones. */
    val lengthInZones: Int

    /** Its length in the network's own units, or null when it was sized in zones instead. */
    val physicalLength: Double?

    /** How many loads it may carry at once, which is not its resource capacity. */
    val loadCapacity: Int

    // ---- where it is, and whether it can be sent ----------------------------------------------

    /** The zones it is standing on, which is what makes them unavailable to everybody else. */
    val heldZones: List<Zone>

    /** True while it is travelling, whether loaded or empty. */
    val isMoving: Boolean

    /**
     * True when it could be sent somewhere now: allocated to nobody, not halted, not under tow.
     * This is the question an allocation rule is really asking, and the one a report about an idle
     * fleet should be over.
     */
    val isDispatchable: Boolean

    /** True while another transporter is towing it, during which it goes nowhere of its own. */
    val isUnderTow: Boolean

    /** True when a shift schedule has it off duty. */
    val isOffShift: Boolean

    /** How far it has travelled, in the network's units. */
    val distanceTravelled: Double

    /** How many zones it has entered, which is the engine's unit of work. */
    val zonesEntered: Int

    // ---- what it is carrying -------------------------------------------------------------------

    /** The loads aboard, in boarding order. */
    val manifest: List<ProcessModel.Entity>

    /** How many loads are aboard. */
    val numLoadsAboard: Int

    /** How many more it could take. */
    val spareCapacity: Int

    // ---- how its time was spent ----------------------------------------------------------------

    /** Fraction of time travelling, loaded or empty. */
    val fracTimeMoving: TWResponseCIfc

    /** Fraction of time travelling with a load aboard. */
    val fracTimeTransporting: TWResponseCIfc

    /** Fraction of time travelling empty, which is what a dispatching rule exists to reduce. */
    val fracTimeMovingEmpty: TWResponseCIfc

    /** Fraction of time stopped by space it could not have, which no free-path model can report. */
    val fracTimeBlocked: TWResponseCIfc

    /** How many times it was stopped by space it could not have. */
    val numTimesBlocked: CounterCIfc

    /** Loads aboard over time, registered only for a transporter that may hold more than one. */
    val numLoadsAboardResponse: TWResponseCIfc?

    /** Loads aboard as a fraction of capacity, registered only for a multi-load transporter. */
    val capacityUtilization: TWResponseCIfc?

    /** Fraction of time full, registered only for a multi-load transporter. */
    val fracTimeAtCapacity: TWResponseCIfc?

    /** Loads carried per loaded move, registered only for a multi-load transporter. */
    val loadsPerLoadedMove: ResponseCIfc?
}
