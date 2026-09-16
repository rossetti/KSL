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

import ksl.controls.KSLStringControl
import ksl.modeling.guidedpath.rules.createCrossingArbiter
import ksl.modeling.guidedpath.rules.nameOfCrossingArbiter
import ksl.modeling.guidedpath.rules.BoundedBatchArbiter
import ksl.modeling.guidedpath.rules.CrossingArbiterIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * A place where people cross guide-path space that vehicles also want.
 *
 * Thin, because the exclusion is not its job. A crossing is **a set of zones, an admission policy,
 * and its own statistics** -- everything about keeping vehicles and people out of each other's way
 * is already the zone's, and this adds no new rule about it.
 *
 * ## Why this is a ModelElement, when a holder is not
 *
 * The contrast is worth stating because it is the one place the two kinds of occupancy meet. A
 * spill cannot be a `ModelElement`: it happens at minute 137.4 on whichever aisle the sample picks,
 * and nothing about it can be declared before the run, which is why [ZoneHolderIfc] is an interface
 * with two members. A crossing is the opposite. It is a piece of the layout -- it has a location, a
 * name, statistics that accumulate across a replication, and a discipline that may carry state
 * between turns -- and there is exactly one of it, known when the model is built. So it is an
 * element, and it gets `initialize()` and a place in the model tree for the same reasons a `Link`
 * would if links were elements.
 *
 * ## How a turn works
 *
 * The mechanism is the one the subsystem already has, used for the turn rather than for the people.
 *
 * 1. The arbiter is asked whether vehicles should be barred. When it says yes the crossing
 *    **requests its zones as a holder** -- so traffic already on them *drains off* through the
 *    ordinary all-or-nothing machinery, and the turn opens on every zone at one instant or on none.
 *    Nothing is evicted and no vehicle is stranded half way across.
 * 2. Once granted, the crossing holds the zones and admits people onto what it holds -- it is a
 *    [ZonePopulationHostIfc], which is the whole of what that interface means. Vehicles are
 *    excluded because the zones are held; people are admitted because the holder is the crossing.
 * 3. The turn ends when the arbiter stops barring vehicles **and the last person is off**. A turn
 *    always finishes: the arbiter can stop admitting, never evict.
 *
 * The design record originally said vehicles would be excluded by the population count rather than
 * by anything holding on the pedestrians' behalf. That does not work, and the reason is worth
 * keeping: a count excludes traffic only once somebody is *already standing in the road*, which is
 * too late to be how a crossing gathers people safely. One holder for the crossing is not the thing
 * that was being warned against -- a holder per pedestrian would have been.
 *
 * ## What it earns its place by absorbing
 *
 * - **The population edges.** A walker that balks, is interrupted, or is destroyed must still leave
 *   the count, or the crossing stays shut to vehicles for the rest of the replication with nobody
 *   on it. This is the single most likely hand-rolling error, and [ProcessModel.Entity] cleans it
 *   up at the same two exits that give back guide-path space.
 * - **Admission control**, which is where the modelling actually lives, and which is a
 *   substitutable [CrossingArbiterIfc] rather than a policy baked in here.
 * - **The statistics**: turns taken, time shut to traffic, how long people wait and how many.
 *
 * @param parent where this sits in the model
 * @param space the guide path whose zones are being crossed
 * @param zones the zones the crossing covers, all on that guide path, distinct, at least one
 * @param arbiter who decides whose turn it is; substitutable, and the point of the construct
 * @param name the crossing's name in the model
 */
class ZoneCrossing(
    parent: ModelElement,
    val space: GuidedPathSpace,
    val zones: List<Zone>,
    var arbiter: CrossingArbiterIfc = BoundedBatchArbiter(batchSize = 3, maxWait = 5.0),
    name: String? = null
) : ModelElement(parent, name), ZonePopulationHostIfc, ZoneHoldActionIfc {

    /**
     * The discipline by name, so a scenario or an app can change it without holding an arbiter.
     *
     * **Only the two priority disciplines have names.** An alternating or bounded-batch arbiter
     * takes the times and counts that *are* its discipline -- a batch of two with a five-minute cap
     * is a different policy from a batch of ten with a one-minute cap, not the same policy tuned
     * differently -- so naming one would mean choosing those numbers on the modeller's behalf and
     * hiding the choice inside a string. Assign the arbiter to use those, and read this property to
     * see what is in force: it reports the arbiter's own `toString` when no name stands for it.
     */
    @set:KSLStringControl(
        allowedValues = ["PedestrianPriority", "VehiclePriority"],
        comment = "Which admission discipline the crossing runs under"
    )
    var arbiterName: String
        get() = nameOfCrossingArbiter(arbiter) ?: arbiter.toString()
        set(value) {
            arbiter = createCrossingArbiter(value)
        }

    init {
        require(zones.isNotEmpty()) { "Crossing (${this.name}) covers no zones at all." }
        require(zones.distinct().size == zones.size) {
            "Crossing (${this.name}) lists the same zone twice: ${zones.joinToString { it.name }}"
        }
        for (zone in zones) {
            require(zone in space.network.zones) {
                "Zone (${zone.name}) is not on guide path (${space.name})."
            }
        }
    }

    /**
     * Never anything. A crossing waits for space through its request, not by queuing on a zone, so
     * it contributes no queuing edge to the wait-for graph.
     */
    final override val awaitedZone: Zone?
        get() = null

    // ---- what the arbiter reads ----------------------------------------------------------------

    /**
     *  One person waiting their turn.
     *
     *  Carries the queue it is suspended in rather than a callback, which is the same shape the
     *  zone verbs use: the crossing decides *who* goes and the process layer does the resuming,
     *  because that is the only part that knows what a suspended entity is.
     *
     *  [mayGo] exists for the walker that has joined but not yet suspended. A crossing already open
     *  should not suspend an arrival at all, so the verb asks, and this is how the answer comes
     *  back without the crossing having to resume a coroutine that has not stopped yet.
     */
    internal class Waiter(
        val arrivedAt: Double,
        val entity: ProcessModel.Entity,
        val queue: HoldQueue
    ) {
        var mayGo: Boolean = false
    }

    private val myWaiting = mutableListOf<Waiter>()

    /** How many are waiting to step on. */
    val numWaitingToCross: Int
        get() = myWaiting.size

    /** How many are on the crossing now. */
    var numOnCrossing: Int = 0
        private set

    /** True while the crossing holds its zones, so no vehicle may enter. */
    val isBarred: Boolean
        get() = space.isHoldingZones(this)

    /** True while the crossing has asked for its zones and traffic is still draining off them. */
    val isOpening: Boolean
        get() = space.isWaitingForZones(this)

    /** How long the one at the head of the queue has been waiting, or zero when nobody is. */
    val longestWaitToCross: Double
        get() = if (myWaiting.isEmpty()) 0.0 else time - myWaiting.first().arrivedAt

    // ---- statistics ----------------------------------------------------------------------------

    private val myTurnsTaken = Counter(this, name = "${this.name}:TurnsTaken")

    /** How many pedestrian turns the crossing opened. */
    val turnsTaken: CounterCIfc
        get() = myTurnsTaken

    private val myCrossingsMade = Counter(this, name = "${this.name}:CrossingsMade")

    /** How many people got across. */
    val crossingsMade: CounterCIfc
        get() = myCrossingsMade

    private val myTimeBarred = TWResponse(this, name = "${this.name}:FracTimeBarred")

    /**
     * The fraction of time the crossing was shut to vehicles, counting the drain as shut.
     *
     * Time-weighted over one and zero, so its average reads directly as a fraction. The drain
     * counts because a vehicle arriving during it is refused exactly as one arriving mid-turn is:
     * from the instant the crossing asks, the zones are no longer traffic's.
     */
    val fracTimeBarred: TWResponseCIfc
        get() = myTimeBarred

    private val myNumWaiting = TWResponse(this, name = "${this.name}:NumWaitingToCross")

    /** How many were waiting to cross, time-weighted. */
    val numWaitingResponse: TWResponseCIfc
        get() = myNumWaiting

    private val myWaitToCross = Response(this, name = "${this.name}:WaitToCross")

    /** How long each person waited before stepping on. Zero when the crossing was already open. */
    val waitToCross: ResponseCIfc
        get() = myWaitToCross

    // ---- the turn ------------------------------------------------------------------------------

    override fun initialize() {
        myWaiting.clear()
        numOnCrossing = 0
        myNumWaiting.value = 0.0
        myTimeBarred.value = 0.0
        myReviewEvent = null
        arbiter.reset()
    }

    /**
     * Joins the queue and asks the arbiter, which may open a turn.
     *
     * @return true when this walker may step on without suspending, which is the case a crossing
     *   that is already open should not make anybody pay for
     */
    internal fun joinAndTryToCross(entity: ProcessModel.Entity, queue: HoldQueue): Boolean {
        val waiter = Waiter(time, entity, queue)
        myWaiting.add(waiter)
        myNumWaiting.value = myWaiting.size.toDouble()
        reconsider()
        return waiter.mayGo
    }

    /**
     * Asks the arbiter both of its questions and acts on the answers.
     *
     * One place, called whenever anything the arbiter reads has changed -- an arrival, a departure,
     * a grant, the end of a turn -- so that a discipline can be written as two pure questions and
     * never has to know which event it is answering.
     */
    private fun reconsider() {
        // End-of-replication cleanup terminates every suspended walker, and each one comes through
        // here on its way off the crossing. There is nothing left to arbitrate at that point and
        // the executive refuses an event once it has ended, so asking again would raise during
        // teardown -- from inside the very cleanup that exists to leave the model tidy.
        if (executive.isEnded) return
        if (arbiter.barsVehicles(this)) {
            // Asked for but not yet granted means traffic is still draining off; nothing may step
            // on until it has, and asking twice would raise.
            if (!isBarred && !isOpening) {
                // Shut from this instant, not from the grant: a vehicle arriving while the zones
                // drain is refused exactly as one arriving mid-turn is, so the statistic has to
                // start here or it would understate the crossing's cost by the drain every turn.
                myTimeBarred.value = 1.0
                space.requestZones(this, zones, this)
            }
        } else if (isBarred && numOnCrossing == 0) {
            endTurn()
        } else if (isOpening && numOnCrossing == 0 && myWaiting.isEmpty()) {
            // The last of them gave up while traffic was still draining off, so the turn being
            // opened is no longer wanted. Giving the request up reopens the zones at once rather
            // than shutting them for a turn nobody will take.
            space.releaseZones(this)
            barringEnded()
        }
        letThemThrough()
        scheduleReview()
    }

    private var myReviewEvent: KSLEvent<Nothing>? = null

    private val myReviewAction = EventActionIfc<Nothing> {
        myReviewEvent = null
        reconsider()
    }

    /**
     * Books the next instant the arbiter wants to be asked, replacing any review already booked.
     *
     * At most one outstanding, and re-read on every pass, so a discipline may move its own deadline
     * as often as it likes without the crossing accumulating events. A review at or before now is
     * not scheduled: the question has just been asked.
     */
    private fun scheduleReview() {
        val at = arbiter.reviewAt(this)
        myReviewEvent?.let { if (it.isScheduled) it.cancel = true }
        myReviewEvent = null
        if (at.isNaN()) return
        val delay = at - time
        if (delay <= 0.0) return
        myReviewEvent = schedule(myReviewAction, delay)
    }

    /**
     * Sends as many as the arbiter will admit, oldest first.
     *
     * A loop rather than one at a time, because a turn that opens for a group has to release the
     * whole group in the instant it opens; and it asks the arbiter again on every pass, because
     * admitting somebody changes what the arbiter sees.
     */
    private fun letThemThrough() {
        while (isBarred && myWaiting.isNotEmpty() && arbiter.admitsPedestrian(this)) {
            val waiter = myWaiting.removeFirst()
            myNumWaiting.value = myWaiting.size.toDouble()
            myWaitToCross.value = time - waiter.arrivedAt
            if (waiter.entity.isQueued) {
                // Suspended, so the process layer wakes it and it steps on when it resumes.
                waiter.queue.removeAndResume(waiter.entity)
            } else {
                // Joined in this very instant and has not suspended yet: the verb is still on the
                // stack and will step on without ever having stopped.
                waiter.mayGo = true
                stepOn()
            }
        }
    }

    /** Takes one walker onto the crossing, having been let through. */
    private fun stepOn() {
        check(isBarred) { "Crossing (${this.name}) is not shut to traffic, so nobody may step on." }
        for (zone in zones) {
            check(space.admitToZone(zone, this)) {
                "Crossing (${this.name}) holds zone (${zone.name}) and was refused admission to " +
                        "it, which cannot happen: a population host admits onto what it holds."
            }
        }
        numOnCrossing++
        (arbiter as? BoundedBatchArbiter)?.pedestrianAdmitted()
    }

    /** Steps on after having been woken, which the verb calls once its suspension ends. */
    internal fun stepOnAfterWaiting() {
        stepOn()
    }

    /** Takes a walker off again, which may end the turn. */
    internal fun stepOff() {
        check(numOnCrossing > 0) { "Crossing (${this.name}) has nobody on it to step off." }
        for (zone in zones) {
            space.departFromZone(zone)
        }
        numOnCrossing--
        myCrossingsMade.increment()
        reconsider()
    }

    /**
     * Takes a walker off the books whatever state it was in, for an entity that will not finish.
     *
     * The population edge, and the reason the crossing is worth having rather than hand-rolling: a
     * walker that balks, is interrupted or is destroyed must still leave, or the crossing stays
     * shut to traffic for the rest of the replication with nobody on it and nothing coming to say
     * so. Called from the same two entity exits that give guide-path space back.
     */
    internal fun abandon(entity: ProcessModel.Entity) {
        val waiting = myWaiting.indexOfFirst { it.entity === entity }
        if (waiting >= 0) {
            myWaiting.removeAt(waiting)
            myNumWaiting.value = myWaiting.size.toDouble()
            reconsider()
            return
        }
        if (numOnCrossing > 0) stepOff()
    }

    private fun endTurn() {
        space.releaseZones(this)
        arbiter.turnEnded(this)
    }

    // ---- told about its own hold ---------------------------------------------------------------

    override fun holdBegan(allocation: ZoneAllocation) {
        myTurnsTaken.increment()
        // The turn is open, which is the event the queue has been waiting for. Everything the
        // arbiter reads changed when the grant landed, so this goes back through the one place.
        letThemThrough()
    }

    override fun holdEnded(allocation: ZoneAllocation) {
        myTimeBarred.value = 0.0
    }

    /**
     * Also zero when a turn being opened is given up, which ends the barred period without any
     * hold ever having begun -- [ZoneHoldActionIfc] says nothing for a request abandoned before it
     * was granted, so this is the crossing's own business.
     */
    private fun barringEnded() {
        myTimeBarred.value = 0.0
    }

}
