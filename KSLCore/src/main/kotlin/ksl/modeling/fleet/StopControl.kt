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

/**
 * What a controller tells a vehicle to do about the next stop in its tour.
 *
 * Three instructions cover the four things a real controller says: run normally, hold to even out a
 * headway, run express, and turn back short.
 */
sealed class StopInstruction {

    /**
     * Go there and perform the stop's action.
     *
     * @param departNotBefore holds the vehicle at the stop once the action is done. An instant, not
     *   a duration, so a control that computes a schedule says what it means; an instant already
     *   past has no effect, which is what a service running late does
     */
    data class Serve @JvmOverloads constructor(
        val departNotBefore: Double = Double.NEGATIVE_INFINITY
    ) : StopInstruction()

    /**
     * Do not go. Advance past this stop to the next one.
     *
     * The vehicle may still physically pass through the place, because the route between the two
     * remaining stops is the space layer's business and knows nothing about tours. **That is
     * express running**, and it falls out rather than being built.
     */
    data object Skip : StopInstruction()

    /** Serve it, and end the tour there rather than going on. A short turn. */
    data object ServeAndEndTour : StopInstruction()
}

/**
 * Decides, stop by stop, whether a vehicle serves the next place in its tour.
 *
 * **A question with an answer, not a duration.** A dwell time is the special case; the general one
 * is that a vehicle may need to be told to hold, to skip, or to turn back, and the instruction may
 * be asked for by the vehicle or pushed by a controller that has decided this one is running late.
 * One seam serves both directions: [ksl.modeling.fleet.policies.DispatcherStopControl] asks, and
 * [Dispatcher.instruct] is how a controller answers before it is asked.
 *
 * **Asked before the leg, not on arrival**, and that is the decision inside the decision. Asked on
 * arrival, `Skip` would mean *drive all the way there and then not serve it*, which is not what an
 * express service does. Asked before the leg it means *do not make this leg at all*.
 *
 * The cost of asking early is that an instruction which depends on the *arrival* time cannot be
 * computed then. That is not a gap: a [TourStopActionIfc] runs on arrival and may suspend, so a
 * hold that depends on how late the vehicle actually was is expressed there. The division is clean:
 *
 * > **The control decides *where* — go, skip, or stop here. The action decides *what and how
 * > long*.**
 *
 * **Suspending**, because asking may take simulated time -- a radio call, a decision epoch, a
 * dispatching pass. One per vehicle, on the standing rule: one of anything that decides.
 *
 * A control reads the tour and does not edit it. It is handed the whole of it, because a genuine
 * short-turn decision needs to see what comes later; it returns an *instruction* and never a stop
 * list, so it cannot rewrite an itinerary through the back door.
 */
interface StopControlIfc {

    /**
     * @param vehicle the vehicle about to set out for [stop]
     * @param stop the next stop in its tour, not yet reached
     * @param tour the whole round, so a decision can see what comes after
     */
    suspend fun KSLProcessBuilder.instruct(
        vehicle: FleetVehicle,
        stop: TourStop,
        tour: Tour
    ): StopInstruction
}
