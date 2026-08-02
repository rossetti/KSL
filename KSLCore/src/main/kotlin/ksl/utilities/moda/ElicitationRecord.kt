/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.moda

/**
 *  The range a metric was measured over when a weight was elicited for it.
 *
 *  Held as limits rather than as text so that whether a range has moved is a question about
 *  numbers, which can be answered, rather than about how an interval happened to be printed.
 */
data class ElicitedRange(
    val lowerLimit: Double,
    val upperLimit: Double
)

/**
 *  What was asked, and what was answered, when weights were elicited by the swing method, together
 *  with the ranges the answers were given against.
 *
 *  The ranges are the point of keeping this. A weight in an additive model is a scaling constant
 *  tied to a range: saying cost matters twice as much as delay means the swing from the worst cost
 *  to the best cost is worth twice the swing from the worst delay to the best delay. Change either
 *  range afterwards and the answer no longer means what the person who gave it meant, even though
 *  the number is unchanged. Recording the ranges is what makes that detectable rather than silent.
 *
 *  @param order the metrics from the most valuable swing to the least
 *  @param ratings each metric's swing rated against the top one, which is fixed at 100
 *  @param elicitedAgainst the range each metric was measured over when the ratings were given
 *  @param adjustableRanges the metrics whose range the study permits to be refitted, and whose
 *  weights are therefore the ones at risk of being invalidated later
 */
data class ElicitationRecord(
    val order: List<String>,
    val ratings: Map<String, Double>,
    val elicitedAgainst: Map<String, ElicitedRange>,
    val adjustableRanges: List<String>
) {

    /** The weights these answers imply, normalized to sum to one. */
    fun weights(): Map<String, Double> {
        val total = ratings.values.sum()
        check(total > 0.0) { "At least one swing must have been rated above zero." }
        return ratings.mapValues { it.value / total }
    }

    /**
     *  The metrics whose range has moved since the weights were elicited, comparing against the
     *  ranges [snapshot] actually evaluated over.
     *
     *  Any difference counts. A range that has been refitted to the realized scores is a different
     *  range from the one the person was asked about, whether it moved a lot or a little, and the
     *  weight they gave does not carry over to it.
     */
    fun rangesThatMoved(snapshot: ModaSnapshot): List<String> =
        elicitedAgainst.entries
            .mapNotNull { (name, elicited) ->
                val record = snapshot.metric(name) ?: return@mapNotNull name
                val moved = record.effectiveLowerLimit != elicited.lowerLimit ||
                        record.effectiveUpperLimit != elicited.upperLimit
                if (moved) name else null
            }
            .sorted()

    /**
     *  Indicates whether these weights still mean what they meant, given what [snapshot] evaluated
     *  over. When this is false the weights should be discarded rather than applied to ranges they
     *  were not elicited against.
     */
    fun isStillValidFor(snapshot: ModaSnapshot): Boolean = rangesThatMoved(snapshot).isEmpty()

    /**
     *  A description of why these weights no longer apply to [snapshot], or null when they still
     *  do. Written to be shown to whoever gave the answers.
     */
    fun invalidationReason(snapshot: ModaSnapshot): String? {
        val moved = rangesThatMoved(snapshot)
        if (moved.isEmpty()) return null
        return "The weights were elicited against ranges that have since changed for: " +
                "${moved.joinToString(", ")}. A swing weight is tied to the range it was given " +
                "against, so these weights no longer describe the study and should be elicited " +
                "again, or the study run with its ranges fixed."
    }
}
