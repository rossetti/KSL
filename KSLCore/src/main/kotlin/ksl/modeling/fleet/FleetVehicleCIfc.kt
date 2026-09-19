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
package ksl.modeling.fleet

import ksl.modeling.fleet.policies.BidPolicyIfc
import ksl.modeling.fleet.policies.DispositionPolicyIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc

/**
 * Controlled access to a vehicle a dispatcher commits to tasks: the four policies it carries, what
 * it is doing, and how its time was spent.
 *
 * The passive counterpart is [ksl.modeling.guidedpath.GuidedTransporterCIfc], and the two report the
 * same fractions of time on purpose -- moving, transporting, moving empty, blocked -- because the
 * whole claim of the two-paradigm design is that one shop modelled either way gives one answer. A
 * contract that reported them differently would make that claim unverifiable.
 *
 * A [FleetVehicle] is not a [ksl.modeling.entity.Resource]: it is never seized, it is *assigned*.
 * So there is no capacity here, and [fracTimeOnTask] is the quantity that takes utilization's
 * place -- committed, whether moving or standing.
 */
interface FleetVehicleCIfc {

    // ---- what the modeller decides -------------------------------------------------------------

    /** Where it returns to when its disposition policy sends it home, or null when it has none. */
    var homeBase: String?

    /** What it does when it has nothing to do. */
    var dispositionPolicy: DispositionPolicyIfc

    /** What it offers when a dispatcher runs an auction. */
    var bidPolicy: BidPolicyIfc

    /** What happens to the task in hand when the vehicle is interrupted. */
    var interruptionPolicy: InterruptionPolicyIfc

    /** Whether it is willing to serve a stop it has been offered. */
    var stopControl: StopControlIfc

    /** How many loads it may carry at once. */
    val loadCapacity: Int

    // ---- what it is doing ----------------------------------------------------------------------

    /** Where it is, by name. */
    val currentLocationName: String

    /** True when it could take work: in service, not failed, and not already committed. */
    val isAvailable: Boolean

    /** The task it is committed to, or null. */
    val currentAssignment: Assignment?

    /** True when it has a task in hand. */
    val hasAssignment: Boolean

    /** True while a failure model has it broken down. */
    val isFailed: Boolean

    /** True while it has been taken out of service. */
    val isOutOfService: Boolean

    // ---- how its time was spent ----------------------------------------------------------------

    /** Fraction of time travelling, loaded or empty. */
    val fracTimeMoving: TWResponseCIfc

    /** Fraction of time travelling with a load aboard. */
    val fracTimeTransporting: TWResponseCIfc

    /** Fraction of time travelling empty. */
    val fracTimeMovingEmpty: TWResponseCIfc

    /** Fraction of time stopped by space it could not have; flat zero on a free path. */
    val fracTimeBlocked: TWResponseCIfc

    /** How many times it was stopped by space it could not have. */
    val numTimesBlocked: CounterCIfc

    /**
     * Fraction of time committed to a task, whether moving or standing.
     *
     * Not the same as [fracTimeMoving] and neither contains the other: a vehicle is on task while
     * it stands still being loaded, and moving but not on task while it returns to its depot.
     */
    val fracTimeOnTask: TWResponseCIfc

    /** Loads carried per tour, registered only for a vehicle that may hold more than one. */
    val loadsPerTour: ResponseCIfc?

    /** Loads aboard over time, registered only for a multi-load vehicle. */
    val numLoadsAboardResponse: TWResponseCIfc?

    /** Loads aboard as a fraction of capacity, registered only for a multi-load vehicle. */
    val capacityUtilization: TWResponseCIfc?
}
