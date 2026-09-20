package ksl.modeling.fleet

import ksl.modeling.fleet.policies.AlwaysServe
import ksl.modeling.fleet.policies.BidPolicyIfc
import ksl.modeling.fleet.policies.DispositionPolicyIfc
import ksl.modeling.fleet.policies.NetworkDistanceBid
import ksl.modeling.fleet.policies.ReturnToHomeBaseDisposition
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.spatial.VehicleMovementIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 * A vehicle: the permanent identity a modeller declares, names, and reads statistics from.
 *
 * What it is *not* is the thing that decides, and it is not the thing that occupies space. Those
 * are two other objects, and both are hidden. The vehicle **composes** a [VehicleBodyIfc] for its
 * physical presence, because every substrate's body is a `Resource` and a resource is passive by
 * construction: inheriting one would expose a way to seize this vehicle as though it were a tool,
 * which is exactly the modelling stance this subsystem exists to replace. And it holds a
 * per-replication agent for its behaviour, because a `KSLProcess` runs once and a vehicle must run
 * in every replication.
 *
 * **Substrate-independent.** Nothing here names a guide path, a projection or a plane. What a
 * subclass supplies is a [body], a tow, and the two facts an interruption carries about *where a
 * vehicle stopped* -- which is the only thing about stopping that differs between substrates.
 *
 * A modeller names only the concrete subclass.
 *
 * @property failureModel when the vehicle breaks down and how long it takes to repair, or null for
 * a vehicle that does not fail. Like [battery], a vehicle without one reports no failure rows.
 * @property battery the vehicle's energy store, or null for a vehicle whose charge is not modelled.
 * A vehicle with no battery reports no charging statistics, because a row that measures something
 * the model does not have is a question its reader has to answer for themselves every time.
 * @property loadCapacity how many loads the vehicle can carry at once. A vehicle given more than
 * one task in a dispatching pass plans a single tour over all of them, in an order the dispatcher's
 * tour policy chooses. The capacity statistics are registered only when this is above one, because
 * a row measuring something the model does not have is a question its reader has to answer for
 * themselves every time.
 */
abstract class FleetVehicle @JvmOverloads constructor(
    val system: FleetSystem,
    name: String? = null,
    /**
     * How many loads this vehicle can carry at once.
     *
     * Declared here rather than read from the [body], and the reason is initialization order: the
     * capacity statistics are registered only for a vehicle that holds more than one, that decision
     * is made while this class is being constructed, and the body belongs to a subclass that has
     * not been built yet. A body's capacity is built from this same number, so there is one figure
     * rather than two that could disagree.
     */
    override val loadCapacity: Int = 1,
    val battery: Battery? = null,
    val failureModel: FailureModel? = null
) : ModelElement(system, name), FleetVehicleCIfc {

    /**
     * The physical presence: what carries the loads, moves, and records how the time was spent.
     *
     * Supplied by the subclass, which is what binds this vehicle to a substrate. Everything here --
     * the control loop's view of it, the tour, the manifest, the statistics -- goes through the
     * abstraction, and nothing here names a guide path.
     *
     * **A property rather than a constructor parameter**, because a body is built from the
     * vehicle's own name and its system's substrate, and neither is available until this class has
     * been constructed. Nothing in this class reads it during construction.
     */
    internal abstract val body: VehicleBodyIfc

    /**
     * Required by `seize`, which always takes a queue. Nothing ever waits here: a vehicle's body is
     * seized only by that vehicle's own agent, and only once the dispatcher has already chosen it.
     * The allocation records the vehicle's commitment; it is not an allocation protocol.
     */
    internal val bodyQ: RequestQ = RequestQ(this, "${this.name}:BodyQ")

    init {
        require(loadCapacity >= 1) {
            "Vehicle ($name) was given a load capacity of $loadCapacity. A vehicle must be able to " +
                    "carry at least one load."
        }
        system.addVehicle(this)
    }

    /** Where the vehicle waits when it has nothing to do, or null to wait where it stops. */
    override var homeBase: String? = null
        set(value) {
            require(model.isNotRunning) { "The home base cannot be changed while the model is running." }
            field = value
            body.homeBase = value
        }

    /** What it does with itself when the dispatcher has no work. Per vehicle, so a fleet may be
     *  heterogeneous. */
    override var dispositionPolicy: DispositionPolicyIfc = ReturnToHomeBaseDisposition()

    /** What it offers when a dispatcher calls for proposals. Consulted from the negotiated phase. */
    override var bidPolicy: BidPolicyIfc = NetworkDistanceBid()

    /**
     * What happens when this vehicle stops and cannot carry on by itself.
     *
     * Per vehicle, so a fleet may be heterogeneous -- which is the realistic case, since a fleet is
     * rarely all one age. The default repairs where the vehicle stands and leaves a flat one flat.
     */
    override var interruptionPolicy: InterruptionPolicyIfc = RepairInPlacePolicy()

    /**
     * Who decides, stop by stop, whether this vehicle serves the next place in its tour.
     *
     * `AlwaysServe` by default, which is what every fleet did before there was a control to ask, so
     * setting nothing changes nothing. Replace it with `TimetableControl` for a scheduled service or
     * `DispatcherStopControl` for one a controller can run express or turn short.
     */
    override var stopControl: StopControlIfc = AlwaysServe()

    /** The live agent for this replication. Non-null only between `initialize` and the horizon, and
     *  **replaced** rather than reused each replication -- a retained agent would read the previous
     *  replication's mailbox, which nothing in the framework resets for a runtime agent. */
    internal var agent: FleetSystem.VehicleAgent? = null

    /**
     * Where the vehicle is, as a place name, **updated while it travels**.
     *
     * Read through the movement seam's [ksl.modeling.spatial.VehicleMovementIfc.positionNow] rather
     * than from anything written on arrival, and the difference matters more than it looks. A
     * location written when a vehicle is *placed* and when it *arrives* still reports where the
     * vehicle set off from throughout a journey. Anything asking a moving vehicle where it is -- a
     * bidding rule, a re-tasking rule, a feasible-set cost -- would be answered with its last
     * stopping place, and would keep choosing as though it had never left.
     *
     * How finely "while it travels" is resolved is the substrate's business. A guide path answers
     * with the junction a vehicle is heading for, which is the right approximation where a vehicle
     * cannot turn round: the junction ahead is the first point from which it has any choice. An
     * interpolated substrate answers with the interpolated point. A substrate that cannot say
     * answers with the place it set out from, and says so by not interpolating.
     */
    override val currentLocationName: String
        get() = movement.positionNow.name

    /**
     * How fast this vehicle travels, as most recently sampled.
     *
     * For a bidding rule that wants to quote a completion time rather than a distance, since a fast
     * vehicle slightly further off finishes first and a distance-only rule cannot say so. It is the
     * *last sampled* value rather than a distributional mean, which is exact when velocity is
     * constant -- the usual case -- and an estimate otherwise. A bid is a quote, not a guarantee, so
     * an estimate is the right thing here; anything drawing on it for a hard constraint should not.
     */
    val nominalVelocity: Double
        get() = body.currentVelocity

    /** True when it has declared itself available and holds no assignment. Asserted by the vehicle,
     *  never inferred by the dispatcher. */
    override val isAvailable: Boolean
        get() = system.dispatcher.isAvailable(this)

    override val currentAssignment: Assignment?
        get() = agent?.assignment

    /** Everything this vehicle is committed to. One at a time while capacity is one. */
    val assignments: List<Assignment>
        get() = agent?.assignments ?: emptyList()

    /** True when the vehicle is committed to anything at all. */
    override val hasAssignment: Boolean
        get() = agent?.assignments?.isNotEmpty() == true

    // ---- statistics ---------------------------------------------------------------------------
    // These delegate to the body wherever the passive subsystem already reports the same quantity,
    // so that a model built both ways can be compared row by row. The element *names* still differ
    // by a ":Body" suffix, which the equivalence benchmark maps; see the note in FleetSystem.

    override val fracTimeMoving: TWResponseCIfc get() = body.fracTimeMoving
    override val fracTimeTransporting: TWResponseCIfc get() = body.fracTimeTransporting
    override val fracTimeMovingEmpty: TWResponseCIfc get() = body.fracTimeMovingEmpty
    override val fracTimeBlocked: TWResponseCIfc get() = body.fracTimeBlocked
    override val numTimesBlocked: CounterCIfc get() = body.numTimesBlocked

    /**
     * The fraction of time the vehicle holds an assignment.
     *
     * Not the body's figure, and the difference is the point: a vehicle standing still while a load
     * is put on it is on task and not moving. Under the passive paradigm there is no object that
     * could report this, because there is nothing that holds a commitment.
     */
    private val myFracTimeOnTask = TWResponse(this, "${this.name}:FracTimeOnTask")
    override val fracTimeOnTask: TWResponseCIfc
        get() = myFracTimeOnTask

    private val myNumTasksCompleted = Counter(this, "${this.name}:NumTasksCompleted")

    private val myLoadsPerTour: Response? =
        if (loadCapacity <= 1) null else Response(this, "${this.name}:LoadsPerTour")

    /**
     * How many loads each completed round carried.
     *
     * The tour-level twin of the body's `LoadsPerLoadedMove`, and the two answer the same question
     * at different scales: whether the room is being used, and whether the *dispatcher* is filling
     * it. A fleet whose loaded moves average two but whose tours average two as well is
     * consolidating within a round and never batching across one, which is a dispatching finding
     * rather than a physical one.
     *
     * It is here rather than on the body because a tour is not physical: a substrate knows nothing
     * about rounds, only about journeys.
     */
    override val loadsPerTour: ResponseCIfc?
        get() = myLoadsPerTour

    /** Records what a finished round carried. Called once per tour, whatever it was for. */
    internal fun tourCompleted(loadsCarried: Int) {
        val r = myLoadsPerTour ?: return
        r.value = loadsCarried.toDouble()
    }

    // ---- how much of the room was used ---------------------------------------------------------
    //
    // The body registers these and the vehicle exposes them, for the same reason it exposes the
    // body's time fractions: the body is `internal`, so without these a modeller could read the
    // rows only off the report by name. All four are **null below a capacity of two**, which is the
    // same statement the rows themselves make by not being registered -- a row measuring something
    // a model does not have is a question its reader has to answer every time they meet it.

    /** The mean number aboard, or null for a single-load vehicle. */
    override val numLoadsAboardResponse: TWResponseCIfc?
        get() = body.numLoadsAboardResponse

    /**
     * How much of the room was used, time-weighted, or null for a single-load vehicle.
     *
     * The row `FracTimeTransporting` is often read as answering this and does not: that is a
     * fraction of *time*, and reads 1.0 whether the vehicle is carrying one load or four.
     */
    override val capacityUtilization: TWResponseCIfc?
        get() = body.capacityUtilization

    /**
     * How much of the time it was full, or null for a single-load vehicle.
     *
     * Mean utilization cannot answer whether the capacity binds: a fleet at 50% could be
     * alternately empty and full, which wants more vehicles, or steadily half full, which wants
     * smaller ones.
     */
    val fracTimeAtCapacity: TWResponseCIfc?
        get() = body.fracTimeAtCapacity

    /**
     * How many loads a loaded move carried, or null for a single-load vehicle.
     *
     * Whether consolidation is actually happening. A capacity-four fleet averaging 1.02 has the
     * room and is not using it. [loadsPerTour] asks the same question one scale up.
     */
    val loadsPerLoadedMove: ResponseCIfc?
        get() = body.loadsPerLoadedMove

    // ---- what the vehicle is doing right now ----------------------------------------------------
    //
    // The body's remaining readable quantities, exposed for the same reason the rows above are: the
    // body is `internal`, so without these a modeller could not ask. All are read-only. What is
    // deliberately *not* forwarded is the body's `seizable` and `movementQueue` -- a `Resource` and
    // a `HoldQueue` -- because handing those out would let a model seize this vehicle as though it
    // were a tool, or suspend something in its movement queue, and replacing both of those is what
    // the subsystem exists to do.

    /** True when there is no room for another load. The complement of [spareCapacity] being zero. */
    val isAtCapacity: Boolean
        get() = body.isAtCapacity

    /**
     * What is aboard, in the order it came aboard.
     *
     * A read-only view. Loads get on and off through a stop action's `takeAboard` and `setDown`
     * and through the transport protocol -- those are where every per-load interval this subsystem
     * reports is recorded, so a model that moved a load any other way would run and be missing from
     * every statistic.
     */
    val manifest: List<ProcessModel.Entity>
        get() = body.manifest

    /**
     * How long this vehicle has been blocked so far this replication.
     *
     * The running total behind [fracTimeBlocked], for a model that wants the quantity rather than
     * the fraction -- a maintenance rule, say, or a stopping condition. Reads zero for the whole run
     * on a substrate where nothing blocks.
     */
    val cumulativeBlockedTime: Double
        get() = body.cumulativeBlockedTime

    /** How fast it is going now, which is not the mean of its velocity distribution. */
    val currentVelocity: Double
        get() = body.currentVelocity

    /**
     * How many units of discretised space it has entered this replication.
     *
     * A guide path counts zones. A substrate that does not discretise space counts nothing and this
     * reads zero, which is what "this model has no zones" should look like rather than something to
     * be explained.
     */
    val zonesEntered: Int
        get() = body.zonesEntered

    /**
     * True while somebody is moving it rather than it moving itself.
     *
     * A vehicle under tow is exempt from its own faults, so a listener or a policy that wants to
     * know whether a stopped vehicle is being dealt with asks this rather than inferring it.
     */
    val isUnderTow: Boolean
        get() = body.isUnderTow
    val numTasksCompleted: CounterCIfc
        get() = myNumTasksCompleted

    internal fun taskStarted() {
        myFracTimeOnTask.value = 1.0
    }

    /** The vehicle is no longer on task, however that came about -- delivered or abandoned. */
    internal fun taskEnded() {
        myFracTimeOnTask.value = 0.0
    }

    /** The vehicle delivered. Separate from [taskEnded] because a tour abandoned part-way through
     *  ends without completing, and counting it would let a fleet report more deliveries than there
     *  were loads. */
    internal fun taskCompleted() {
        myNumTasksCompleted.increment()
    }


    // ---- charge ---------------------------------------------------------------------------------

    /**
     * How far this vehicle has travelled in this replication, in the layout's own length units.
     *
     * An odometer on the body, where the movement happens, so every paradigm and every substrate
     * has it. Includes as much of a journey still under way as the substrate can account for.
     */
    val distanceTravelled: Double
        get() = body.distanceTravelled

    /**
     * How long this vehicle has been anything other than idle in this replication.
     *
     * Moving, blocked, loading and unloading count; standing with nothing to do does not. Distinct
     * from elapsed time, and the distinction is what lets a service or wear model be written
     * against hours in operation rather than hours on the wall.
     */
    val operatingTime: Double
        get() = body.operatingTime

    /** Charge added at chargers, and the instant the depletion clock started, both per replication. */
    private var chargeAdded: Double = 0.0
    private var chargeClockStartedAt: Double = 0.0
    private var lowestCharge: Double = Double.MAX_VALUE

    /**
     * The charge account, which may run negative.
     *
     * Kept unclamped because clamping loses the arithmetic. A vehicle that stands flat for an hour
     * has drawn an hour of hotel load it did not have; topping it up to full at a charger must
     * cancel that draw, and it can only do so if the overdraft was recorded. [stateOfCharge] is
     * what a modeller reads.
     */
    private val rawCharge: Double
        get() {
            val b = battery ?: return Double.NaN
            return b.initialCharge + chargeAdded -
                    body.distanceTravelled * b.chargePerDistance -
                    (time - chargeClockStartedAt) * b.chargePerTime
        }

    /**
     * How much charge is left, or NaN for a vehicle with no battery.
     *
     * Derived from the odometers whenever it is asked for rather than stepped by events, so reading
     * it costs nothing and never lags. Never below zero: a flat battery is flat.
     */
    val stateOfCharge: Double
        get() = battery?.let { rawCharge.coerceIn(0.0, it.capacity) } ?: Double.NaN

    /** [stateOfCharge] as a fraction of capacity, or NaN for a vehicle with no battery. */
    val fractionCharged: Double
        get() = battery?.let { stateOfCharge / it.capacity } ?: Double.NaN

    /** True when the vehicle has no charge left. Always false for a vehicle with no battery. */
    val isFlat: Boolean
        get() = battery != null && rawCharge <= 0.0

    /**
     * True while the vehicle is stopped mid-route because it ran out of charge.
     *
     * On a substrate where vehicles share space it stands there for the rest of the replication,
     * obstructing everything behind it, which is what running flat on a guide path actually costs.
     * See [ksl.modeling.fleet.policies.ChargeReservePolicy] for the guard that stops it happening.
     */
    val isStranded: Boolean
        get() = body.isHalted

    private val myFracTimeCharging: TWResponse? =
        if (battery == null) null else TWResponse(this, "${this.name}:FracTimeCharging")

    /** The fraction of time spent on a charger. Registered only for a vehicle that has a battery. */
    val fracTimeCharging: TWResponseCIfc?
        get() = myFracTimeCharging

    private val myNumChargingSessions: Counter? =
        if (battery == null) null else Counter(this, "${this.name}:NumChargingSessions")

    /** How many times the vehicle charged. Registered only for a vehicle that has a battery. */
    val numChargingSessions: CounterCIfc?
        get() = myNumChargingSessions

    private val myNumTimesStranded: Counter? =
        if (battery == null) null else Counter(this, "${this.name}:NumTimesStranded")

    /**
     * How many times the vehicle stopped mid-route for want of charge.
     *
     * It can be more than one per replication only if something restarts a stranded vehicle;
     * nothing in this subsystem does, so in practice it is zero or one, and one is a modelling
     * failure rather than an outcome to average.
     */
    val numTimesStranded: CounterCIfc?
        get() = myNumTimesStranded

    private val myMinStateOfCharge: Response? =
        if (battery == null) null else Response(this, "${this.name}:MinStateOfCharge")

    /**
     * The lowest charge the vehicle was seen to hold during the replication, one observation per
     * replication.
     *
     * Sampled where charge is checked -- at the substrate's decision points and around charging --
     * rather than continuously, because a quantity derived from odometers has no events of its own
     * to sample at. Between two decision points it falls monotonically, so the readings bracket the
     * true minimum to within one of them. A substrate with coarser decision points brackets it
     * less tightly, which is the honest consequence of asking less often.
     */
    val minStateOfCharge: ResponseCIfc?
        get() = myMinStateOfCharge

    /**
     * The gate the substrate asks at every decision point of a journey.
     *
     * Two reasons a vehicle may not carry on, checked in the order that matters: a vehicle with no
     * charge cannot be repaired into moving again, so exhaustion is decided first and a failure due
     * at the same instant stays due until the vehicle moves -- which, for a flat one, is never.
     *
     * A substrate with no decision points never asks, and a vehicle on one runs flat or breaks down
     * at the end of its journey rather than part way through it.
     */
    private fun mayContinuePastBoundary(): Boolean {
        // A vehicle being pushed is not deciding anything and is not driving. Neither its battery
        // nor the failure it is being pushed away from may stop it part way to the refuge.
        if (body.isUnderTow) return true
        observeCharge()
        if (isFlat) {
            myNumTimesStranded?.increment()
            logger.warn {
                "Vehicle ($name) ran out of charge at ($currentLocationName) during replication " +
                        "${model.currentReplicationNumber} and stopped where it stands. Its " +
                        "interruption policy decides what happens next; the default leaves it " +
                        "there, in the way of anything routed through where it stands. A " +
                        "ChargeReservePolicy is what prevents this arising at all."
            }
            pendingInterruption = outOfChargeInterruption()
            return false
        }
        if (isFailureDue()) {
            pendingInterruption = bookFailure()
            return false
        }
        return true
    }

    private fun observeCharge() {
        if (battery == null) return
        val soc = stateOfCharge
        if (soc < lowestCharge) lowestCharge = soc
    }

    /** Puts the vehicle on a charger and reports how long it must stay to fill the battery. */
    internal fun beginCharging(): Double {
        val b = battery ?: return 0.0
        observeCharge()
        myFracTimeCharging?.value = 1.0
        // Net of the hotel load, which keeps drawing while the vehicle is on the charger. The
        // battery refuses a charging rate that does not outpace it, so this is positive.
        return ((b.capacity - rawCharge) / (b.chargingRate - b.chargePerTime)).coerceAtLeast(0.0)
    }

    /** Takes the vehicle off the charger, full. */
    internal fun endCharging() {
        val b = battery ?: return
        chargeAdded += b.capacity - rawCharge
        myFracTimeCharging?.value = 0.0
        myNumChargingSessions?.increment()
        observeCharge()
    }


    // ---- failure and repair ---------------------------------------------------------------------

    private val myBetweenFailures: RandomVariable? = failureModel?.let {
        RandomVariable(this, it.betweenFailures, name = "${this.name}:BetweenFailuresRV")
    }
    private val myRepairTimeRV: RandomVariable? = failureModel?.let {
        RandomVariable(this, it.repairTime, name = "${this.name}:RepairTimeRV")
    }

    /** The value of the basis quantity at which the next failure comes due. */
    private var nextFailureAt: Double = Double.POSITIVE_INFINITY

    /**
     * True while the vehicle is broken down.
     *
     * It keeps its assignment and its load throughout: a failure interrupts the tour, it does not
     * hand the work back. See [FailureModel].
     */
    override var isFailed: Boolean = false
        // Written by the failure model and the service verbs, never by a modeller: a vehicle is
        // broken or withdrawn by something that happened to it, and setting the flag directly would
        // leave the machinery that tracks it disagreeing with the flag.
        internal set

    /** How far the basis quantity has advanced, in whatever units the basis is measured in. */
    private fun basisValue(): Double = when (failureModel?.basis) {
        FailureBasis.CALENDAR_TIME -> time
        FailureBasis.OPERATING_TIME -> body.operatingTime
        FailureBasis.DISTANCE_TRAVELLED -> body.distanceTravelled
        FailureBasis.TASKS_COMPLETED -> myNumTasksCompleted.value
        null -> 0.0
    }

    /** True when a failure has come due and has not yet been acted on. */
    internal fun isFailureDue(): Boolean =
        failureModel != null && !isFailed && basisValue() >= nextFailureAt

    private val myFracTimeFailed: TWResponse? =
        if (failureModel == null) null else TWResponse(this, "${this.name}:FracTimeFailed")

    /** The fraction of time the vehicle was broken down. Registered only if it can fail. */
    val fracTimeFailed: TWResponseCIfc?
        get() = myFracTimeFailed

    private val myNumFailures: Counter? =
        if (failureModel == null) null else Counter(this, "${this.name}:NumFailures")

    /** How many times the vehicle failed. Registered only if it can fail. */
    val numFailures: CounterCIfc?
        get() = myNumFailures

    private val myTimeOutOfService: Response? =
        if (failureModel == null) null else Response(this, "${this.name}:TimeOutOfService")

    /**
     * How long the vehicle was out of service, one observation per failure.
     *
     * The whole procedure, not the repair alone: the wait for a technician, the walk to the
     * vehicle, the assessment and any tow are all time the vehicle was not working, and a row named
     * for the repair while measuring all of it would say the wrong thing. What the repair itself
     * costs is the failure model's own `repairTime` random variable, which is what the default
     * policy delays for.
     */
    val timeOutOfService: ResponseCIfc?
        get() = myTimeOutOfService

    /**
     * True when anything is aboard.
     *
     * Read from the body's manifest rather than inferred from a task's state. The two agree while a
     * vehicle carries at most one load, and only the manifest keeps agreeing when it carries more.
     */
    val isCarryingALoad: Boolean
        get() = body.isCarryingLoad

    /** How many loads are aboard. */
    val numLoadsAboard: Int
        get() = body.numLoadsAboard

    /** How many more loads this vehicle could take. */
    val spareCapacity: Int
        get() = body.spareCapacity

    // ---- riders ------------------------------------------------------------------------------
    //
    // A vehicle carries two kinds of thing and the difference is who asked. A *task* was posted to
    // a dispatcher, which decided that this vehicle would serve it. A *ride* was not posted to
    // anybody: somebody stood at a stop and got on whatever came by. Both are on the same manifest,
    // because the manifest is about what is physically aboard and the physics does not care; the
    // list here is the part that had no task, which is what a stop action reads to decide who gets
    // off.

    private val myRides = mutableListOf<Stop.Ride>()

    /** Who is aboard without a task, in the order they boarded. */
    val ridersAboard: List<Stop.Ride>
        get() = myRides

    /**
     * Who aboard is bound for [location].
     *
     * Read by an alighting action to decide who gets off, and by a stop control before deciding to
     * run past a stop -- so that a policy which will not carry anybody past their stop can say so.
     */
    fun ridersBoundFor(location: String): List<Stop.Ride> =
        myRides.filter { it.destination == location }

    internal fun rideBoarded(ride: Stop.Ride) {
        myRides.add(ride)
    }

    internal fun rideAlighted(ride: Stop.Ride) {
        myRides.removeAll { it === ride }
    }

    // ---- what expressing costs -----------------------------------------------------------------
    //
    // A skipped stop has two victims and they are different, so they are counted apart. The stop
    // records that a vehicle went past it; these record what the *vehicle* did and to whom.

    private val myNumStopsSkipped = Counter(this, "${this.name}:NumStopsSkipped")

    /** How much expressing this vehicle did. Zero for a fleet whose control never says `Skip`. */
    val numStopsSkipped: CounterCIfc
        get() = myNumStopsSkipped

    private val myNumCarriedPast = Counter(this, "${this.name}:NumCarriedPast")

    /**
     * Loads that were aboard, bound for a stop this vehicle was told past, and stayed on.
     *
     * Being carried past your stop is a service failure and one of the things a route study exists
     * to measure, which is why skipping a stop with somebody aboard bound for it is permitted
     * rather than refused. A control that will not do it can see who is aboard, through
     * [ridersBoundFor], and decline.
     */
    val numCarriedPast: CounterCIfc
        get() = myNumCarriedPast

    internal fun stopSkipped(ridersBoundThere: Int) {
        myNumStopsSkipped.increment()
        if (ridersBoundThere > 0) myNumCarriedPast.increment(ridersBoundThere.toDouble())
    }

    /**
     * Books a failure: counts it, starts the out-of-service clock, and draws the repair time.
     *
     * The draw happens here rather than inside the policy so that a policy which surrounds the
     * repair with travel and waiting uses the same number the default would have used, and one
     * which ignores it is visibly choosing to.
     */
    private fun bookFailure(): Interruption.Failed {
        val duration = myRepairTimeRV!!.value
        isFailed = true
        myFracTimeFailed?.value = 1.0
        myNumFailures?.increment()
        outOfServiceSince = time
        return failureInterruption(
            failureNumber = myNumFailures?.value?.toInt() ?: 0,
            repairTime = duration
        )
    }

    private var pendingInterruption: Interruption? = null

    /** True when a movement gate stopped this vehicle and nothing has dealt with it yet. */
    internal val hasPendingInterruption: Boolean
        get() = pendingInterruption != null
    private var outOfServiceSince: Double = Double.NaN

    /**
     * The interruption the vehicle's agent must deal with before it does anything else, or null.
     *
     * Two things reach the agent through here and they are deliberately the same thing. One is an
     * interruption a movement gate raised part way through a journey, which is waiting to be
     * collected. The other is a failure that has come due while the vehicle was between tours, which
     * is booked now -- at a point where the vehicle has *not yet declared itself available*, so
     * nothing can be assigned to a vehicle under repair without anything having to check for it.
     */
    internal fun takeInterruption(): Interruption? {
        val taken = pendingInterruption?.also { pendingInterruption = null }
            ?: if (isFailureDue()) bookFailure() else null
            ?: return null
        // Out of service from here until its policy has run and the vehicle has been found fit
        // again. Set before anybody is told, so a listener that reads the fleet counts sees the
        // vehicle already accounted for rather than still counted as spare capacity.
        isOutOfService = true
        // Out of the dispatcher's pool as well. A vehicle repositioning under a disposition is
        // deliberately left assignable while it moves, so one that breaks down on the way home
        // would otherwise still be offered work it cannot start. It declares itself again at the
        // top of its own loop once its policy has put it right.
        system.dispatcher.withdraw(this)
        system.refreshFleetCounts()
        system.notifyStopped(taken)
        return taken
    }

    /**
     * Closes the procedure the policy has just finished.
     *
     * A repair policy returns when the vehicle is repaired, so the framework clears the failure
     * rather than making every policy remember to. `FracTimeFailed` and `TimeOutOfService`
     * therefore span the whole procedure -- the wait for a technician, the walk to the vehicle, the
     * assessment and the tow included -- which is what being out of service actually costs.
     */
    internal fun interruptionEnded(interruption: Interruption) {
        if (interruption is Interruption.Failed && isFailed) {
            isFailed = false
            myFracTimeFailed?.value = 0.0
            myCumulativeFailedTime += time - outOfServiceSince
            myTimeOutOfService?.value = time - outOfServiceSince
            outOfServiceSince = Double.NaN
            nextFailureAt = basisValue() + myBetweenFailures!!.value
        }
        if (isFitToContinue) {
            isOutOfService = false
            system.refreshFleetCounts()
            system.notifyReturnedToService(interruption, time - interruption.at)
        } else {
            // Stays out of service, and stays counted that way: nothing is going to change its
            // mind for the rest of the replication.
            system.notifyOutOfService(interruption)
        }
    }

    /**
     * True from the moment the vehicle stops until its policy has put it right -- and thereafter,
     * for good, if the policy did not.
     *
     * Distinct from [isFailed], which is about breakdowns alone: a vehicle stopped for a flat
     * battery is out of service without having failed. It is what [FleetSystem.numVehiclesOutOfService]
     * counts, and it is why a vehicle that broke down between tours is no longer reported as idle.
     */
    override var isOutOfService: Boolean = false
        // Written by the failure model and the service verbs, never by a modeller: a vehicle is
        // broken or withdrawn by something that happened to it, and setting the flag directly would
        // leave the machinery that tracks it disagreeing with the flag.
        internal set

    /**
     * Whether the vehicle can carry on from where it stands.
     *
     * Asked by the framework the moment an interruption policy returns, and it is the same question
     * the movement gate asks -- deliberately, so that a policy cannot claim to have fixed something
     * it did not. A repair that finished leaves a repaired vehicle and it carries on. A charge
     * policy that did nothing leaves a flat vehicle and it does not.
     */
    internal val isFitToContinue: Boolean
        get() = !isFlat && !isFailed

    private var myCumulativeFailedTime: Double = 0.0

    /**
     * How long this vehicle has been out of service in this replication, including a procedure
     * still under way.
     *
     * Differences of this between two instants give the out-of-service time within a journey, which
     * is how a delivered load's `failedTime` is computed. Same pattern, and for the same reason, as
     * the body's cumulative blocked time.
     */
    internal val cumulativeFailedTime: Double
        get() = if (outOfServiceSince.isNaN()) myCumulativeFailedTime
        else myCumulativeFailedTime + (time - outOfServiceSince)

    // ---- towing ----------------------------------------------------------------------------------

    /**
     * Starts a tow. See `tow`, which is the verb a policy calls.
     *
     * The journey itself is the substrate's, because being pushed somewhere by somebody else is
     * still a journey through whatever the vehicle moves through. A substrate that cannot express
     * one answers null and the vehicle stays where it is -- which is what a policy that tries to
     * tow on such a substrate should see, rather than a silent teleport.
     */
    internal fun beginTow(
        location: String,
        velocity: Double,
        waiter: ProcessModel.Entity
    ): HoldQueue? {
        body.towVelocity = velocity
        val queue = towJourney(location, waiter)
        // Already there, or the substrate has no tow. Nothing was started, so nothing is under tow.
        if (queue == null) body.towVelocity = null
        return queue
    }

    /**
     * Who is standing still right now because this vehicle is in their way.
     *
     * Empty by default, which is the answer for any substrate where vehicles do not share space --
     * and is what makes [Interruption.obstructed] a question the fleet layer can ask without
     * knowing what it is running on.
     */
    internal open fun vehiclesObstructed(): List<FleetVehicle> = emptyList()

    /** Commands the substrate to move this vehicle while it is under tow. */
    protected abstract fun towJourney(location: String, waiter: ProcessModel.Entity): HoldQueue?

    /**
     * Builds the interruption for a breakdown, with whatever facts about *where it stopped* the
     * substrate can supply -- what space it is denying to others, and what it was doing.
     */
    protected abstract fun failureInterruption(
        failureNumber: Int,
        repairTime: Double
    ): Interruption.Failed

    /** Builds the interruption for a flat battery. See [failureInterruption]. */
    protected abstract fun outOfChargeInterruption(): Interruption.OutOfCharge

    /** Ends a tow, whether or not the vehicle actually had to go anywhere. */
    internal fun endTow() = body.endTow()

    /**
     * Commands the body toward a location and reports whether a journey is now under way.
     *
     * **Does not suspend**, and that is deliberate: the `hold` that waits for arrival stays visible
     * at the call site in the control loop, where a reader of the loop can see every point at which
     * simulated time passes and the world can change underneath the vehicle.
     *
     * Not `sendTo`, which refuses while the body is allocated. That refusal is right for its own
     * purpose -- a passive transporter belonging to an entity must not wander off -- but it makes
     * `sendTo` unusable for a vehicle that has seized its own body to record commitment.
     *
     * @param waiter the vehicle's own agent, which is what arrival resumes. Never the load: the
     *   load waits in this subsystem's hold queue, and passing it here would produce a model that
     *   works while attributing the riding time to the space layer's queue instead.
     * @return the movement queue to suspend the waiter in, or null when the body was already there
     */
    /**
     * How this vehicle moves, as the fleet's machinery sees it.
     *
     * Typed as the seam rather than as the body, and that is the point: the control loop, the tour
     * and the dispatcher are written against what any movement substrate can do, so the same
     * machinery drives a vehicle on a guide path, on a continuous projection, and on a free path
     * without knowing which it has.
     */
    internal val movement: VehicleMovementIfc
        get() = body

    internal fun beginTravelTo(
        location: String,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity
    ): HoldQueue? = movement.beginTravelTo(
        system.space.requireLocation(location), purpose, waiter
    )

    override fun initialize() {
        myRides.clear()
        myFracTimeOnTask.value = 0.0
        chargeAdded = 0.0
        chargeClockStartedAt = time
        lowestCharge = Double.MAX_VALUE
        myFracTimeCharging?.value = 0.0
        isFailed = false
        myFracTimeFailed?.value = 0.0
        pendingInterruption = null
        isOutOfService = false
        outOfServiceSince = Double.NaN
        myCumulativeFailedTime = 0.0
        nextFailureAt = if (myBetweenFailures == null) Double.POSITIVE_INFINITY
        else basisValue() + myBetweenFailures.value
        // One gate, because a gate is a veto and two would need a rule for disagreeing. Installed
        // only when something can actually refuse, so a vehicle that neither runs on a battery nor
        // breaks down is asked nothing at all.
        if (battery != null || failureModel != null) {
            body.attachContinuationGate { mayContinuePastBoundary() }
        }
    }

    /**
     * Records the replication's lowest charge.
     *
     * Here rather than in `afterReplication` because this runs while the replication is still live,
     * and a `Response` summarizes in `afterReplication` whatever was observed during the run. A
     * replication in which the vehicle never moved observed nothing, and its initial charge is the
     * honest reading for it.
     */
    override fun replicationEnded() {
        super.replicationEnded()
        if (battery == null) return
        observeCharge()
        myMinStateOfCharge?.value = if (lowestCharge == Double.MAX_VALUE) stateOfCharge else lowestCharge
    }

    override fun toString(): String =
        "${this::class.simpleName}($name, at=$currentLocationName, assignment=${currentAssignment})"
}
