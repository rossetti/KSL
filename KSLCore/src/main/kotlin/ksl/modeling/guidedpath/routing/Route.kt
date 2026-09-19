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
package ksl.modeling.guidedpath.routing

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.Zone

/**
 * The ordered zones a transporter will cross to reach a destination, and how far along them it has
 * come.
 *
 * A route begins at the zone *after* the one the transporter currently holds, because the
 * transporter is already in that zone and does not travel into it again, and ends at the
 * destination's own zone. It is a value produced on demand and discarded when the movement ends:
 * nothing owns it, nothing caches it, and two transporters given the same origin and destination
 * receive separate routes, so one cannot disturb the other's progress.
 *
 * The cursor only ever moves forward. A transporter that needs to go back the way it came is given
 * a new route rather than being wound backwards along this one, which is what makes progress along
 * a route monotone and what lets the movement engine treat "am I there yet" as a single comparison.
 *
 * @param origin the zone the transporter held when the route was planned, not part of the sequence
 * @param destination the intersection the route ends at
 * @param zones the zones to cross, in order, ending with the destination's zone
 */
class Route internal constructor(
    val origin: Zone,
    val destination: GuidedPathNetwork.Intersection,
    val zones: List<Zone>
) {
    init {
        require(zones.isNotEmpty()) {
            "A route must contain at least one zone. A transporter already at its destination " +
                    "needs no route."
        }
    }

    private var myCursor: Int = 0

    /** How many zones of this route the transporter has entered. Never decreases. */
    val zonesTraversed: Int
        get() = myCursor

    /** How many zones remain, including the one the transporter is travelling into. */
    val zonesRemaining: Int
        get() = zones.size - myCursor

    /** True when every zone of the route has been entered. */
    val isComplete: Boolean
        get() = myCursor >= zones.size

    /** The zone the transporter travels into next, or null when the route is complete. */
    val nextZone: Zone?
        get() = if (isComplete) null else zones[myCursor]

    /** The zones not yet entered, in order. */
    val remainingZones: List<Zone>
        get() = if (isComplete) emptyList() else zones.subList(myCursor, zones.size)

    /** The total distance the route covers. */
    val totalLength: Double
        get() = zones.sumOf { it.length }

    /** The distance still to cover, including the zone being travelled into. */
    val remainingLength: Double
        get() = remainingZones.sumOf { it.length }

    /**
     * Records that the transporter has entered the next zone.
     *
     * @throws IllegalStateException when the route is already complete, which would mean the engine
     *   advanced a transporter that had already arrived
     */
    internal fun advance() {
        check(!isComplete) {
            "Route to ${destination.name} is complete and cannot advance further."
        }
        myCursor++
    }

    override fun toString(): String =
        "Route(${origin.name} -> ${destination.name}: ${zones.size} zones, " +
                "${totalLength} long, $myCursor traversed)"
}
