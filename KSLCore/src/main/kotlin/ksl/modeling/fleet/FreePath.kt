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

import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.spatial.FleetSpaceIfc
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.SpatialModel
import ksl.modeling.spatial.SpatialModelFleetSpace
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.toDouble

/**
 * A free-path vehicle's body: a `MovableResource` that also carries a manifest.
 *
 * The second binding of [VehicleBodyIfc], and what it demonstrates is that the fleet layer really
 * was substrate-independent rather than merely arranged to look it: this file is the whole of what
 * a fleet needs to run on a free path, and nothing above it changed to accept it.
 *
 * **What a free path does not have, it reports as not having.** There is no interference between
 * vehicles, so `fracTimeBlocked` and `numTimesBlocked` are registered and stay at zero, and
 * `zonesEntered` is zero. Those are not missing rows: a free-path model *asserts* that vehicles do
 * not obstruct one another, and a zero is that assertion showing up in the output where a reader
 * can see it and compare it with a guide path's.
 *
 * **The manifest is here rather than on `MovableResource`.** A movable resource carries whoever
 * seized it, which is the passive protocol's answer and is exactly one entity; carrying several is
 * a fleet's idea, so the fleet's binding is where it belongs. The capacity statistics come with it,
 * with the same names and the same meanings the guide path's have, so the two are comparable.
 *
 * **A `ModelElement`, and that is not decoration.** A body holds state that must be forgotten
 * between replications -- what is aboard it -- and a guide path's body gets that for free by being
 * a model element already. A body written any other way does not, and a manifest that survives a
 * replication starts the next one with a vehicle that has no room and no explanation. Found by the
 * first study to run three replications with loads still aboard when the horizon fell.
 *
 * @param resource what actually moves, and what a vehicle seizes to record its commitment
 */
class FreePathBody internal constructor(
    val resource: MovableResource,
    override val loadCapacity: Int
) : ModelElement(resource, "${resource.name}:Manifest"), VehicleBodyIfc,
    ksl.modeling.spatial.VehicleMovementIfc by resource {

    private val myManifest = mutableListOf<ProcessModel.Entity>()

    override fun initialize() {
        super.initialize()
        myManifest.clear()
        observeCapacity()
    }

    override val manifest: List<ProcessModel.Entity>
        get() = myManifest

    override val numLoadsAboard: Int
        get() = myManifest.size

    override val spareCapacity: Int
        get() = loadCapacity - myManifest.size

    override val isAtCapacity: Boolean
        get() = myManifest.size >= loadCapacity

    override val isCarryingLoad: Boolean
        get() = myManifest.isNotEmpty()

    override fun board(load: ProcessModel.Entity) {
        require(myManifest.none { it === load }) {
            "Load (${load.name}) is already aboard vehicle (${resource.name})."
        }
        myManifest.add(load)
        require(myManifest.size <= loadCapacity) {
            "Vehicle (${resource.name}) was given ${myManifest.size} loads but holds $loadCapacity."
        }
        resource.isTransporting = true
        observeCapacity()
    }

    override fun alight(load: ProcessModel.Entity) {
        require(myManifest.any { it === load }) {
            "Load (${load.name}) is not aboard vehicle (${resource.name})."
        }
        myManifest.removeAll { it === load }
        if (myManifest.isEmpty()) resource.isTransporting = false
        observeCapacity()
    }

    // ---- how it spent its time -----------------------------------------------------------------

    override val fracTimeMoving: TWResponseCIfc get() = resource.fracTimeMoving
    override val fracTimeTransporting: TWResponseCIfc get() = resource.fracTimeTransporting
    override val fracTimeMovingEmpty: TWResponseCIfc get() = resource.fracTimeMovingEmpty

    /**
     * Registered, and zero for the whole run.
     *
     * See the class comment: it is the free path's central assumption, reported rather than left to
     * be remembered.
     */
    private val myFracTimeBlocked = TWResponse(resource, "${resource.name}:FracTimeBlocked")
    override val fracTimeBlocked: TWResponseCIfc get() = myFracTimeBlocked

    private val myNumTimesBlocked = Counter(resource, "${resource.name}:NumTimesBlocked")
    override val numTimesBlocked: CounterCIfc get() = myNumTimesBlocked

    override val cumulativeBlockedTime: Double
        get() = 0.0

    // ---- how much of the room it used ----------------------------------------------------------
    //
    // The same rows, the same names and the same meanings the guide path's body registers, so that
    // a capacity study reads the same whichever substrate it was run on. Registered only above a
    // capacity of one, on the convention this subsystem keeps throughout.

    private val myNumLoadsAboard: TWResponse? =
        if (loadCapacity <= 1) null else TWResponse(resource, "${resource.name}:NumLoadsAboard")
    override val numLoadsAboardResponse: TWResponseCIfc? get() = myNumLoadsAboard

    private val myCapacityUtilization: TWResponse? =
        if (loadCapacity <= 1) null else TWResponse(resource, "${resource.name}:CapacityUtilization")
    override val capacityUtilization: TWResponseCIfc? get() = myCapacityUtilization

    private val myFracTimeAtCapacity: TWResponse? =
        if (loadCapacity <= 1) null else TWResponse(resource, "${resource.name}:FracTimeAtCapacity")
    override val fracTimeAtCapacity: TWResponseCIfc? get() = myFracTimeAtCapacity

    private val myLoadsPerLoadedMove: Response? =
        if (loadCapacity <= 1) null else Response(resource, "${resource.name}:LoadsPerLoadedMove")
    override val loadsPerLoadedMove: ResponseCIfc? get() = myLoadsPerLoadedMove

    private fun observeCapacity() {
        myNumLoadsAboard?.value = myManifest.size.toDouble()
        myCapacityUtilization?.value = myManifest.size.toDouble() / loadCapacity
        myFracTimeAtCapacity?.value = isAtCapacity.toDouble()
    }

    // ---- being commanded -----------------------------------------------------------------------

    override val currentVelocity: Double
        get() = resource.towVelocity ?: resource.velocity.value

    override var homeBase: String? = null

    override val seizable: Resource
        get() = resource

    override val movementQueue: HoldQueue
        get() = resource.travelQueue

    override fun attachContinuationGate(gate: (() -> Boolean)?) {
        resource.continuationGate = gate
    }

    override fun beginTravelTo(
        destination: LocationIfc,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity
    ): HoldQueue? {
        // Every loaded departure, counted where the guide path counts it: as a movement begins,
        // rather than per journey, so a vehicle redirected mid-move records the new movement too.
        if (myManifest.isNotEmpty()) myLoadsPerLoadedMove?.value = myManifest.size.toDouble()
        return resource.beginTravelTo(destination, purpose, waiter)
    }

    // ---- being towed ---------------------------------------------------------------------------

    override val isUnderTow: Boolean
        get() = resource.towVelocity != null

    override var towVelocity: Double?
        get() = resource.towVelocity
        set(value) {
            resource.towVelocity = value
        }

    override fun endTow() {
        resource.towVelocity = null
    }

    override fun toString(): String = "FreePathBody(${resource.name}, $numLoadsAboard aboard)"
}

/**
 * A fleet vehicle that moves over a free path.
 *
 * Everything a modeller uses -- assignments, batteries, failures, the manifest, every statistic --
 * is on [FleetVehicle] and is the same as it is for a vehicle on a guide path. What is here is the
 * body and the three answers a substrate owes: how to move it while somebody pushes, and what to
 * record about where it stopped.
 *
 * @param stepSize how far apart the decision points of a journey are. It fixes how quickly a
 *   breakdown, a flat battery or a redirection is observed, and it is ignored where the spatial
 *   model cannot say what lies between two places -- there a journey is one step and the vehicle
 *   arrives before anything can stop it
 */
open class FreePathVehicle @JvmOverloads constructor(
    val fleet: FreePathFleet,
    initialLocation: String,
    velocity: RVariableIfc,
    name: String? = null,
    loadCapacity: Int = 1,
    stepSize: Double = 1.0,
    battery: Battery? = null,
    failureModel: FailureModel? = null
) : FleetVehicle(fleet, name, loadCapacity, battery, failureModel) {

    /** What actually moves. A `MovableResource`, so the passive verbs work on it unchanged. */
    val resource: MovableResource = MovableResource(
        this, fleet.space.requireLocation(initialLocation), velocity,
        name = "${this.name}:Body", stepSize = stepSize
    )

    override val body: VehicleBodyIfc = FreePathBody(resource, loadCapacity)

    /**
     * A tow along the free path: the same geometry an ordinary journey uses, at whatever speed
     * somebody is pushing it. The speed is already set on the body by the time this is called.
     */
    override fun towJourney(location: String, waiter: ProcessModel.Entity): HoldQueue? =
        resource.beginTravelTo(fleet.space.requireLocation(location), MovePurpose.TOW, waiter)

    /**
     * **Nothing is held and nobody is obstructed.** A vehicle stopped on a free path is in nobody's
     * way, which is what a free path means; a policy asking `isObstructingNow` is told no, and a
     * policy that tows only obstructing vehicles will correctly never tow on this substrate.
     */
    override fun failureInterruption(failureNumber: Int, repairTime: Double): Interruption.Failed =
        Interruption.Failed(
            this, time, currentLocationName, emptyList(), activity(),
            currentAssignment?.task, isCarryingALoad,
            failureNumber = failureNumber, repairTime = repairTime
        )

    override fun outOfChargeInterruption(): Interruption.OutOfCharge =
        Interruption.OutOfCharge(
            this, time, currentLocationName, emptyList(), activity(),
            currentAssignment?.task, isCarryingALoad
        )

    /** What it was doing, read from what is aboard rather than asserted. */
    private fun activity(): TransporterState = when {
        !resource.isMoving -> TransporterState.IDLE
        isCarryingALoad -> TransporterState.MOVING_LOADED
        else -> TransporterState.MOVING_EMPTY
    }
}

/**
 * A fleet of self-directing vehicles on a **free path**, and the dispatcher that tasks them.
 *
 * The binding that made the point of the whole seam: a dispatcher, tours, stops, lines, multi-load
 * consolidation, batteries and breakdowns, over a spatial model instead of a guide path, with no
 * change to any of them. A vehicle here travels directly between two places at its own speed and
 * never waits for another vehicle, which is exactly what a free-path model assumes -- and the
 * difference between that assumption and a guide path's blocking is what a comparison between the
 * two measures.
 *
 * **Places are the spatial model's named locations.** A `DistancesModel` names them; so does any
 * model whose `namedLocations` are populated. Whether a vehicle's position is *live* while it
 * travels is the spatial model's business too: one that can interpolate answers where the vehicle
 * is now, and one that cannot answers where it set out from until it arrives.
 *
 * @param spatialModel the layout the places belong to and whose metric measures between them
 * @param places the named places a vehicle can be sent to. Defaults to the model's own named
 *   locations, which a `DistancesModel` maintains and a plane does not
 */
open class FreePathFleet @JvmOverloads constructor(
    parent: ModelElement,
    spatialModel: SpatialModel,
    places: List<LocationIfc> = spatialModel.namedLocations,
    assignmentPolicy: AssignmentPolicyIfc = NearestVehiclePolicy(),
    name: String? = null
) : FleetSystem(parent, assignmentPolicy, name) {

    override val space: FleetSpaceIfc = SpatialModelFleetSpace(spatialModel, places)

    override fun toString(): String =
        "FreePathFleet($name, ${vehicles.size} vehicles, over ${space.name})"
}
