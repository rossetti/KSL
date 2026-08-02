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

/**
 *  Elicits weights by the swing method.
 *
 *  Asking directly how important each metric is invites answers that ignore how much the metrics
 *  actually vary, and an additive model's weights are not importance in that sense; they are
 *  scaling constants tied to ranges. The swing method asks a question that has the ranges built
 *  into it. Starting from the worst outcome on every metric at once, which single metric would you
 *  most want moved to its best? That is the top swing. It anchors the scale at 100, and every other
 *  swing is rated against it: a swing rated 40 is worth four tenths of the top one. Normalizing the
 *  ratings gives weights that mean what the additive model needs them to mean.
 *
 *  The steps have to happen in order, since a rating is meaningless before there is a top swing to
 *  rate against, so this refuses to be used out of order rather than producing weights from
 *  incomplete answers.
 */
class SwingWeightElicitation(
    private val metrics: List<MetricIfc>
) {

    init {
        require(metrics.isNotEmpty()) { "There must be at least one metric to elicit weights for." }
        val duplicate = metrics.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Metric names must be unique to elicit weights. '${duplicate!!.key}' appears " +
                    "${duplicate.value.size} times."
        }
    }

    private val metricNames: List<String> = metrics.map { it.name }

    private var swingOrder: List<String>? = null

    // Kept in insertion order so that what is reported back follows the order things were asked in.
    private val ratings = linkedMapOf<String, Double>()

    /** The metrics from the most valuable swing to the least, or null before they have been ranked. */
    val order: List<String>?
        get() = swingOrder?.toList()

    /** The ratings given so far, against the top swing's 100. */
    val currentRatings: Map<String, Double>
        get() = ratings.toMap()

    /**
     *  Step one: put the swings in order, the most valuable first.
     *
     *  Ranking again starts over, discarding any ratings already given, since a rating is relative
     *  to whichever swing was top when it was given.
     *
     *  @param order every metric exactly once
     */
    fun rankSwings(order: List<String>) {
        require(order.size == metricNames.size && order.toSet() == metricNames.toSet()) {
            "Every metric must be ranked exactly once. Expected ${metricNames.sorted()} " +
                    "but got ${order.sorted()}."
        }
        swingOrder = order.toList()
        ratings.clear()
        // The top swing defines the scale, so it is fixed rather than asked about.
        ratings[order.first()] = TOP_SWING_RATING
    }

    /**
     *  Step two: rate one swing against the top one.
     *
     *  @param metric the metric whose swing is being rated, which may not be the top one
     *  @param rating how much of the top swing this one is worth, within [0, 100]
     */
    fun rateSwing(metric: String, rating: Double) {
        val ranked = swingOrder
        checkNotNull(ranked) { "Rank the swings before rating them." }
        require(metric in ranked) { "Unknown metric '$metric'. The study has: ${metricNames.joinToString(", ")}." }
        require(metric != ranked.first()) {
            "The top swing '${ranked.first()}' is fixed at $TOP_SWING_RATING and defines the scale, " +
                    "so it cannot be rated against itself."
        }
        require(rating in 0.0..TOP_SWING_RATING) {
            "A rating must be within [0, $TOP_SWING_RATING]. It was $rating."
        }
        ratings[metric] = rating
    }

    /** The metrics still waiting to be rated. */
    fun missing(): List<String> =
        swingOrder?.filter { !ratings.containsKey(it) } ?: metricNames

    /** Indicates whether every swing has been ranked and rated. */
    val isComplete: Boolean
        get() = swingOrder?.let { ratings.size == it.size } == true

    /**
     *  The weights the answers imply, normalized to sum to one.
     *
     *  @throws IllegalStateException if any swing is still unrated, or if every swing was rated
     *  zero, which leaves nothing to distribute
     */
    fun weights(): Map<String, Double> {
        check(isComplete) {
            "The elicitation is incomplete. Still unrated: ${missing().joinToString(", ")}."
        }
        val total = ratings.values.sum()
        check(total > 0.0) { "At least one swing must be rated above zero." }
        return ratings.mapValues { it.value / total }
    }

    /**
     *  Records the answers together with the ranges they were given against, so that a study run
     *  later can tell whether they still apply.
     *
     *  The ranges recorded are the ones declared for the metrics, which is what the person was
     *  shown when asked. If the study goes on to refit a range to the realized scores, the recorded
     *  range and the one used will differ, and [ElicitationRecord.isStillValidFor] will say so.
     */
    fun record(): ElicitationRecord {
        check(isComplete) {
            "The elicitation is incomplete. Still unrated: ${missing().joinToString(", ")}."
        }
        return ElicitationRecord(
            order = swingOrder.orEmpty(),
            ratings = ratings.toMap(),
            elicitedAgainst = metrics.associate {
                it.name to ElicitedRange(it.domain.lowerLimit, it.domain.upperLimit)
            },
            adjustableRanges = metrics
                .filter { it.allowLowerLimitAdjustment || it.allowUpperLimitAdjustment }
                .map { it.name }
        )
    }

    companion object {
        /** The rating given to the top swing, which anchors the scale everything else is rated on. */
        const val TOP_SWING_RATING: Double = 100.0
    }
}
