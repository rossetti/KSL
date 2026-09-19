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

/**
 * Where a vehicle is going and what it does when it gets there.
 *
 * A single-load transport is a two-stop tour, and the control loop does not know how many stops it
 * has. That is the whole point: multi-load work adds stops rather than adding a code path, so the
 * loop written here is the loop that will still be running when a vehicle carries four things.
 */
class Tour internal constructor(
    stops: List<TourStop>,
    /**
     * True when this round comes back to where it began.
     *
     * Read by a boarding action, which may take somebody bound for a stop this round has already
     * been past: on a loop the vehicle will reach it again, and a rider keeps its seat across the
     * cycle boundary for exactly that reason.
     */
    val cyclic: Boolean = false
) {

    init {
        require(stops.isNotEmpty()) { "A tour must have at least one stop." }
    }

    private val myStops: MutableList<TourStop> = stops.toMutableList()

    /**
     * The stops, in the order they will be reached.
     *
     * Mutable behind the cursor and frozen in front of it: a stop already reached is history, and
     * rewriting history would let a vehicle be told to collect a load it has already set down.
     * [remove] and [insert] are the only ways it changes; both refuse to touch the past, and both
     * work in whole tasks so that a transport cannot be left with half of itself in the tour.
     */
    val stops: List<TourStop>
        get() = myStops

    /**
     * When the round began: the instant the vehicle was committed to it.
     *
     * Set once, by the agent that built the tour. A cycle-relative timetable is read from this, so
     * that the same table serves every vehicle running the same line whatever time it set out.
     */
    var startedAt: Double = Double.NaN
        internal set

    private var cursor: Int = 0

    /** The stop the vehicle is travelling to, or null when the tour is done. */
    val nextStop: TourStop?
        get() = myStops.getOrNull(cursor)

    val isComplete: Boolean
        get() = cursor >= myStops.size

    val stopsCompleted: Int
        get() = cursor

    /** The stops still to be reached, in order. What a tour policy is given to reorder. */
    val remainingStops: List<TourStop>
        get() = myStops.subList(cursor, myStops.size).toList()

    internal fun advance() {
        check(!isComplete) { "The tour is already complete and cannot be advanced." }
        cursor++
    }

    /**
     * Puts a task's stops into the tour as one block, starting at [position], counted from the next
     * stop.
     *
     * **A task goes in whole or not at all**, which is why this takes the stops of one task rather
     * than a stop. The two stops of a transport are not independent: putting in a pickup and
     * leaving out its set-down would route a vehicle to collect something it never puts down, which
     * is the same statement [remove] makes from the other side. Taking a list rather than a stop is
     * what makes that a rule instead of a thing a caller is trusted to remember.
     *
     * The stops go in contiguously and in the order given. Interleaving one task's stops with
     * another's is a routing decision and belongs to the tour policy that composes the itinerary,
     * not here.
     *
     * Position 0 makes the first of them the stop the vehicle goes to next, which is what a
     * redirection means; the caller is then responsible for issuing the leg, because a tour
     * describes where a vehicle is going and never commands it. **A vehicle already travelling to
     * its next stop must not be inserted in front of**: the control loop holds that stop while it
     * is suspended, so shifting it would have the vehicle serve it twice and pass the new one by.
     *
     * @param stops the stops of a single task, in the order they are to be reached
     * @param position where among the remaining stops the block begins, 0 being next and
     *   [remainingStops].size being last
     */
    internal fun insert(stops: List<TourStop>, position: Int) {
        require(stops.isNotEmpty()) { "A tour cannot have an empty block of stops put into it." }
        require(position in 0..(myStops.size - cursor)) {
            "Cannot insert at position $position: the tour has ${myStops.size - cursor} stops left " +
                    "to make, and a stop already reached cannot be changed."
        }
        val task = stops.first().action.task
        require(stops.all { it.action.task === task }) {
            "The stops put into a tour in one go must belong to one task: a transport whose pickup " +
                    "and set-down are split across two insertions can be left half in the tour by " +
                    "anything that happens between them."
        }
        // Only a transport's stops name a task; a repositioning errand and a line's cycle name
        // none, and neither has a pairing to protect.
        if (task != null) {
            require(myStops.none { it.action.task === task }) {
                "Task (${task.name}) is already in this tour. Putting it in twice would give the " +
                        "vehicle two pickups for one load, and `remove` would then take out stops " +
                        "belonging to a commitment it was not asked about."
            }
        }
        myStops.addAll(cursor + position, stops)
    }

    /**
     * Takes out every stop still to be reached that belongs to [task], and reports how many went.
     *
     * The unit of removal is the *task* rather than the stop, because the two stops of a transport
     * are not independent: taking out a pickup and leaving its set-down would leave a vehicle
     * routed to put down something it never collected. A task whose pickup has already happened
     * cannot be removed at all -- which is the same statement `A4` makes about revocation, arrived
     * at from the other side.
     */
    internal fun remove(task: Dispatcher.Task): Int {
        val doomed = myStops.subList(cursor, myStops.size).filter { it.action.task === task }
        if (doomed.isEmpty()) return 0
        require(doomed.size == myStops.count { it.action.task === task }) {
            "Task (${task.name}) cannot be taken out of this tour: part of it has already been " +
                    "reached, and a stop already reached cannot be changed."
        }
        myStops.removeAll { s -> doomed.any { it === s } }
        return doomed.size
    }

    override fun toString(): String = "Tour(${myStops.size} stops, $cursor completed)"
}

/** One leg of a tour: somewhere to be, and something to do there. */
class TourStop(val location: String, val action: TourStopActionIfc) {
    override fun toString(): String = "TourStop($location, $action)"
}
