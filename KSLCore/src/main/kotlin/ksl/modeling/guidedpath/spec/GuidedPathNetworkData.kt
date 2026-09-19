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
package ksl.modeling.guidedpath.spec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.utilities.math.KSLMath

/**
 * A junction, as it appears in a serialized network specification.
 *
 * The length defaults to zero, the usual assumption that a junction is a point. Coordinates default
 * to not-a-number, meaning the modeler supplied no layout; the animation layer treats that as
 * "place it yourself" rather than as the origin.
 *
 * @param name unique within the network, and the name process code uses to address the junction
 * @param length the distance to cross the junction, zero or more
 * @param velocityFactor multiplies transporter velocity while crossing, strictly positive
 * @param x layout abscissa, or not-a-number when no layout was supplied
 * @param y layout ordinate, or not-a-number when no layout was supplied
 * @param z layout height, defaulting to zero rather than to not-a-number. A guide path with no
 *   layout at all is a flat one at ground level, which is a meaningful position; a height of
 *   not-a-number would be a third way of saying "unspecified" that every reader would have to
 *   handle, and would poison any renderer that added it to a camera. The two planar coordinates keep
 *   not-a-number because *there* it distinguishes "no layout" from "the origin", and a reader
 *   testing `x.isNaN()` for that still gets the right answer.
 */
@Serializable
data class IntersectionData(
    var name: String,
    var length: Double = 0.0,
    var velocityFactor: Double = 1.0,
    var x: Double = Double.NaN,
    var y: Double = Double.NaN,
    var z: Double = 0.0
) {
    init {
        if (name.isBlank()) {
            throw GuidedPathNetworkException("An intersection name must not be blank.")
        }
        if (length < 0.0) {
            throw GuidedPathNetworkException.nonPositive("Intersection", name, "length", length)
        }
        if (velocityFactor <= 0.0) {
            throw GuidedPathNetworkException
                .nonPositive("Intersection", name, "velocityFactor", velocityFactor)
        }
    }
}

/**
 * A run of guide path, as it appears in a serialized network specification.
 *
 * Both the zone length and the zone count are carried even though either determines the other given
 * the length. That redundancy is deliberate: it makes the specification self-describing, and it
 * lets a validation failure report which of the three quantities the modeler actually supplied
 * rather than guessing. The three are required to agree, within the library's default numerical
 * precision so that a length written as a product of decimals is not rejected for a rounding error
 * the modeler cannot see.
 *
 * Prefer the companion factory functions to the constructor: they derive the redundant quantity
 * instead of asking for it, which removes the commonest way to write an inconsistent link.
 *
 * @param name unique within the network
 * @param fromIntersection the name of the beginning intersection
 * @param toIntersection the name of the ending intersection, different from the beginning
 * @param length the total length in the modeler's units, strictly positive
 * @param zoneLength the length of each zone, strictly positive
 * @param numZones how many zones divide the link, at least one
 * @param type whether traffic may run one way, both ways, or into a dead end
 * @param velocityFactor multiplies transporter velocity on this link, strictly positive
 * @param beginDirection direction of travel in degrees leaving the beginning intersection. Layout
 *   metadata only: it does not affect travel time.
 */
@Serializable
data class LinkData(
    var name: String,
    var fromIntersection: String,
    var toIntersection: String,
    var length: Double,
    var zoneLength: Double,
    var numZones: Int,
    var type: LinkType = LinkType.UNIDIRECTIONAL,
    var velocityFactor: Double = 1.0,
    var beginDirection: Double = 0.0
) {
    init {
        if (name.isBlank()) {
            throw GuidedPathNetworkException("A link name must not be blank.")
        }
        if (fromIntersection == toIntersection) {
            throw GuidedPathNetworkException.selfLoop(name, fromIntersection)
        }
        if (length <= 0.0) {
            throw GuidedPathNetworkException.nonPositive("Link", name, "length", length)
        }
        if (zoneLength <= 0.0) {
            throw GuidedPathNetworkException.nonPositive("Link", name, "zoneLength", zoneLength)
        }
        if (velocityFactor <= 0.0) {
            throw GuidedPathNetworkException.nonPositive("Link", name, "velocityFactor", velocityFactor)
        }
        if (numZones < 1) {
            throw GuidedPathNetworkException.zoneCountTooSmall(name, numZones)
        }
        val implied = numZones * zoneLength
        if (!KSLMath.equal(implied, length)) {
            throw GuidedPathNetworkException
                .linkGeometry(name, length, zoneLength, numZones, length - implied)
        }
    }

    companion object {

        /**
         * Builds a link from its length and the zone size the control system enforces, deriving the
         * zone count. Use this when the zone size is the quantity the real system fixes.
         *
         * The length must divide evenly by the zone length, and a remainder is reported rather than
         * rounded away, because silently rounding would change the amount of space being modeled.
         */
        @JvmStatic
        @JvmOverloads
        fun byZoneLength(
            name: String,
            fromIntersection: String,
            toIntersection: String,
            length: Double,
            zoneLength: Double,
            type: LinkType = LinkType.UNIDIRECTIONAL,
            velocityFactor: Double = 1.0,
            beginDirection: Double = 0.0
        ): LinkData {
            if (zoneLength <= 0.0) {
                throw GuidedPathNetworkException.nonPositive("Link", name, "zoneLength", zoneLength)
            }
            val exact = length / zoneLength
            val count = Math.round(exact).toInt()
            if (count < 1) {
                throw GuidedPathNetworkException.zoneCountTooSmall(name, count)
            }
            return LinkData(
                name, fromIntersection, toIntersection, length, zoneLength, count,
                type, velocityFactor, beginDirection
            )
        }

        /**
         * Builds a link from its length and how many zones divide it, deriving the zone length.
         *
         * This form cannot produce a geometry mismatch, because the zone length is computed rather
         * than supplied, so it is the safer of the two whenever the zone count is what the modeler
         * actually knows.
         */
        @JvmStatic
        @JvmOverloads
        fun byZoneCount(
            name: String,
            fromIntersection: String,
            toIntersection: String,
            length: Double,
            numZones: Int,
            type: LinkType = LinkType.UNIDIRECTIONAL,
            velocityFactor: Double = 1.0,
            beginDirection: Double = 0.0
        ): LinkData {
            if (numZones < 1) {
                throw GuidedPathNetworkException.zoneCountTooSmall(name, numZones)
            }
            return LinkData(
                name, fromIntersection, toIntersection, length, length / numZones, numZones,
                type, velocityFactor, beginDirection
            )
        }
    }
}

/**
 * A complete guide path network as data: everything needed to rebuild an equivalent network, and
 * nothing that belongs to a running model.
 *
 * Intersections need not be listed. Any intersection a link names is created implicitly with
 * default properties, so a network of point junctions is described by its links alone. List an
 * intersection only to give it a length, a velocity factor, or layout coordinates.
 *
 * Station aliases are a flat map from alias to intersection name rather than a field on each
 * intersection, so that adding an alias does not require rewriting an intersection record. That
 * matters when the specification is generated by a scenario sweep.
 *
 * @param name the network's name
 * @param links the links, at least one
 * @param intersections optional property overrides for named junctions
 * @param stationAliases additional names by which process code may address intersections
 */
@Serializable
data class GuidedPathNetworkData(
    var name: String,
    var links: List<LinkData>,
    var intersections: List<IntersectionData> = emptyList(),
    var stationAliases: Map<String, String> = emptyMap()
) {
    init {
        if (links.isEmpty()) {
            throw GuidedPathNetworkException(
                "Network ($name): a guided path network must have at least one link."
            )
        }
    }

    /** Renders this specification as JSON. */
    fun toJson(): String = Json { prettyPrint = true }.encodeToString(serializer(), this)

    companion object {

        /**
         * Parses a specification from JSON. The result is validated exactly as a hand-written
         * specification is, so a malformed document fails at the same point and with the same
         * message it would have if the modeler had typed it.
         */
        @JvmStatic
        fun fromJson(json: String): GuidedPathNetworkData =
            Json.decodeFromString(serializer(), json)
    }
}
