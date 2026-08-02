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

import kotlin.math.abs

/**
 *  A weight at which the alternative a study currently recommends would be displaced by another.
 *
 *  The [margin] is what makes this worth reporting: it says how far the weight on [metric] would
 *  have to move from where it is before the recommendation changes. A small margin means the
 *  recommendation rests on a weight the decision maker may not have felt strongly about.
 */
data class FlipPoint(
    val metric: String,
    val currentWeight: Double,
    val criticalWeight: Double,
    val displacedBy: String
) {
    /** How far the weight would have to move for the recommendation to change. */
    val margin: Double
        get() = abs(criticalWeight - currentWeight)
}

/**
 *  What a study would conclude if one metric carried a given weight.
 */
data class SweepPoint(
    val weight: Double,
    val overallValues: Map<String, Double>,
    val winner: String
)

/**
 *  Asks how much a study's recommendation depends on the weights it was given.
 *
 *  A weight in an additive model is not a statement of how important something is in the abstract;
 *  it is a scaling constant tied to the range the metric was measured over. Decision makers rarely
 *  hold weights precisely, so the useful question is not what the recommendation is but how far a
 *  weight would have to move before the recommendation changed. A recommendation that survives
 *  large movements is worth acting on; one that turns on the third decimal place of a weight is
 *  really a tie being reported as a decision.
 *
 *  Varying one metric's weight means deciding what happens to the others. They are held in their
 *  existing proportions to one another and scaled to fill whatever is left, which keeps the weights
 *  summing to one while changing only the one thing being asked about. With that convention each
 *  alternative's overall value is a straight line in the weight, so the point where two
 *  alternatives change places is solved directly rather than searched for.
 *
 *  This reads a [ModaSnapshot] rather than a live model, so the answers cannot drift underneath the
 *  caller while it asks a series of questions.
 */
class ModaSensitivity(
    private val snapshot: ModaSnapshot
) {

    /** The metrics of the study, in the order it declared them. */
    val metricNames: List<String> = snapshot.metrics.map { it.name }

    private val weights: Map<String, Double> = snapshot.metrics.associate { it.name to it.weight }

    /** The sum, over every metric but the excluded one, of that metric's weight times its value. */
    private fun partial(alternative: String, exclude: String): Double {
        val values = snapshot.values[alternative]
            ?: throw IllegalArgumentException("The alternative '$alternative' is not part of the study.")
        var sum = 0.0
        for ((metric, value) in values) {
            if (metric == exclude) continue
            sum += (weights[metric] ?: 0.0) * value
        }
        return sum
    }

    private fun requireMetric(metric: String) {
        require(weights.containsKey(metric)) {
            "The metric '$metric' is not part of the study. It has: ${metricNames.joinToString(", ")}."
        }
    }

    /**
     *  The overall value of every alternative if [metric] carried the given [weight], with the other
     *  metrics held in their existing proportions and scaled to fill the remainder.
     *
     *  @param weight a weight within [0, 1]
     */
    fun overallValuesAt(metric: String, weight: Double): Map<String, Double> {
        requireMetric(metric)
        require(weight in 0.0..1.0) { "A weight must be within [0, 1]. It was $weight." }
        val currentWeight = weights[metric]!!
        val remainder = 1.0 - currentWeight
        return snapshot.alternatives.associateWith { alternative ->
            val own = snapshot.values[alternative]!![metric] ?: 0.0
            // With no weight left over there is nothing to rescale, and the others contribute
            // nothing however the weight is set.
            val rest = if (remainder <= 0.0) 0.0 else partial(alternative, metric) * (1.0 - weight) / remainder
            weight * own + rest
        }
    }

    /**
     *  The alternative a study would recommend if [metric] carried the given [weight]. Alternatives
     *  that tie are resolved by name, the same way a snapshot resolves them, so asking twice gives
     *  the same answer.
     */
    fun winnerAt(metric: String, weight: Double): String {
        val values = overallValuesAt(metric, weight)
        return snapshot.alternatives.sortedWith(
            compareByDescending<String> { values[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
        ).first()
    }

    /**
     *  The weight on [metric] at which alternatives [a] and [b] change places, or null when they
     *  never do.
     *
     *  Null covers several situations, all of which mean the same thing to a caller: there is no
     *  weight in [0, 1] at which the order of these two changes. That happens when the metric
     *  already carries all the weight, so there is nothing to redistribute; when the study has only
     *  one metric, so its weight is fixed at one; when the two alternatives keep a constant gap
     *  however the weight moves; and when the crossing exists mathematically but lies outside the
     *  range a weight can take.
     */
    fun criticalWeight(metric: String, a: String, b: String): Double? {
        requireMetric(metric)
        require(snapshot.values.containsKey(a)) { "The alternative '$a' is not part of the study." }
        require(snapshot.values.containsKey(b)) { "The alternative '$b' is not part of the study." }
        val currentWeight = weights[metric]!!
        if (currentWeight >= 1.0) return null
        if (metricNames.size < 2) return null
        // The gap between the two, as a straight line in the weight: gap(t) = rest + t * (own - rest)
        val own = (snapshot.values[a]!![metric] ?: 0.0) - (snapshot.values[b]!![metric] ?: 0.0)
        val rest = (partial(a, metric) - partial(b, metric)) / (1.0 - currentWeight)
        if (own == rest) return null
        val crossing = rest / (rest - own)
        if (crossing !in 0.0..1.0) return null
        // A crossing exactly at either end arrives as a negative zero when the numerator is zero
        // and the denominator negative. It is the same weight, but reporting it as -0.0 would be
        // an odd thing to hand back and would not compare as a caller expects.
        return if (crossing == 0.0) 0.0 else crossing
    }

    /**
     *  Every weight on [metric] at which the study's current recommendation would be displaced,
     *  nearest first, so the most fragile comparison is reported first. Ties are broken by the name
     *  of the displacing alternative so the order is the same every time.
     */
    fun flipPointsForWinner(metric: String): List<FlipPoint> {
        requireMetric(metric)
        val winner = snapshot.primaryRecommendation
        val currentWeight = weights[metric]!!
        return snapshot.alternatives
            .filter { it != winner }
            .mapNotNull { other ->
                criticalWeight(metric, winner, other)?.let { crossing ->
                    FlipPoint(metric, currentWeight, crossing, other)
                }
            }
            .sortedWith(compareBy({ it.margin }, { it.displacedBy }))
    }

    /**
     *  The weight this study's recommendation is most fragile to, across every metric, or null when
     *  no weight within [0, 1] would change it. A null here is the strongest thing sensitivity can
     *  say for a recommendation: no single weight can be moved anywhere and displace it.
     */
    fun mostCriticalMetric(): FlipPoint? =
        metricNames.flatMap { flipPointsForWinner(it) }
            .minWithOrNull(compareBy({ it.margin }, { it.metric }, { it.displacedBy }))

    /**
     *  The study evaluated across the whole range of weights for [metric], for plotting or for
     *  reporting where the recommendation changes hands.
     *
     *  Both endpoints are included, so a sweep of n steps returns n + 1 points.
     *
     *  @param steps how many intervals to divide [0, 1] into, at least one
     */
    fun weightSweep(metric: String, steps: Int = 50): List<SweepPoint> {
        requireMetric(metric)
        require(steps >= 1) { "A sweep needs at least one step. It was $steps." }
        return (0..steps).map { step ->
            // Computed from the step rather than accumulated, so the endpoint is exactly 1.0.
            val weight = step.toDouble() / steps.toDouble()
            val values = overallValuesAt(metric, weight)
            val winner = snapshot.alternatives.sortedWith(
                compareByDescending<String> { values[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
            ).first()
            SweepPoint(weight, values, winner)
        }
    }
}
