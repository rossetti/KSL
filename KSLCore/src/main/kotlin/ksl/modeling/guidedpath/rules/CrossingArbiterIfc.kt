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
package ksl.modeling.guidedpath.rules

import ksl.modeling.guidedpath.ZoneCrossing

/**
 * Who gets the crossing next, and for how long.
 *
 * **Two decisions, not one, and that is the whole reason this is an object.** A single shared
 * resource hides the fact that a crossing arbitrates between two populations with opposite needs,
 * and a model that decides only one of them fails in a way that looks like a modelling error rather
 * than a missing rule:
 *
 * - with only "may this walker start?", vehicles starve under any steady pedestrian flow, because
 *   there is never an instant with nobody waiting to cross;
 * - with only "should vehicles be held off?", pedestrians never cross at all, because traffic never
 *   happens to leave a long enough gap.
 *
 * So both belong here, to one substitutable object, which is also what makes a discipline something
 * a study can *vary* rather than something a model hard-codes. The four that ship
 * ([PedestrianPriorityArbiter], [VehiclePriorityArbiter], [AlternatingArbiter],
 * [BoundedBatchArbiter]) are the four in the design record's table, and they are there to be
 * compared rather than to be defaults.
 *
 * Because the population count lives on the zone, a discipline may be written against **crowding**
 * as well as against time: "admit while fewer than k are on the crossing", "stop admitting once a
 * vehicle has waited t", "close the group when the zone reaches its limit". What is too many is a
 * modelling statement and belongs here; the zone only reports the number.
 */
interface CrossingArbiterIfc {

    /**
     * May a pedestrian step onto the crossing now?
     *
     * Asked of every arrival, and again of whoever is still queued each time the crossing's state
     * changes. Answering false does not end a turn that is already open -- people already on the
     * crossing finish and leave -- it only stops more from joining, which is how a turn is brought
     * to a close without evicting anybody.
     *
     * @param crossing the crossing being asked, which reports how many are on it, how many wait,
     *   and how long the vehicle at the head of the queue has been waiting
     */
    fun admitsPedestrian(crossing: ZoneCrossing): Boolean

    /**
     * Should the crossing be held shut against vehicles?
     *
     * Asked whenever the crossing's state changes. True takes the zones -- draining whatever
     * traffic is on them first, through the ordinary all-or-nothing machinery -- and keeps them
     * until this answers false and the last pedestrian is off.
     *
     * Answering true with nobody waiting shuts the crossing for no reason; answering false while
     * people are on it does **not** evict them, because a turn always finishes.
     *
     * @param crossing the crossing being asked
     */
    fun barsVehicles(crossing: ZoneCrossing): Boolean

    /**
     * When the crossing should ask again even though nothing has happened.
     *
     * **A discipline whose answer depends on elapsed time needs this, or its answer never changes.**
     * The crossing asks its two questions when something happens -- somebody arrives, somebody
     * leaves, a turn is granted -- and a rule like "open once the first of them has waited five
     * minutes" turns on an instant at which, by construction, nothing happens. One walker waiting
     * alone would wait for ever: nothing else is coming to prompt the question.
     *
     * Return the instant to ask at, or `NaN` for "nothing is pending". The crossing keeps at most
     * one review outstanding and re-reads this every time it asks, so a rule may move its own
     * deadline freely.
     *
     * @return the simulated time to reconsider at, or NaN when the discipline is not waiting on the
     *   clock
     */
    fun reviewAt(crossing: ZoneCrossing): Double = Double.NaN

    /**
     * Told that a turn has ended, so a discipline with a memory can advance it.
     *
     * Default does nothing, which is right for the stateless disciplines. [AlternatingArbiter] and
     * [BoundedBatchArbiter] use it to know that their turn is over.
     */
    fun turnEnded(crossing: ZoneCrossing) {}

    /**
     * Clears whatever the discipline remembers between replications. Does nothing by default.
     *
     * Named and shaped to match [GuidedTransporterAllocationRuleIfc.reset], which solved this same
     * problem first: a stateless rule needs it not at all, and a rule that carries the end of one
     * replication into the start of the next makes the second depend on the first, which is the one
     * thing a replication may never do. The crossing calls this as each replication begins, so a
     * stateful arbiter is correct without its author having to remember.
     *
     * Only a test running **more than one replication** will ever catch its absence.
     */
    fun reset() {}
}

/**
 * People cross whenever they arrive; traffic waits.
 *
 * In the field this is the failure mode rather than a policy: under any steady pedestrian flow
 * there is never an instant with nobody waiting, so the crossing never reopens and **vehicles
 * starve**. It ships so that a comparison can show that happening rather than assert it.
 */
class PedestrianPriorityArbiter : CrossingArbiterIfc {
    override fun admitsPedestrian(crossing: ZoneCrossing): Boolean = true
    override fun barsVehicles(crossing: ZoneCrossing): Boolean =
        crossing.numWaitingToCross > 0 || crossing.numOnCrossing > 0
}

/**
 * Traffic goes whenever it arrives; people wait for a gap.
 *
 * The opposite failure, and just as instructive: on a busy aisle a gap never comes and
 * **pedestrians never cross**. A crossing that only ever yields to an empty road is not a crossing.
 */
class VehiclePriorityArbiter : CrossingArbiterIfc {
    override fun admitsPedestrian(crossing: ZoneCrossing): Boolean = crossing.isBarred
    override fun barsVehicles(crossing: ZoneCrossing): Boolean = false
}

/**
 * Turn and turn about: a signal with a cycle.
 *
 * Pedestrians are admitted for [walkTime] once a turn opens, and the crossing then stays open to
 * traffic for at least [driveTime] before another turn may begin. Both sides get a share that does
 * not depend on how hard the other side is pushing, which is what neither priority rule can offer.
 *
 * @param walkTime how long a pedestrian turn admits people
 * @param driveTime the least time traffic gets between turns
 */
class AlternatingArbiter(
    val walkTime: Double,
    val driveTime: Double
) : CrossingArbiterIfc {

    init {
        require(walkTime > 0.0) { "walkTime must be strictly positive, was $walkTime" }
        require(driveTime > 0.0) { "driveTime must be strictly positive, was $driveTime" }
    }

    private var turnOpenedAt: Double = Double.NEGATIVE_INFINITY
    private var turnEndedAt: Double = Double.NEGATIVE_INFINITY

    override fun reset() {
        turnOpenedAt = Double.NEGATIVE_INFINITY
        turnEndedAt = Double.NEGATIVE_INFINITY
    }

    override fun admitsPedestrian(crossing: ZoneCrossing): Boolean {
        if (!crossing.isBarred) return false
        return crossing.time - turnOpenedAt < walkTime
    }

    override fun barsVehicles(crossing: ZoneCrossing): Boolean {
        if (crossing.isBarred) {
            // A turn under way ends when its time is up and the crossing has emptied.
            if (crossing.time - turnOpenedAt >= walkTime && crossing.numOnCrossing == 0) return false
            return true
        }
        if (crossing.numWaitingToCross == 0) return false
        if (crossing.time - turnEndedAt < driveTime) return false
        turnOpenedAt = crossing.time
        return true
    }

    override fun reviewAt(crossing: ZoneCrossing): Double = when {
        // A turn under way ends when its walk time is up.
        crossing.isBarred -> turnOpenedAt + walkTime
        // Somebody is waiting and traffic's share is not yet over.
        crossing.numWaitingToCross > 0 -> turnEndedAt + driveTime
        else -> Double.NaN
    }

    override fun turnEnded(crossing: ZoneCrossing) {
        turnEndedAt = crossing.time
    }
}

/**
 * Gap acceptance: this group finishes, new arrivals hold.
 *
 * A turn opens once [batchSize] people are waiting, or once the one at the head has waited
 * [maxWait]; it then admits **only those already queued when it opened**, so a stream of arrivals
 * cannot extend one turn indefinitely. That bound is the whole of the discipline, and it is the one
 * that behaves like a real crossing with a warden on it.
 *
 * @param batchSize how many waiting people open a turn
 * @param maxWait how long the first of them will wait before a turn opens regardless
 */
class BoundedBatchArbiter(
    val batchSize: Int,
    val maxWait: Double
) : CrossingArbiterIfc {

    init {
        require(batchSize >= 1) { "batchSize must be at least one, was $batchSize" }
        require(maxWait > 0.0) { "maxWait must be strictly positive, was $maxWait" }
    }

    private var admittedThisTurn: Int = 0
    private var turnSize: Int = 0

    override fun reset() {
        admittedThisTurn = 0
        turnSize = 0
    }

    override fun admitsPedestrian(crossing: ZoneCrossing): Boolean {
        if (!crossing.isBarred) return false
        return admittedThisTurn < turnSize
    }

    override fun barsVehicles(crossing: ZoneCrossing): Boolean {
        if (crossing.isBarred) {
            // Over when the group that opened it has gone through and left the zones.
            if (admittedThisTurn >= turnSize && crossing.numOnCrossing == 0) return false
            return true
        }
        val waiting = crossing.numWaitingToCross
        if (waiting == 0) return false
        if (waiting < batchSize && crossing.longestWaitToCross < maxWait) return false
        turnSize = waiting
        admittedThisTurn = 0
        return true
    }

    override fun reviewAt(crossing: ZoneCrossing): Double {
        if (crossing.isBarred) return Double.NaN
        if (crossing.numWaitingToCross == 0) return Double.NaN
        // The instant the one at the head will have waited long enough. Without this a walker that
        // never sees the batch fill up waits for the rest of the replication.
        return crossing.time + (maxWait - crossing.longestWaitToCross)
    }

    /** Counted by the crossing as each walker steps on, so the turn knows when its group is through. */
    internal fun pedestrianAdmitted() {
        admittedThisTurn++
    }

    override fun turnEnded(crossing: ZoneCrossing) {
        admittedThisTurn = 0
        turnSize = 0
    }
}
