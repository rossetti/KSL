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

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.Zone

/**
 * Chooses which way a transporter goes.
 *
 * The default sends every transporter along the shortest path, which is the behaviour a modeler
 * expects and the one the reference tools implement. A rule is the place to change that: routing
 * that steers around congestion, that reserves a path in advance to make deadlock impossible, or
 * that keeps particular traffic off particular links, all fit here without touching the movement
 * engine.
 *
 * A rule is handed the live network, so it may consult zone occupancy and route around what it
 * sees. It may not change anything: claiming, releasing, or occupying a zone from inside a rule
 * would put space allocation in two places at once, and the mutators are not visible outside the
 * package for that reason.
 *
 * The sequence a rule returns must be a route a transporter could actually follow: it must start at
 * a zone reachable in one step from where the transporter is, each zone must be reachable in one
 * step from the one before it, and it must end at the destination's own zone. A sequence that is
 * not checked against these conditions before the transporter starts moving would strand it
 * somewhere in the middle of the network, so the planner validates every rule's output and names
 * the offending rule when it is wrong. The default rule is validated on the same path as any other:
 * it earns no exemption.
 *
 * A rule must be deterministic, or reproducibility is lost. One that needs randomness must draw it
 * from a stream the model controls rather than from a global source.
 */
fun interface RouteSelectionRuleIfc {

    /**
     * The zones the transporter should cross, in order, ending at the destination's zone.
     *
     * @param network the network being travelled, which may be consulted for occupancy
     * @param fromZone the zone the transporter holds now, which is not part of the returned sequence
     * @param travellingForward the direction the transporter faces when it sits on a link zone,
     *   meaningless when it sits at an intersection
     * @param toIntersection where it is going
     * @return the zones to cross, never empty unless the transporter is already there
     */
    fun selectRoute(
        network: GuidedPathNetwork,
        fromZone: Zone,
        travellingForward: Boolean,
        toIntersection: GuidedPathNetwork.Intersection
    ): List<Zone>
}

/**
 * Sends every transporter along the shortest path, ignoring where other transporters are.
 *
 * This is the default, and it is what the reference tools do. Congestion is left to resolve itself
 * through blocking rather than being anticipated, which keeps routing off the hot path and keeps
 * the behaviour of a model explicable: a transporter's path depends only on the layout, never on
 * what happened to be in the way when it was dispatched.
 */
class ShortestPathRouteRule : RouteSelectionRuleIfc {

    override fun selectRoute(
        network: GuidedPathNetwork,
        fromZone: Zone,
        travellingForward: Boolean,
        toIntersection: GuidedPathNetwork.Intersection
    ): List<Zone> = network.shortestPathZones(fromZone, travellingForward, toIntersection)

    override fun toString(): String = "ShortestPathRouteRule"
}
