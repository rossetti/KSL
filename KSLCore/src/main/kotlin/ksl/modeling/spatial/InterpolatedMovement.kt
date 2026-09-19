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
package ksl.modeling.spatial

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * The geometry a journey is made through: where the vehicle is, how far things are, and how to say
 * where *between* two places is.
 *
 * The half of movement that differs between substrates. [InterpolatedMovement] is the other half --
 * the clockwork -- and it is written once because everything substrate-specific is here.
 *
 * **[positionAlong] may answer null, and that is a supported answer rather than a failure.** A plane
 * can say where a third of the way from A to B is; a table of pairwise distances cannot, and neither
 * can a great-circle model without spherical interpolation nobody has asked for. A geometry that
 * cannot say makes a journey one step: the vehicle waits out the whole leg and arrives, which is
 * exactly what a free-path move has always done.
 */
interface MovePathIfc {

    /** Where the vehicle is at this instant. */
    val positionNow: LocationIfc

    /** How far apart two places are, by this geometry's own metric. */
    fun distanceBetween(from: LocationIfc, to: LocationIfc): Double

    /** True when the vehicle could get to [destination] at all. */
    fun isReachable(destination: LocationIfc): Boolean

    /**
     * Where [fraction] of the way from [from] to [to] is, or null when this geometry cannot say.
     *
     * @param fraction between 0.0 and 1.0
     */
    fun positionAlong(from: LocationIfc, to: LocationIfc, fraction: Double): LocationIfc?

    /** Puts the vehicle at [location]. The only way this machinery moves anything. */
    fun placeAt(location: LocationIfc)
}

/**
 * The clockwork of a journey: plan a step, wait for it, advance, plan the next.
 *
 * **Below every substrate, because every substrate needs it.** A guide path discretises a journey at
 * zone boundaries, which its geometry fixes; a continuous projection and a free path discretise it
 * at interpolation steps, which the modeller chooses. The second and third are the same clockwork
 * over different geometry, and this is that clockwork, in the package both of them are already
 * above.
 *
 * **It is driven by events the vehicle owns, not by the traveller's process.** That is what
 * [VehicleMovementIfc] requires and why it could not be built on a suspending integration loop: a
 * fleet's control loop has to be able to command a vehicle from somewhere other than that vehicle's
 * process, and the two ends of a wait must not disagree about where the wake will come from. So
 * [beginTravelTo] commands and returns a queue, and whoever is waiting is resumed from it.
 *
 * **A step is planned before it is waited for.** The direction and the distance are fixed when the
 * event is scheduled and applied when it fires, so the vehicle covers the ground the elapsed time
 * paid for. A redirection arriving mid-step therefore costs the step it interrupts rather than
 * being free, and is observed at most `stepSize/velocity` later -- the same latency a guide path has
 * at its next boundary, and for the same reason: something between two places cannot stop and turn.
 *
 * @param parent the vehicle this belongs to. A model element of its own so that it can schedule
 *   its own steps and be reset between replications without the vehicle remembering to
 * @param path the geometry to move through
 * @param stepSize how far apart the decision points are, in the geometry's own distance units.
 *   Ignored where the geometry cannot interpolate, since there the whole leg is one step
 * @param velocityOf how fast the vehicle is going, asked once per step so that a vehicle whose
 *   speed depends on what it is carrying answers for itself
 * @param onJourneyEnded told whenever a journey stops, by arrival or by [halt], and told before
 *   whoever was waiting is woken. For a vehicle that keeps its own flags about whether it is moving
 */
class InterpolatedMovement @JvmOverloads constructor(
    parent: ModelElement,
    private val path: MovePathIfc,
    var stepSize: Double,
    private val velocityOf: () -> Double,
    private val onJourneyEnded: (() -> Unit)? = null,
    name: String? = null
) : ModelElement(parent, name ?: "${parent.name}:Movement") {

    init {
        require(stepSize > 0.0) { "The step size must be > 0.0, but was $stepSize." }
    }

    /** Where whoever is waiting for this vehicle waits. Reports nothing; it is plumbing. */
    val travelQ: HoldQueue = HoldQueue(this, "${this.name}:TravelQ")

    init {
        travelQ.waitTimeStatOption = false
        travelQ.defaultReportingOption = false
    }

    /** Where the journey in progress is going, or null when there is none. */
    var destination: LocationIfc? = null
        private set

    private var waiter: ProcessModel.Entity? = null
    private var stepEvent: KSLEvent<Nothing>? = null

    // The leg is remade at every redirection, so a fraction is always measured from where the
    // vehicle actually set out on the leg it is making rather than from where the journey began.
    private var legFrom: LocationIfc? = null
    private var legLength: Double = 0.0
    private var travelledOnLeg: Double = 0.0
    private var plannedStep: Double = 0.0
    private var plannedTarget: LocationIfc? = null
    private var stepStartedAt: Double = Double.NaN

    private var myDistanceTravelled: Double = 0.0
    private var myOperatingTime: Double = 0.0
    private var myHalted: Boolean = false

    /** True while the vehicle is stopped short of where it was going, with nothing scheduled. */
    val isHalted: Boolean
        get() = myHalted

    /** True while a journey is under way, whether or not it is halted. */
    val isTravelling: Boolean
        get() = destination != null

    /**
     * A veto asked at every step boundary: may the vehicle go on past this one?
     *
     * What a guide path's movement gate is, on a substrate whose decision points are interpolation
     * steps rather than zone boundaries. Answering false stops the vehicle where it stands, exactly
     * as [halt] does -- so a flat battery or a breakdown is discovered part way through a journey
     * rather than at the end of it, which is the whole reason a substrate has decision points at
     * all. Null asks nothing, which is the default and costs nothing.
     */
    var continuationGate: (() -> Boolean)? = null

    /** How far the vehicle has travelled this replication. Never decreases. */
    val distanceTravelled: Double
        get() = myDistanceTravelled

    /** How long it has spent travelling this replication. Never decreases. */
    val operatingTime: Double
        get() = myOperatingTime

    /**
     * Sends the vehicle to [to] and hands back the queue to wait in.
     *
     * A second call while a journey is under way is a **redirection**, not an error: the target
     * changes and the running step chain re-plans from wherever the vehicle is when its current step
     * completes. The odometer keeps growing across it, because a vehicle that turns round has still
     * covered the ground it covered.
     *
     * @return the queue to suspend [waitingFor] in, or null when the vehicle was already there
     */
    fun beginTravelTo(to: LocationIfc, waitingFor: ProcessModel.Entity): HoldQueue? {
        require(path.isReachable(to)) {
            "(${this.name}) cannot reach (${to.name}) through its own geometry."
        }
        val underway = isTravelling && !myHalted
        if (!underway && path.distanceBetween(path.positionNow, to) <= ARRIVAL_TOLERANCE) return null
        waiter = waitingFor
        destination = to
        myHalted = false
        // A journey already stepping re-plans at its next boundary, which is what makes a second
        // call a redirection rather than a restart.
        if (stepEvent == null) planLeg()
        return travelQ
    }

    /**
     * Stops the vehicle where it stands and wakes whoever was waiting for it.
     *
     * Whoever called this owns starting it again, through [resumeHalted] or a fresh [beginTravelTo].
     * Harmless when no journey is under way.
     */
    fun halt() {
        if (!isTravelling || myHalted) return
        cancelPendingStep()
        // The vehicle was moving right up to this instant, so it is credited with the part of the
        // step it had actually made. A *redirection* completes the step it interrupts instead,
        // because a redirection does not stop the vehicle and something between two places cannot
        // stop and turn; a halt does stop it, here, now.
        creditPartOfAStep()
        myHalted = true
        onJourneyEnded?.invoke()
        endTheWait()
    }

    /** Starts a halted vehicle again from where it stopped. Harmless on one that is not halted. */
    fun resumeHalted() {
        if (!myHalted) return
        myHalted = false
        if (isTravelling && stepEvent == null) planLeg()
    }

    /** Forgets any journey and zeroes the odometers, at the start of every replication. */
    override fun initialize() {
        super.initialize()
        cancelPendingStep()
        destination = null
        plannedTarget = null
        legFrom = null
        waiter = null
        myHalted = false
        myDistanceTravelled = 0.0
        myOperatingTime = 0.0
        travelledOnLeg = 0.0
    }

    /**
     * Records a journey this machinery did not make.
     *
     * For a substrate that also moves the same vehicle another way -- notably the free-path
     * `move()` verbs, which are one delay and have always been -- so that a vehicle's odometers
     * are about the vehicle rather than about which verb happened to move it.
     */
    fun recordExternalMove(distance: Double, duration: Double) {
        require(distance >= 0.0) { "A recorded distance cannot be negative, but was $distance." }
        require(duration >= 0.0) { "A recorded duration cannot be negative, but was $duration." }
        myDistanceTravelled += distance
        myOperatingTime += duration
    }

    /**
     * Takes the step in flight off the calendar, if there is one that is still on it.
     *
     * The guard matters at the start of a replication: a vehicle that was mid-journey when the
     * previous horizon fell still holds a reference to a step that the executive has already
     * discarded, and cancelling an event that is not scheduled raises. Found by the first study to
     * run more than one replication with a vehicle still moving at the end of it.
     */
    private fun cancelPendingStep() {
        val e = stepEvent
        stepEvent = null
        if (e != null && e.isScheduled) e.cancel = true
    }

    /** Starts a leg from wherever the vehicle is now to wherever it is now going. */
    private fun planLeg() {
        val to = destination ?: return
        val from = path.positionNow
        legFrom = from
        legLength = path.distanceBetween(from, to)
        travelledOnLeg = 0.0
        if (legLength <= ARRIVAL_TOLERANCE) {
            arrive(to)
            return
        }
        planNextStep()
    }

    private fun planNextStep() {
        val to = destination ?: return
        val remaining = legLength - travelledOnLeg
        if (remaining <= ARRIVAL_TOLERANCE) {
            arrive(to)
            return
        }
        // A geometry that cannot say where between two places is makes the whole leg one step. The
        // vehicle then waits out the journey and arrives, which is what a free-path move has always
        // done -- and its position is honestly the place it set out from until it gets there.
        val canInterpolate = path.positionAlong(legFrom!!, to, 0.5) != null
        plannedStep = if (canInterpolate) minOf(stepSize, remaining) else remaining
        plannedTarget = to
        stepStartedAt = time
        stepEvent = schedule(this::advance, plannedStep / velocityOf())
    }

    /**
     * Books the fraction of the step in flight that the elapsed time paid for.
     *
     * Without it a vehicle stopped between two decision points would report the position it had at
     * the last one, and its odometer would be short by however far it got after that -- which is
     * the same staleness the seam exists to prevent, arriving through the back door.
     */
    private fun creditPartOfAStep() {
        val from = legFrom ?: return
        val to = plannedTarget ?: return
        if (stepStartedAt.isNaN()) return
        val covered = minOf(plannedStep, (time - stepStartedAt) * velocityOf())
        if (covered <= 0.0) return
        travelledOnLeg += covered
        myDistanceTravelled += covered
        myOperatingTime += time - stepStartedAt
        val fraction = (travelledOnLeg / legLength).coerceIn(0.0, 1.0)
        path.positionAlong(from, to, fraction)?.let { path.placeAt(it) }
        plannedStep = 0.0
        stepStartedAt = Double.NaN
    }

    @Suppress("UNUSED_PARAMETER")
    private fun advance(event: KSLEvent<Nothing>) {
        stepEvent = null
        stepStartedAt = Double.NaN
        val planned = plannedTarget ?: return
        val from = legFrom ?: return
        travelledOnLeg += plannedStep
        myDistanceTravelled += plannedStep
        myOperatingTime += plannedStep / velocityOf()
        val fraction = (travelledOnLeg / legLength).coerceIn(0.0, 1.0)
        val next = if (fraction >= 1.0 - 1e-12) planned else path.positionAlong(from, planned, fraction)
        if (next != null) path.placeAt(next)
        // Asked here, at the step boundary, and before the next step is planned: a vehicle told it
        // may not go on stops where this step left it.
        if (continuationGate?.invoke() == false) {
            halt()
            return
        }
        // Re-planning from here is what observes a redirection: `destination` may no longer be the
        // target this step was planned against.
        val to = destination
        when {
            to == null -> Unit
            to !== planned -> planLeg()                     // redirected while this step was in flight
            travelledOnLeg >= legLength - ARRIVAL_TOLERANCE -> arrive(to)
            else -> planNextStep()
        }
    }

    private fun arrive(at: LocationIfc) {
        path.placeAt(at)
        destination = null
        plannedTarget = null
        legFrom = null
        onJourneyEnded?.invoke()
        endTheWait()
    }

    private fun endTheWait() {
        val w = waiter ?: return
        waiter = null
        if (travelQ.contains(w)) travelQ.removeAndResume(w)
    }

    companion object {
        /** Below this a vehicle is treated as being there. In the geometry's own distance units. */
        const val ARRIVAL_TOLERANCE: Double = 1e-9
    }
}
