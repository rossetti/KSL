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

/**
 * What a fleet needs from whatever moves its vehicles.
 *
 * A dispatcher, a tour and a manifest have nothing to say about *how* a vehicle gets from one place
 * to another. They name locations, ask how far away one is, command a journey and wait for it to
 * end. This is that, and nothing else: everything above it can then be written once and run over
 * more than one movement substrate.
 *
 * **Three substrates are in view and they differ only in how finely they discretise a journey.**
 * A zone-based guide path makes a decision at every zone boundary, which the network's geometry
 * fixes. A continuous projection makes one at every interpolation step, which the modeller chooses.
 * A free path makes none, because a move there is a single delay. What they share is this interface.
 *
 * ### The contract
 *
 * - [positionNow] is where the vehicle is **at this instant**, not where it last arrived. A
 *   substrate that cannot say must not answer with a stale location: every distance-based decision
 *   reads this, and a decision made on where a vehicle set off from is a decision made on the wrong
 *   fleet.
 * - [pathDistanceTo] is distance **along the path the vehicle would actually take**, by the
 *   substrate's own metric — never straight-line separation, unless the substrate is a plane and
 *   the two coincide. On a one-way loop a vehicle standing just past a point must go all the way
 *   round, and a rule scoring proximity would send the one furthest away.
 * - [beginTravelTo] commands a journey and **does not suspend**. It hands back the queue the caller
 *   must wait in, so that the two ends of a wait cannot disagree about where the wake will come
 *   from. Null means the vehicle was already there and no journey started.
 * - **A second [beginTravelTo] while one is in progress is a redirection**, not an error. Where a
 *   substrate cannot turn round instantly it may defer the change to its next decision point.
 * - [isHalted] means stopped short of the destination with nothing scheduled: whatever stopped it
 *   owns starting it again, through [resumeHalted]. It is not the same as blocked, which resolves
 *   itself when somebody else moves.
 * - [distanceTravelled] and [operatingTime] never decrease within a replication, and both are
 *   continuous across a redirection: a vehicle that turns round has still covered the ground it
 *   covered.
 *
 * Implementations pass a common conformance suite, which is where these sentences are enforced
 * rather than merely stated.
 */
interface VehicleMovementIfc {

    /** Where the vehicle is now, interpolated or derived as the substrate requires. */
    val positionNow: LocationIfc

    /** How far [destination] is along the path this vehicle would take, by the substrate's metric. */
    fun pathDistanceTo(destination: LocationIfc): Double

    /** True when the vehicle could get to [destination] at all. Always true where every place is. */
    fun isReachable(destination: LocationIfc): Boolean

    /**
     * Sends the vehicle to [destination] and reports where to wait for it.
     *
     * @param purpose what the journey is for. Never what the vehicle is carrying, which the
     *   substrate reads for itself
     * @param waiter whoever is to be resumed when the journey ends -- the vehicle's own driver or
     *   agent, never a load being carried, whose waiting belongs to its own protocol
     * @return the queue to suspend [waiter] in, or null when the vehicle was already there
     */
    fun beginTravelTo(
        destination: LocationIfc,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity
    ): HoldQueue?

    /** True while the vehicle is stopped short of where it was going, with nothing scheduled. */
    val isHalted: Boolean

    /** Starts a halted vehicle again from where it stopped. Harmless on one that is not halted. */
    fun resumeHalted()

    /** How far the vehicle has travelled this replication. Never decreases. */
    val distanceTravelled: Double

    /** How long the vehicle has been doing anything other than standing idle. Never decreases. */
    val operatingTime: Double
}
