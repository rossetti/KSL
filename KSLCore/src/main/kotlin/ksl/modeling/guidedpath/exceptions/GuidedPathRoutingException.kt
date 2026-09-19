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
package ksl.modeling.guidedpath.exceptions

/**
 * Thrown when a route cannot be produced between two points of a guided path network.
 *
 * Two situations raise it. The first is that no path exists: the destination is simply not
 * reachable from the origin, which a partially specified or deliberately disconnected network
 * permits. A network with unreachable pairs is legal at construction, because incremental model
 * building passes through such states; the error surfaces only when a route between the
 * unreachable pair is actually requested.
 *
 * The second is that a user-supplied route selection rule returned a sequence that is not a route:
 * consecutive zones that are not adjacent, a sequence that does not start where the vehicle is, or
 * one that does not end at the destination. Reporting the rule's class name matters here, because
 * the defect is in the extension rather than in the subsystem, and without the name a modeler has
 * no way to tell which of several rules misbehaved.
 *
 * A request naming a location that belongs to a different network, or a destination that is not an
 * intersection at all, is a programmer error rather than a routing failure and raises
 * `IllegalArgumentException` instead.
 */
class GuidedPathRoutingException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    @Suppress("unused")
    companion object {

        /**
         * No path exists through the network from the origin to the destination.
         */
        fun unreachable(fromName: String, toName: String): GuidedPathRoutingException =
            GuidedPathRoutingException(
                "No path exists from ($fromName) to ($toName) in the guided path network. " +
                        "Check the link directions: a unidirectional link cannot be traversed " +
                        "against its declared direction."
            )

        /**
         * A route selection rule returned a sequence containing two zones that are not adjacent.
         */
        fun nonAdjacentRoute(
            ruleClassName: String,
            firstZoneName: String,
            secondZoneName: String
        ): GuidedPathRoutingException = GuidedPathRoutingException(
            "Route selection rule ($ruleClassName) returned a sequence in which zone " +
                    "($firstZoneName) is not adjacent to the following zone ($secondZoneName). " +
                    "A route must be a sequence of pairwise adjacent zones."
        )

        /**
         * A route selection rule returned a sequence that does not begin where the vehicle is.
         */
        fun routeDoesNotStartAtOrigin(
            ruleClassName: String,
            expectedZoneName: String,
            actualZoneName: String
        ): GuidedPathRoutingException = GuidedPathRoutingException(
            "Route selection rule ($ruleClassName) returned a sequence starting at zone " +
                    "($actualZoneName), but the route must start at ($expectedZoneName), the " +
                    "successor of the transporter's current front zone."
        )

        /**
         * A route selection rule returned a sequence that does not end at the destination.
         */
        fun routeDoesNotReachDestination(
            ruleClassName: String,
            destinationName: String,
            actualZoneName: String
        ): GuidedPathRoutingException = GuidedPathRoutingException(
            "Route selection rule ($ruleClassName) returned a sequence ending at zone " +
                    "($actualZoneName), but the requested destination was ($destinationName)."
        )
    }
}
