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

/** What an unallocated transporter should do. */
sealed class IdleDisposition {

    /** Stay put, holding the zones it stands on. */
    data object ParkInPlace : IdleDisposition()

    /** Go to the transporter's own home base, or stay put when it has none. */
    data object ReturnToHomeBase : IdleDisposition()

    /**
     * Go to a named junction or station.
     *
     * @param locationName where to wait
     */
    data class MoveTo(val locationName: String) : IdleDisposition()
}

/**
 * Decides what a transporter does when it has nothing to carry.
 *
 * This looks like a convenience and is not. A stopped transporter goes on holding the zones it
 * stands on, so where a fleet idles decides how much of the guide path is unavailable to everyone
 * else -- and an idle transporter standing where others need to pass will block them indefinitely,
 * with no error and a run that completes looking entirely reasonable. Designing that out is what
 * home spurs and staging areas are for, and this rule is how a model expresses the choice.
 *
 * A rule is consulted only when nothing is waiting for the transporter. It cannot override the pool
 * handing a transporter straight to the next entity in the queue, which would strand it while work
 * waited.
 */
fun interface IdleDispositionRuleIfc {

    /**
     * @param transporter the transporter that has just been released
     * @return what it should do now
     */
    fun disposition(transporter: GuidedTransporter): IdleDisposition
}

/**
 * Leaves a released transporter where it is.
 *
 * The default, matching what the free-path transporters do and what the reference tools do. It is
 * also the choice most likely to produce a guide path that quietly stops moving, so the guide
 * recommends changing it as soon as a model has more than one transporter.
 */
class ParkInPlaceRule : IdleDispositionRuleIfc {
    override fun disposition(transporter: GuidedTransporter): IdleDisposition =
        IdleDisposition.ParkInPlace

    override fun toString(): String = "ParkInPlaceRule"
}

/**
 * Sends a released transporter back to its own home base, so that it waits somewhere chosen rather
 * than wherever its last job happened to end.
 *
 * With a home base on a spur, this is the arrangement the source text recommends: an idle vehicle
 * is out of the traffic entirely.
 */
class ReturnToHomeBaseRule : IdleDispositionRuleIfc {
    override fun disposition(transporter: GuidedTransporter): IdleDisposition =
        if (transporter.homeBase != null) IdleDisposition.ReturnToHomeBase
        else IdleDisposition.ParkInPlace

    override fun toString(): String = "ReturnToHomeBaseRule"
}

/**
 * Sends every released transporter to one shared waiting place.
 *
 * Simpler to set up than a home base each, and appropriate where a fleet genuinely does queue at
 * one staging area. Note that transporters will then contend for the way there, so a staging area
 * on the main path is worse than no policy at all.
 *
 * @param locationName the junction or station to wait at
 */
class MoveToStagingAreaRule(val locationName: String) : IdleDispositionRuleIfc {
    override fun disposition(transporter: GuidedTransporter): IdleDisposition =
        IdleDisposition.MoveTo(locationName)

    override fun toString(): String = "MoveToStagingAreaRule($locationName)"
}

// ---- naming ------------------------------------------------------------------------------------
//
// A rule is offered by name only when a name is all it takes to define it. [MoveToStagingAreaRule]
// is absent below because it takes a location that no name could carry; it is set by assigning the
// object. That line is deliberate throughout the package: a name that silently stood for one
// parameterisation out of many would let a study vary the label while freezing the number, and
// report the result as a comparison of rules.

/** The idle dispositions a name can select, in the order an interface should offer them. */
val idleDispositionRuleNames: List<String> = listOf("ParkInPlace", "ReturnToHomeBase")

/**
 * The rule a name stands for, made fresh.
 *
 * @throws IllegalArgumentException if the name is not one of [idleDispositionRuleNames]
 */
fun createIdleDispositionRule(name: String): IdleDispositionRuleIfc = when (name) {
    "ParkInPlace" -> ParkInPlaceRule()
    "ReturnToHomeBase" -> ReturnToHomeBaseRule()
    else -> throw IllegalArgumentException(
        "Unknown idle disposition rule '$name'; expected one of $idleDispositionRuleNames. A rule " +
                "that takes an argument, such as MoveToStagingAreaRule, is set by assigning it."
    )
}

/** The name of a rule, or null when it is one no name stands for. */
fun nameOfIdleDispositionRule(rule: IdleDispositionRuleIfc): String? = when (rule) {
    is ParkInPlaceRule -> "ParkInPlace"
    is ReturnToHomeBaseRule -> "ReturnToHomeBase"
    else -> null
}
