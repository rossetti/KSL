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

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.VehicleMovementIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.TWResponseCIfc

/**
 * A vehicle's physical presence, as the fleet layer sees it.
 *
 * The third and last of the abstractions that stood between a fleet and a substrate.
 * [VehicleMovementIfc] says how a vehicle gets somewhere and
 * [ksl.modeling.spatial.FleetSpaceIfc] says what "somewhere" is; this says what a vehicle *is* --
 * something that carries loads, records how it spent its time, and can be told to stop.
 *
 * **It extends the movement seam rather than sitting beside it**, because moving and carrying are
 * the same object's business: what a vehicle is doing (moving loaded, moving empty, blocked) is
 * read from what is aboard it, and a design that split the two would have to keep them agreeing.
 *
 * **`internal`, deliberately.** [board] and [alight] are how a load gets on and off, and a manifest
 * a modeller can edit directly is a manifest that will disagree with the statistics derived from
 * it. The library supplies the bodies; a modeller supplies vehicles.
 *
 * **A body must forget what is aboard it between replications**, and nothing in this interface can
 * make it: an interface has no hook the framework calls. Being a `ModelElement` is how -- a guide
 * path's transporter is one already, and `FreePathBody` is one for no other reason. A manifest that
 * survives a replication starts the next one with a vehicle that has no room and no explanation for
 * it, which is a defect that only appears on the second replication.
 */
internal interface VehicleBodyIfc : VehicleMovementIfc {

    // ---- the manifest --------------------------------------------------------------------------

    /** How many loads it can carry at once. */
    val loadCapacity: Int

    /** What is aboard, in the order it came aboard. */
    val manifest: List<ProcessModel.Entity>

    val numLoadsAboard: Int

    val spareCapacity: Int

    val isAtCapacity: Boolean

    val isCarryingLoad: Boolean

    fun board(load: ProcessModel.Entity)

    fun alight(load: ProcessModel.Entity)

    // ---- how it spent its time -----------------------------------------------------------------

    val fracTimeMoving: TWResponseCIfc
    val fracTimeTransporting: TWResponseCIfc
    val fracTimeMovingEmpty: TWResponseCIfc

    /**
     * The fraction of time it could not proceed because something was in the way.
     *
     * **Always zero on a substrate with no interference**, and that is the honest answer rather
     * than a missing row: a free-path model asserts that vehicles do not obstruct one another, and
     * a zero here is that assertion showing up in the output where a reader can see it.
     */
    val fracTimeBlocked: TWResponseCIfc

    val numTimesBlocked: CounterCIfc

    /** How long in total it has been unable to proceed. Zero where nothing can block. */
    val cumulativeBlockedTime: Double

    // ---- how much of the room it used -----------------------------------------------------------
    //
    // Null for a vehicle that holds one, because a row measuring something a model does not have is
    // a question its reader has to answer every time they meet it.

    /** The mean number aboard, or null for a single-load vehicle. */
    val numLoadsAboardResponse: TWResponseCIfc?

    /** How much of the room was used, time-weighted, or null for a single-load vehicle. */
    val capacityUtilization: TWResponseCIfc?

    /** How much of the time it was full, or null for a single-load vehicle. */
    val fracTimeAtCapacity: TWResponseCIfc?

    /** How many loads a loaded move carried, or null for a single-load vehicle. */
    val loadsPerLoadedMove: ksl.modeling.variable.ResponseCIfc?

    /**
     * How many space units it has entered.
     *
     * A guide path counts zones. A substrate with no discretisation of space counts nothing, and
     * the per-carry figures derived from this are then zero -- which is what "this model has no
     * zones" should look like.
     */
    val zonesEntered: Int
        get() = 0

    // ---- being commanded -----------------------------------------------------------------------

    /** How fast it is going now. */
    val currentVelocity: Double

    /**
     * What a vehicle seizes to record that it is committed.
     *
     * A vehicle's own body, and only its own agent ever seizes it -- the dispatcher has already
     * chosen it by then, so this is a record of commitment rather than an allocation protocol. It
     * is here because a body is a physical thing with one of itself, whatever substrate it is on.
     */
    val seizable: ksl.modeling.entity.Resource

    /** Where it waits when it has nothing to do, or null to wait where it stops. */
    var homeBase: String?

    /**
     * Where a waiter suspends while this body is under way.
     *
     * Needed apart from what [VehicleMovementIfc.beginTravelTo] hands back, for the one case where
     * a vehicle is redirected to somewhere it already stands: no journey starts, so nothing will
     * arrive to resume it, and whoever is already waiting has to be found and woken.
     */
    val movementQueue: HoldQueue

    /**
     * Installs a veto asked at every decision point of a journey: may it go on past this one?
     *
     * A guide path asks at zone boundaries; an interpolated substrate asks at its steps; a
     * substrate with neither never asks, and a vehicle that runs flat or breaks down on one is
     * discovered at the end of its journey rather than part way through it. Null removes the veto.
     */
    fun attachContinuationGate(gate: (() -> Boolean)?)

    // ---- being towed ---------------------------------------------------------------------------

    /** True while somebody is moving it rather than it moving itself. */
    val isUnderTow: Boolean

    /** How fast it is being towed, or null when it is not. */
    var towVelocity: Double?

    /** Puts it back to standing still after a tow, whether or not it went anywhere. */
    fun endTow()
}
