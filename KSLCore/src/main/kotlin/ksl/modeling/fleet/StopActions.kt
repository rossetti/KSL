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

import ksl.modeling.entity.KSLProcessBuilder
import ksl.utilities.GetValueIfc

/**
 * What a vehicle does when it gets there.
 *
 * A tour names stops, and a stop is a place paired with one of these. The interface is open and the
 * method is suspending, which together are the whole extension point of this subsystem: an action
 * may wait for a resource, delay for a dwell, ask the dispatcher a question and wait for the
 * answer, or do nothing at all, and the control loop that runs it does not change to accommodate
 * any of that. The loop's job is to get the vehicle to the location; what happens on arrival is
 * this.
 *
 * **The vehicle executes actions; it never invents them.** An action is written by whoever builds
 * the model and put into a tour by the dispatcher's tour policy. That division is what keeps a
 * fleet's behaviour in one place instead of two.
 *
 * A modeller writing one gets the subsystem's per-load bookkeeping by calling the verbs on
 * [StopContextIfc] rather than by touching the load: [StopContextIfc.takeAboard] and
 * [StopContextIfc.setDown] record the intervals, keep the manifest honest, tell the dispatcher and
 * wake the load. An action that moves a load without them will run, and will be missing from every
 * statistic the subsystem reports.
 */
interface TourStopActionIfc {

    /**
     * The task this action acts on behalf of, or null for an action that acts on nobody's.
     *
     * Read by the tour machinery to decide what belongs to whom: which stops a revocation takes
     * out, which stops are still this vehicle's to make, and which set-down answers which pickup.
     * An action with no task is nobody's to revoke and is always still ours.
     */
    val task: Dispatcher.Task?
        get() = null

    /**
     * How many loads this stop is *planned* to add to the vehicle: +1 for a collection, -1 for a
     * delivery, 0 for a stop that carries nothing either way.
     *
     * A planning figure rather than a promise, and the distinction matters for an action whose
     * effect depends on what it finds when it arrives -- a stop that boards everyone waiting boards
     * a number nobody knows in advance. Such an action declares 0 and is planned as though it
     * changed nothing, which is the only honest thing a planner can be told; the capacity is then
     * enforced where it is actually known, by the manifest, at the moment of boarding.
     */
    val loadChange: Int
        get() = 0

    /**
     * The permanent stop this action serves, or null for an action that serves none.
     *
     * Read by the machinery that reports what happened at a place rather than to a load: an
     * instruction to pass a stop by is counted against the stop the action names. An action on a
     * bare junction names none, which is every action an ordinary transport uses.
     */
    val servesStop: Stop?
        get() = null

    /** Carried out once the vehicle has arrived at [StopContextIfc.stop]. */
    suspend fun KSLProcessBuilder.perform(context: StopContextIfc)
}

/**
 * What a vehicle knows, and what it can do, at the stop it has just reached.
 *
 * Handed to [TourStopActionIfc.perform] so that an action written outside this library can do the
 * two things that must be done in one particular way -- take a load aboard and put one down --
 * without being given the subsystem's internals to do it with.
 */
interface StopContextIfc {

    /** The vehicle that arrived. */
    val vehicle: FleetVehicle

    /** The stop it arrived at. */
    val stop: TourStop

    /** The round this stop belongs to, and how far through it the vehicle is. */
    val tour: Tour

    /**
     * Everywhere the vehicle is still going after this stop.
     *
     * What makes a boarding decision possible: a rider may be taken only if the vehicle is going
     * where the rider is going. Excludes this stop, because somebody standing here bound for here
     * has nothing to ride for.
     */
    val onwardLocations: Set<String>

    /**
     * Takes [task]'s load aboard: waits out the loading delay, puts it on the manifest, marks the
     * instant its ride begins, tells the dispatcher the vehicle has possession, and releases the
     * load from its wait.
     *
     * Suspends for as long as loading takes. Every per-load interval this subsystem reports about
     * collection is recorded here, which is why an action that wants to be measured collects this
     * way rather than by touching the load itself.
     */
    suspend fun KSLProcessBuilder.takeAboard(task: Dispatcher.TransportTask)

    /**
     * Puts [task]'s load down: measures the ride, waits out the unloading delay, takes it off the
     * manifest, completes the task and the commitment behind it, and returns the load to its own
     * process.
     *
     * Suspends for as long as unloading takes.
     */
    suspend fun KSLProcessBuilder.setDown(task: Dispatcher.TransportTask)

    /**
     * Takes a waiting rider aboard: out of the stop's line, onto the manifest, and released from
     * its wait.
     *
     * The boarding half of the same protocol [takeAboard] performs for a posted task, and it
     * populates the same intervals with the same meanings, so a study may compare a fleet that is
     * hailed with one that is timetabled.
     *
     * @throws ksl.modeling.fleet.exceptions.FleetProtocolException when the vehicle has no room
     */
    suspend fun KSLProcessBuilder.takeAboard(ride: Stop.Ride)

    /** Puts a rider down here and returns it to its own process. */
    suspend fun KSLProcessBuilder.setDown(ride: Stop.Ride)

    /**
     * Holds the vehicle at [stop] until somebody joins the line there or [until] arrives.
     *
     * The waiting half of a load-driven departure. It reports nothing: a vehicle standing at a stop
     * is already visible in its own time-on-task and in the stop's queue, and a third row saying
     * the same thing in a third way is how a report becomes unreadable.
     */
    suspend fun KSLProcessBuilder.holdAt(stop: Stop, until: Double)
}

/**
 * Take possession of a load.
 *
 * The collecting half of a transport. Its set-down is a separate stop, and the two are put into a
 * tour together and taken out of one together, because a vehicle routed to deliver something it
 * never collected is not a tour at all.
 */
data class PickUp(override val task: Dispatcher.TransportTask) : TourStopActionIfc {

    override val loadChange: Int
        get() = 1

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        with(context) { takeAboard(this@PickUp.task) }
    }
}

/** Put a load down, which is what discharges the commitment to carry it. */
data class SetDown(override val task: Dispatcher.TransportTask) : TourStopActionIfc {

    override val loadChange: Int
        get() = -1

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        with(context) { setDown(this@SetDown.task) }
    }
}

/**
 * Be somewhere, and nothing more.
 *
 * What an errand amounts to: the arrival *is* the work. It is not a no-op with a location attached,
 * because getting there was the point.
 */
data object Reposition : TourStopActionIfc {
    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) = Unit
}

// Charge arrives with the battery seam; Repair with the failure seam.

// ---- demand-bound actions ---------------------------------------------------------------------
//
// The three above are TASK-BOUND: each names exactly which load. These name a *rule*, and which
// loads are involved is discovered on arrival. That distinction is the one that makes a fixed route
// work -- the tour is fixed and what happens at each stop is not, which is why a service runs the
// same twelve stops every cycle and carries entirely different people each time.

/**
 * Put down everyone aboard who is bound for here.
 *
 * Sets down nothing when nobody is: an empty stop is served in no time at all, which is what a
 * vehicle running past an unused halt actually does.
 */
data class AlightHere(val stop: Stop) : TourStopActionIfc {

    override val servesStop: Stop
        get() = stop

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        val here = this@AlightHere.stop
        with(context) {
            // A snapshot, because setting one down changes the list being read.
            for (ride in vehicle.ridersBoundFor(here.location)) {
                setDown(ride)
            }
        }
    }
}

/**
 * Take whoever is waiting here for somewhere further along, in the order they arrived, until the
 * vehicle is full or [limit] have boarded.
 *
 * **Somewhere further along** is the whole of the eligibility rule, and it is read from the tour
 * rather than from the line: a vehicle short-turning, or running a line it was reassigned to
 * mid-cycle, will not take somebody to a stop it is no longer going to.
 *
 * @param stop whose line to draw from
 * @param limit the most to take in one visit, for a service that meters boarding. Unlimited by
 *   default, in which case the capacity is the only thing that stops it
 */
data class BoardWaiting @JvmOverloads constructor(
    val stop: Stop,
    val limit: Int = Int.MAX_VALUE
) : TourStopActionIfc {

    init {
        require(limit > 0) { "A boarding limit must be > 0, but was $limit." }
    }

    override val servesStop: Stop
        get() = stop

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        val here = this@BoardWaiting.stop
        val most = limit
        with(context) {
            val eligible = here.waitingFor(onwardLocations)
            var taken = 0
            for (ride in eligible) {
                if (taken >= most || vehicle.spareCapacity <= 0) break
                takeAboard(ride)
                taken++
            }
            // Counted only when the room ran out, not when the limit did: a metered service that
            // leaves people standing is doing what it was told, and a full one is not.
            if (taken < eligible.size && vehicle.spareCapacity <= 0) {
                here.passedByFull()
            }
        }
    }
}

/** Stand here for a while. The simplest thing only a seam that suspends could have expressed. */
data class Dwell(val duration: GetValueIfc) : TourStopActionIfc {

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        delay(duration, suspensionName = "${context.vehicle.name}:dwelling")
    }
}

/**
 * Do not leave until the vehicle is full, or until the wait has run out.
 *
 * A load-driven departure, which is a different thing from a scheduled one: a departure time is a
 * [StopControlIfc] instruction, because the decision belongs to whoever runs the service, whereas
 * this is the vehicle responding to what is in front of it. A terminal that dispatches a trailer
 * when it fills, and otherwise at the cut-off, is this.
 *
 * @param stop whose line to draw from
 * @param maximumWait how long to hold at most, sampled on arrival. Must be finite: a vehicle told
 *   to wait forever for a load that never comes is stranded, and this subsystem prefers a modeller
 *   to have to write the deadline down
 * @param limit the most to take in one visit
 */
data class HoldUntilFull @JvmOverloads constructor(
    val stop: Stop,
    val maximumWait: GetValueIfc,
    val limit: Int = Int.MAX_VALUE
) : TourStopActionIfc {

    override val servesStop: Stop
        get() = stop

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        val here = this@HoldUntilFull.stop
        val most = limit
        val deadline = here.system.time + maximumWait.value
        require(deadline.isFinite()) {
            "HoldUntilFull at (${here.name}) needs a finite maximum wait; a vehicle told to " +
                    "wait forever for a load that never comes is stranded for the replication."
        }
        with(context) {
            var taken = 0
            while (true) {
                for (ride in here.waitingFor(onwardLocations)) {
                    if (taken >= most || vehicle.spareCapacity <= 0) break
                    takeAboard(ride)
                    taken++
                }
                if (taken >= most || vehicle.spareCapacity <= 0) break
                if (here.system.time >= deadline) break
                holdAt(here, deadline)                          // SUSPENDS
            }
        }
    }
}

/**
 * Several actions, in order, at one stop.
 *
 * Serving a stop is two things -- put down, then pick up -- and they have to be one [TourStop]
 * rather than two, because an instruction to skip a stop applies to the whole visit and a vehicle
 * that alighted but did not board would be neither serving nor skipping.
 */
class DoInOrder(val actions: List<TourStopActionIfc>) : TourStopActionIfc {

    constructor(vararg actions: TourStopActionIfc) : this(actions.toList())

    init {
        require(actions.isNotEmpty()) { "DoInOrder needs at least one action." }
        require(actions.count { it.task != null } <= 1) {
            "DoInOrder may hold at most one task-bound action: the machinery that revokes a task " +
                    "takes out the stops that belong to it, and a stop belonging to two tasks " +
                    "could not be taken out for one of them."
        }
    }

    override val task: Dispatcher.Task?
        get() = actions.firstNotNullOfOrNull { it.task }

    override val servesStop: Stop?
        get() = actions.firstNotNullOfOrNull { it.servesStop }

    override val loadChange: Int
        get() = actions.sumOf { it.loadChange }

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        for (a in actions) {
            with(a) { perform(context) }                        // MAY SUSPEND
        }
    }

    override fun toString(): String = "DoInOrder(${actions.joinToString()})"
}
