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
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc

/**
 * A permanent place where loads wait to board whatever comes by.
 *
 * The second of this subsystem's two waiting lines, and the one that exists because of a real
 * difference rather than a convenience. A load that posts a transport request waits for a
 * *decision*: some vehicle will be told to come for it, and the dispatcher's queue measures how
 * long that took. A load standing at a stop waits for no decision at all. It waits for the next
 * vehicle that comes past with room and somewhere useful to go, which is a function of headway and
 * capacity. Putting it in a queue whose time in queue means "wait for a decision" would report a
 * quantity that does not exist (`A11`).
 *
 * So a stop owns its own waiting line and reports it separately. **The two are both real and must
 * not be summed**: one answers how long work waits to be allocated, the other how long a rider
 * waits for a service to turn up.
 *
 * **A stop is permanent and a [TourStop] is not.** A `TourStop` is per-tour execution state -- a
 * place to be and something to do there -- and it names a location. A `Stop` is a model element
 * that outlives every tour, owns the waiting area, and is what per-stop statistics belong to. The
 * two are joined by the *action*: a boarding action holds the `Stop` it draws from. Three things
 * follow, all of them wanted:
 *
 * - **A vehicle collecting a posted load needs no `Stop`.** It collects at an arbitrary junction
 *   that nobody waits at, so requiring one per tour stop would make every such pickup declare a
 *   waiting area that never holds anybody.
 * - **The reference is an object rather than a name**, so a boarding action cannot be pointed at a
 *   stop that does not exist.
 * - **Two lines may share one stop**, which is what makes an interchange, a transfer point and a
 *   shared drop point expressible without any of them being a feature.
 *
 * @param system the fleet whose vehicles serve it
 * @param location the junction or station it stands at, checked against the network now
 * @param name the element name; the location is used when none is given
 */
class Stop @JvmOverloads constructor(
    val system: FleetSystem,
    val location: String,
    name: String? = null
) : ModelElement(system, name ?: "Stop:$location") {

    init {
        system.space.requireLocation(location)
        system.register(this)
    }

    /**
     * One load's intention to be carried somewhere, waiting here until a vehicle takes it.
     *
     * A `QObject`, so the *ride* queues and carries the waiting statistics while the rider itself
     * is suspended in a hold queue that reports nothing -- exactly the division
     * [Dispatcher.Task] and [TaskQ] make, and for the same reason.
     *
     * It is deliberately **not** a [Dispatcher.Task]. Nothing is posted, nothing is assigned, and no
     * policy ranks it; a task would manufacture a decision this system does not make.
     */
    inner class Ride internal constructor(
        val rider: ProcessModel.Entity,
        val destination: String
    ) : QObject("${rider.name}:Ride") {

        /** Where it is waiting, or waited. */
        val stop: Stop
            get() = this@Stop

        /** When it went aboard. NaN until then. */
        var boardedAt: Double = Double.NaN
            internal set

        /** Which vehicle took it. Null until then. */
        var carriedBy: FleetVehicle? = null
            internal set

        /** How many vehicles served this stop while it waited and left it standing. */
        var numVehiclesPassed: Int = 0
            internal set

        override fun toString(): String = "Ride(${rider.name} -> $destination from $location)"
    }

    private val myWaiting = Queue<Ride>(this, "${this.name}:Q")

    /** The reported waiting line: how many are standing here and for how long. */
    val waitingQ: QueueCIfc<Ride>
        get() = myWaiting

    /** Who is waiting, in the order they arrived. What a boarding action draws from. */
    val waiting: List<Ride>
        get() = myWaiting.toList()

    val numWaiting: Int
        get() = myWaiting.size

    // ---- statistics ------------------------------------------------------------------------
    // Counts of what happened here, which is what a per-stop study is about. The waiting time
    // itself is the queue's, above, and is not restated.

    private val myNumBoarded = Counter(this, "${this.name}:NumBoarded")
    val numBoarded: CounterCIfc get() = myNumBoarded

    private val myNumAlighted = Counter(this, "${this.name}:NumAlighted")
    val numAlighted: CounterCIfc get() = myNumAlighted

    /**
     * Visits at which a vehicle served the stop and left somebody standing for want of room.
     *
     * Counted once per visit rather than once per load left behind, because it answers "how often
     * does a service arrive full", which is the question a capacity decision is made from. How many
     * were left is [waitingQ]'s number in queue.
     */
    private val myNumPassedByFull = Counter(this, "${this.name}:NumPassedByFull")
    val numPassedByFull: CounterCIfc get() = myNumPassedByFull

    /**
     * Visits a vehicle was instructed past without stopping.
     *
     * Kept apart from [numPassedByFull] because the two have opposite remedies -- more capacity for
     * one, less expressing for the other -- and a single combined row would hide which of them a
     * fleet is suffering from.
     */
    private val myNumPassedBySkipped = Counter(this, "${this.name}:NumPassedBySkipped")
    val numPassedBySkipped: CounterCIfc get() = myNumPassedBySkipped

    // ---- the protocol ----------------------------------------------------------------------

    /** Puts a rider in the line. Called by the rider's own verb, never by a vehicle. */
    internal fun join(rider: ProcessModel.Entity, destination: String): Ride {
        system.space.requireLocation(destination)
        require(destination != location) {
            "Entity (${rider.name}) asked to be carried from (${this.name}) to ($destination), " +
                    "which is where it already is."
        }
        val ride = Ride(rider, destination)
        myWaiting.enqueue(ride)
        // A vehicle holding here for a load is waiting for exactly this.
        releaseHoldingVehicles()
        return ride
    }

    /** Takes a ride out of the line because a vehicle is collecting it. */
    internal fun departing(ride: Ride) {
        require(ride.stop === this) { "Ride (${ride.name}) is not waiting at ${this.name}." }
        myWaiting.remove(ride)
        myNumBoarded.increment()
    }

    /** A load came off here. */
    internal fun alighted() = myNumAlighted.increment()

    /**
     * Records that a vehicle served the stop and left somebody standing for want of room.
     *
     * Public because a boarding action written outside this library is the only thing that knows it
     * happened: the shipped [BoardWaiting] calls it, and one you write should too, or the stop will
     * report a service that is never full.
     */
    fun passedByFull() {
        myNumPassedByFull.increment()
        // Whoever is still standing here was left behind by this visit, which is what the per-rider
        // count means. It is taken from the queue rather than passed in, so the stop's row and the
        // rider's own figure cannot come to disagree about who was passed by.
        for (r in myWaiting) r.numVehiclesPassed++
    }

    /** A vehicle was told past without serving it. */
    internal fun passedBySkipped() {
        myNumPassedBySkipped.increment()
        for (r in myWaiting) r.numVehiclesPassed++
    }

    // ---- vehicles that are waiting here ----------------------------------------------------
    //
    // A vehicle holding for a load is the mirror image of a load waiting for a vehicle, and it needs
    // the same two things to end its wait: somebody turning up, and a deadline. Neither is a
    // statistic -- the hold queue reports nothing, as all of this subsystem's do -- and both are
    // here rather than on the action because only the stop can see an arrival.

    private val myVehiclesHolding = HoldQueue(this, "${this.name}:VehiclesHoldingQ")

    init {
        myVehiclesHolding.waitTimeStatOption = false
        myVehiclesHolding.defaultReportingOption = false
    }

    /** Suspends a vehicle agent here until somebody joins the line or its deadline arrives. */
    internal val vehiclesHolding: HoldQueue
        get() = myVehiclesHolding

    /** Wakes every vehicle holding here, whatever it was holding for; each re-decides for itself. */
    private fun releaseHoldingVehicles() {
        while (myVehiclesHolding.isNotEmpty) {
            myVehiclesHolding.removeAndResume(myVehiclesHolding.peekNext()!!)
        }
    }

    /**
     * Arranges for a vehicle holding here to be woken at [instant], whether or not anybody comes.
     *
     * Scheduled by the stop rather than delayed by the vehicle because the vehicle must be
     * resumable by an arrival as well, and an entity cannot be both delaying and holding.
     */
    internal fun wakeHoldingVehiclesAt(instant: Double) {
        if (instant > time) schedule(this::deadlineReached, instant - time)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun deadlineReached(event: ksl.simulation.KSLEvent<Nothing>) = releaseHoldingVehicles()

    /**
     * Everyone here bound for one of [destinations], longest waiting first.
     *
     * What a boarding action draws from: pass it [StopContextIfc.onwardLocations] and it returns
     * exactly those the vehicle could usefully take, in the order they arrived.
     */
    fun waitingFor(destinations: Set<String>): List<Ride> =
        myWaiting.filter { it.destination in destinations }

    override fun toString(): String = "Stop($name at $location, $numWaiting waiting)"
}
