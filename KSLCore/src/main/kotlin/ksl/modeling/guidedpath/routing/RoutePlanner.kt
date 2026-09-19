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
import ksl.modeling.guidedpath.IntersectionZone
import ksl.modeling.guidedpath.Link
import ksl.modeling.guidedpath.LinkZone
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.exceptions.GuidedPathRoutingException

/**
 * Shortest paths through a guide path, computed once when the network is built.
 *
 * Distances and first hops are found by the Floyd-Warshall algorithm over the link graph. Both are
 * cubic to compute and quadratic to store in the number of intersections, which is a cost paid once
 * at construction rather than on every dispatch, and is comfortable at the sizes a guide path
 * reaches. Searching on demand would buy nothing while the weights are static and would put a
 * search on the hot path, where a table lookup belongs.
 *
 * The weight of traversing a link is its length plus the length of the intersection it enters.
 * Counting the entered junction is what keeps a distance equal to the total length of the zones a
 * transporter actually crosses; with the usual point junctions it reduces to the sum of link
 * lengths. The intersection a transporter starts from is not entered, so its length never counts.
 *
 * Where two links join the same pair of intersections, or two paths tie on distance, the earlier
 * one in declaration order wins. Declaration order is fixed by the model's construction, so the
 * choice is the same on every run and on every platform: a route is never decided by the iteration
 * order of an unordered collection.
 */
internal class RoutePlanner(
    private val myNetwork: GuidedPathNetwork
) {

    private val myCount: Int = myNetwork.intersections.size

    /** Shortest-path distance between intersections, infinite where no path exists. */
    private val myDistance: Array<DoubleArray> =
        Array(myCount) { DoubleArray(myCount) { Double.POSITIVE_INFINITY } }

    /** The first link on the shortest path, null where none exists. */
    private val myFirstLink: Array<Array<Link?>> = Array(myCount) { arrayOfNulls<Link>(myCount) }

    /** Whether that first link is traversed from its beginning intersection. */
    private val myFirstForward: Array<BooleanArray> = Array(myCount) { BooleanArray(myCount) }

    init {
        computeShortestPaths()
    }

    /**
     * Fills the distance and first-hop tables together, so that a distance and the path that
     * realizes it can never disagree.
     */
    private fun computeShortestPaths() {
        for (i in 0 until myCount) {
            myDistance[i][i] = 0.0
        }
        // Direct edges. A tie between parallel links is settled by declaration order: a strictly
        // smaller weight is required to displace an edge already recorded.
        for (link in myNetwork.links) {
            val b = link.beginIntersection.index
            val e = link.endIntersection.index
            val forward = link.length + link.endIntersection.length
            if (forward < myDistance[b][e]) {
                myDistance[b][e] = forward
                myFirstLink[b][e] = link
                myFirstForward[b][e] = true
            }
            if (link.isTraversableInReverse) {
                val reverse = link.length + link.beginIntersection.length
                if (reverse < myDistance[e][b]) {
                    myDistance[e][b] = reverse
                    myFirstLink[e][b] = link
                    myFirstForward[e][b] = false
                }
            }
        }
        for (k in 0 until myCount) {
            for (i in 0 until myCount) {
                val dik = myDistance[i][k]
                if (dik == Double.POSITIVE_INFINITY) continue
                for (j in 0 until myCount) {
                    val dkj = myDistance[k][j]
                    if (dkj == Double.POSITIVE_INFINITY) continue
                    val through = dik + dkj
                    // Strict improvement only, so an equal-cost path never displaces the one
                    // already found and the choice stays deterministic.
                    if (through < myDistance[i][j]) {
                        myDistance[i][j] = through
                        myFirstLink[i][j] = myFirstLink[i][k]
                        myFirstForward[i][j] = myFirstForward[i][k]
                    }
                }
            }
        }
    }

    /** Shortest-path distance, or positive infinity when no path exists. */
    fun distance(from: GuidedPathNetwork.Intersection, to: GuidedPathNetwork.Intersection): Double =
        myDistance[from.index][to.index]

    /** True when some path runs from one intersection to the other. */
    fun isReachable(
        from: GuidedPathNetwork.Intersection,
        to: GuidedPathNetwork.Intersection
    ): Boolean = myDistance[from.index][to.index] != Double.POSITIVE_INFINITY

    /**
     * The links of the shortest path, in order, each paired with the direction it is traversed.
     * Empty when the endpoints are the same intersection.
     *
     * @throws GuidedPathRoutingException when no path exists
     */
    fun linkPath(
        from: GuidedPathNetwork.Intersection,
        to: GuidedPathNetwork.Intersection
    ): List<Pair<Link, Boolean>> {
        if (from === to) return emptyList()
        if (!isReachable(from, to)) {
            throw GuidedPathRoutingException.unreachable(from.name, to.name)
        }
        val path = mutableListOf<Pair<Link, Boolean>>()
        var current = from
        // Each hop strictly decreases the remaining distance, so the walk terminates. The bound is
        // a guard against a corrupted table rather than an expected outcome.
        var guard = 0
        while (current !== to) {
            val link = myFirstLink[current.index][to.index]
                ?: throw GuidedPathRoutingException.unreachable(from.name, to.name)
            val forward = myFirstForward[current.index][to.index]
            path.add(link to forward)
            current = link.entered(forward)
            if (++guard > myCount) {
                throw IllegalStateException(
                    "Routing from ${from.name} to ${to.name} did not terminate within $myCount " +
                            "hops. The shortest-path tables are inconsistent."
                )
            }
        }
        return path
    }

    /**
     * The zones a transporter crosses on the shortest path, in order.
     *
     * The sequence begins at the zone after the one the transporter holds and ends at the
     * destination's own zone. A transporter part way along a link finishes that link first: it
     * cannot turn round inside a link, so the remaining zones ahead of it are the only way off.
     *
     * @param fromZone where the transporter is now
     * @param travellingForward the direction it faces when it sits on a link zone, ignored when it
     *   sits at an intersection
     * @param to the destination
     * @throws GuidedPathRoutingException when the destination cannot be reached
     */
    fun shortestPathZones(
        fromZone: Zone,
        travellingForward: Boolean,
        to: GuidedPathNetwork.Intersection
    ): List<Zone> {
        val zones = mutableListOf<Zone>()
        val startIntersection: GuidedPathNetwork.Intersection = when (fromZone) {
            is LinkZone -> {
                // Finish the current link, then continue from the junction it leads to.
                val link = fromZone.link
                if (travellingForward) {
                    for (p in fromZone.positionOnLink until link.numZones) {
                        zones.add(link.zones[p])
                    }
                    link.endIntersection
                } else {
                    if (!link.isTraversableInReverse) {
                        throw GuidedPathRoutingException(
                            "A transporter on link (${link.name}) cannot travel in reverse: the " +
                                    "link is ${link.type}."
                        )
                    }
                    for (p in fromZone.positionOnLink - 2 downTo 0) {
                        zones.add(link.zones[p])
                    }
                    link.beginIntersection
                }
            }

            is IntersectionZone -> fromZone.intersection
        }
        if (fromZone is LinkZone) {
            // The junction reached at the end of the current link is entered, so its zone belongs
            // to the route. A transporter already standing at a junction is in that zone already
            // and does not enter it a second time.
            zones.add(startIntersection.zone)
        }
        if (startIntersection === to) {
            return zones
        }
        for ((link, forward) in linkPath(startIntersection, to)) {
            if (forward) {
                zones.addAll(link.zones)
                zones.add(link.endIntersection.zone)
            } else {
                zones.addAll(link.zones.asReversed())
                zones.add(link.beginIntersection.zone)
            }
        }
        return zones
    }
}
