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
 * One stop of a line: where to call, and what to do on arriving.
 *
 * The action defaults to what serving a stop ordinarily means -- put down everyone bound for here,
 * then take whoever is waiting for somewhere further along -- so a line is usually written as a
 * list of stops and nothing else. A terminal that only loads, a drop point that only unloads, and a
 * timing point that dwells are all written by naming a different action.
 */
class LineStop @JvmOverloads constructor(
    val stop: Stop,
    val action: TourStopActionIfc = DoInOrder(AlightHere(stop), BoardWaiting(stop))
) {
    override fun toString(): String = "LineStop(${stop.name}, $action)"
}

/**
 * A fixed sequence of stops a service runs, cycle after cycle.
 *
 * A **specification**: declared once, shared by every vehicle that runs it, and never consumed. It
 * is the third of the three ways a tour comes into existence -- declared here, planned by a tour
 * policy from committed tasks, or written out by a modeller -- and the only one in which the same
 * itinerary recurs.
 *
 * **One cycle is one tour**, built fresh from the line each time it is assigned. That keeps [Tour]
 * what it is -- consumed, with a cursor that only advances -- rather than making it circular, and
 * it puts a decision point at the end of every cycle: the vehicle declares itself available and the
 * dispatcher decides whether it runs the line again, runs another, or goes to the depot. The life
 * of one tour is then the cycle time, which is the number a route study is usually about.
 *
 * @param name what the service is called
 * @param stops where it calls, in order
 * @param cyclic true for a loop that comes back to where it started, which adds a final leg to the
 *   first stop's location so that the cycle time is a whole round and the vehicle finishes in
 *   position for the next one. False for a line that ends where its last stop is, which is the
 *   shape of a terminal-to-terminal run whose return is a separate line.
 */
class Line @JvmOverloads constructor(
    val name: String,
    val stops: List<LineStop>,
    val cyclic: Boolean = true
) {

    init {
        require(stops.isNotEmpty()) { "Line ($name) must have at least one stop." }
    }

    /** Where it starts, which is where a vehicle assigned the line goes first. */
    val origin: String
        get() = stops.first().stop.location

    /** Where a cycle ends: back at the start for a loop, at the last stop otherwise. */
    val terminus: String
        get() = if (cyclic) origin else stops.last().stop.location

    /** Every place this line calls at. What a boarding action tests a destination against. */
    val locations: Set<String>
        get() = stops.map { it.stop.location }.toSet()

    /**
     * One cycle, as stops for a tour.
     *
     * The closing leg of a loop sets down whoever is bound for the terminus and does nothing else.
     * It does not *serve* the first stop -- boarding there belongs to the next cycle, and a vehicle
     * that boarded at the end of one cycle would be carrying people whose stops it has already been
     * past.
     */
    internal fun cycle(): List<TourStop> {
        val body = stops.map { TourStop(it.stop.location, it.action) }
        return if (cyclic) body + TourStop(origin, AlightHere(stops.first().stop)) else body
    }

    override fun toString(): String =
        "Line($name, ${stops.size} stops, ${if (cyclic) "cyclic" else "one-way"})"
}
