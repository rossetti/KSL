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

import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.Zone

/**
 * When a transporter gives up the zone behind it, relative to entering the next one.
 *
 * The choice decides how closely transporters may follow one another, and so how much of the guide
 * path a fleet can use at once. It is a modelling parameter, not an implementation detail: the
 * control system of a real installation implements one of these, and which one it implements
 * changes throughput.
 */
sealed class ZoneReleaseTiming {

    /**
     * Give up the zone behind at the instant travel into the next one begins, so that a following
     * transporter may claim it immediately. The closest following.
     */
    data object AtStart : ZoneReleaseTiming()

    /**
     * Give up the zone behind only on arriving in the next one, keeping a follower a full zone
     * further back. The greater separation, and the default.
     */
    data object AtEnd : ZoneReleaseTiming()

    /**
     * Give up the zone behind after travelling the given distance into the next one: the general
     * case, with the other two as its endpoints.
     *
     * @param distance how far into the next zone, greater than zero and no more than that zone's
     *   length
     */
    data class AfterDistance(val distance: Double) : ZoneReleaseTiming() {
        init {
            require(distance > 0.0) {
                "The release distance into the next zone must be > 0.0, but was $distance. Use " +
                        "AtStart for a release at the moment travel begins."
            }
        }
    }
}

/**
 * Decides when a transporter releases the zone behind it.
 *
 * A rule is consulted once for each zone a transporter travels into, and it decides timing only: it
 * must not claim, occupy, or release anything itself, and it must be a pure function of what it is
 * given, or the release times stop being reproducible.
 */
fun interface ZoneControlRuleIfc {

    /**
     * @param transporter the transporter about to travel into a zone
     * @param enteringZone the zone it is travelling into
     * @return when it should give up the zone behind it
     */
    fun releaseTiming(transporter: GuidedTransporter, enteringZone: Zone): ZoneReleaseTiming
}

/**
 * Releases the zone behind at the moment travel into the next begins, letting a follower close up
 * immediately. Use it where the real control system frees a zone as soon as the vehicle's tail
 * crosses the boundary.
 */
class StartOfZoneControl : ZoneControlRuleIfc {
    override fun releaseTiming(transporter: GuidedTransporter, enteringZone: Zone): ZoneReleaseTiming =
        ZoneReleaseTiming.AtStart

    override fun toString(): String = "StartOfZoneControl"
}

/**
 * Releases the zone behind only on arrival in the next one. The default, because it is the
 * conservative choice: it keeps transporters a zone further apart, and the separation is rarely
 * critical to how a system performs.
 */
class EndOfZoneControl : ZoneControlRuleIfc {
    override fun releaseTiming(transporter: GuidedTransporter, enteringZone: Zone): ZoneReleaseTiming =
        ZoneReleaseTiming.AtEnd

    override fun toString(): String = "EndOfZoneControl"
}

/**
 * Releases the zone behind after travelling a fixed distance into the next one: the general case,
 * with release at the start and release at the end as its two extremes.
 *
 * Zone sizes vary from link to link, and junctions are usually treated as points with no length at
 * all, so one fixed distance will inevitably meet zones shorter than itself. Where it does, the
 * release happens at the far end of that zone, and where the zone has no length the release is
 * immediate. Both follow from reading the distance as "after travelling this far, or on leaving the
 * zone, whichever comes first", which keeps the behaviour monotone in the distance asked for.
 * Refusing instead would make the rule unusable on any network that mixes zone sizes.
 *
 * @param distance how far into the entered zone, greater than zero. A distance at or beyond the
 *   entered zone's length is rejected when the rule is consulted, because it would describe a
 *   release that happens after the transporter has already left that zone.
 */
class DistanceIntoZoneControl(val distance: Double) : ZoneControlRuleIfc {
    init {
        require(distance > 0.0) { "The release distance must be > 0.0, but was $distance." }
    }

    override fun releaseTiming(transporter: GuidedTransporter, enteringZone: Zone): ZoneReleaseTiming =
        ZoneReleaseTiming.AfterDistance(distance)

    override fun toString(): String = "DistanceIntoZoneControl($distance)"
}
