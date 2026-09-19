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
 * Thrown when a guided path network cannot be constructed, or cannot be initialized for a
 * replication, because its specification is inconsistent.
 *
 * This signals a *user specification error* rather than a programmer error: the modeler has
 * described a network that cannot exist. Argument and receiver-state violations by calling code
 * remain ordinary `IllegalArgumentException` and `IllegalStateException`.
 *
 * The exception is raised in two places. At network construction it reports geometry violations,
 * malformed spurs, duplicate names, and alias collisions. At replication initialization it reports
 * vehicle placements that overlap or that do not fit where they were placed. Rejecting a network
 * eagerly, at the point of description, is a deliberate design choice: the alternative is an
 * arithmetic failure part way through a long run, which is far harder to diagnose.
 *
 * Every message names the offending element and the values that make it invalid, so that a modeler
 * can act on the message alone without reading a stack trace. Build messages with the companion
 * factory functions rather than composing text at the throw site, so the wording stays uniform and
 * so the validation tests can assert against one definition of each message.
 */
class GuidedPathNetworkException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    @Suppress("unused")
    companion object {

        /**
         * The link's length is not an integral multiple of its zone length, so the link cannot be
         * divided into equal zones. Reports the remainder so that a modeler can tell a genuine
         * specification error from a unit mismatch.
         */
        fun linkGeometry(
            linkName: String,
            length: Double,
            zoneLength: Double,
            numZones: Int,
            remainder: Double
        ): GuidedPathNetworkException = GuidedPathNetworkException(
            "Link ($linkName): length ($length) is not an integral multiple of the zone length " +
                    "($zoneLength). The closest zone count is $numZones, leaving a remainder of " +
                    "$remainder. Either supply a length equal to numZones times zoneLength, or " +
                    "supply the zone count and let the zone length be derived."
        )

        /**
         * A property that must be strictly positive was given a zero or negative value.
         */
        fun nonPositive(
            elementKind: String,
            elementName: String,
            propertyName: String,
            value: Double
        ): GuidedPathNetworkException = GuidedPathNetworkException(
            "$elementKind ($elementName): $propertyName must be > 0.0, but was $value."
        )

        /**
         * A link was specified with fewer than one zone, so it represents no space at all.
         */
        fun zoneCountTooSmall(linkName: String, numZones: Int): GuidedPathNetworkException =
            GuidedPathNetworkException(
                "Link ($linkName): the number of zones must be >= 1, but was $numZones."
            )

        /**
         * A link begins and ends at the same intersection. A link must connect two distinct points.
         */
        fun selfLoop(linkName: String, intersectionName: String): GuidedPathNetworkException =
            GuidedPathNetworkException(
                "Link ($linkName): the beginning and ending intersection are both " +
                        "($intersectionName). A link must join two distinct intersections."
            )

        /**
         * Two elements of the same kind were given the same name. Names identify elements in
         * messages, in statistics, and in the serialized specification, so they must be unique.
         */
        fun duplicateName(elementKind: String, name: String): GuidedPathNetworkException =
            GuidedPathNetworkException(
                "Duplicate $elementKind name ($name). Each $elementKind must have a unique name."
            )

        /**
         * A station alias collides with an intersection name. Aliases and intersection names share
         * one namespace, because process code addresses destinations by either.
         */
        fun aliasCollision(alias: String, intersectionName: String): GuidedPathNetworkException =
            GuidedPathNetworkException(
                "Station alias ($alias) collides with the name of intersection " +
                        "($intersectionName). Aliases and intersection names share one namespace."
            )

        /**
         * A link declared as a spur ends at an intersection that is not a dead end. The spur exit
         * reservation depends on the terminal having exactly one incident link.
         */
        fun spurTerminalDegree(
            linkName: String,
            intersectionName: String,
            degree: Int,
            incidentLinkNames: List<String>
        ): GuidedPathNetworkException = GuidedPathNetworkException(
            "Link ($linkName) is declared a SPUR, but its ending intersection " +
                    "($intersectionName) has degree $degree rather than 1. Incident links: " +
                    "${incidentLinkNames.joinToString()}. A spur must end at a dead end."
        )

        /**
         * Two vehicles were placed so that the zones they occupy overlap. Because a zone admits at
         * most one vehicle, such a placement cannot be realized.
         */
        fun placementOverlap(
            firstTransporterName: String,
            secondTransporterName: String,
            zoneName: String
        ): GuidedPathNetworkException = GuidedPathNetworkException(
            "Initial placement conflict: transporters ($firstTransporterName) and " +
                    "($secondTransporterName) would both occupy zone ($zoneName). A zone admits " +
                    "at most one transporter."
        )

        /**
         * A vehicle was placed on a link that has fewer zones than the vehicle occupies.
         */
        fun transporterTooLongForPlacement(
            transporterName: String,
            lengthInZones: Int,
            linkName: String,
            numZones: Int
        ): GuidedPathNetworkException = GuidedPathNetworkException(
            "Transporter ($transporterName) occupies $lengthInZones zones but was placed on link " +
                    "($linkName), which has only $numZones. Place it on a longer link, or reduce " +
                    "its length or the zone length."
        )

        /**
         * A vehicle occupying more than one zone was placed at an intersection. An intersection is
         * a single zone, so it can hold only a one-zone vehicle.
         */
        fun multiZoneTransporterAtIntersection(
            transporterName: String,
            lengthInZones: Int,
            intersectionName: String
        ): GuidedPathNetworkException = GuidedPathNetworkException(
            "Transporter ($transporterName) occupies $lengthInZones zones but was placed at " +
                    "intersection ($intersectionName), which is a single zone. Place a multi-zone " +
                    "transporter on a link."
        )

        /**
         * A second transport system attempted to attach to a network that already has one. The
         * runtime owns the network's mutable zone state, so two runtimes would corrupt each other.
         */
        fun networkAlreadyAttached(
            networkName: String,
            attachedSystemName: String
        ): GuidedPathNetworkException = GuidedPathNetworkException(
            "Network ($networkName) is already attached to transport system " +
                    "($attachedSystemName). A network carries the zone state of exactly one " +
                    "running system. Build one network per model."
        )
    }
}
