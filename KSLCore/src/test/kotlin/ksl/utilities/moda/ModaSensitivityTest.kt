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

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

/**
 *  Tests for asking how much a recommendation depends on the weights behind it.
 *
 *  The weight at which two alternatives change places is solved directly rather than searched for,
 *  which is worth doing but easy to get subtly wrong. The main test here therefore does not check
 *  the formula against itself: it generates studies at random, searches for the crossing by
 *  brute force, and requires the two to agree. Everything else covers the cases where there is no
 *  crossing to find, each of which has to have a defined answer rather than an exception or a
 *  meaningless number.
 */
class ModaSensitivityTest {

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private fun studyOf(
        metricScores: Map<String, Map<String, Double>>,
        weights: Map<String, Double>? = null,
        allowRescaling: Boolean = true
    ): ModaSnapshot {
        val metricNames = metricScores.values.first().keys.toList()
        val metrics = metricNames.associateWith { Metric(it, Interval(0.0, 100.0)) }
        val definitions: Map<MetricIfc, ValueFunctionIfc> =
            metrics.values.associate { metric -> metric as MetricIfc to LinearValueFunction() }
        val model = if (weights == null) {
            AdditiveMODAModel(definitions, name = "Study")
        } else {
            AdditiveMODAModel(definitions, weights.mapKeys { metrics[it.key]!! }, name = "Study")
        }
        model.defineAlternatives(
            metricScores.mapValues { (_, byMetric) ->
                metricNames.map { Score(metrics[it]!!, byMetric[it]!!) }
            },
            allowRescalingByMetrics = allowRescaling
        )
        return ModaSnapshot.of(model)
    }

    private fun threeByTwo(): ModaSnapshot = studyOf(
        mapOf(
            "Alpha" to mapOf("Cost" to 20.0, "Delay" to 80.0),
            "Beta" to mapOf("Cost" to 50.0, "Delay" to 50.0),
            "Gamma" to mapOf("Cost" to 80.0, "Delay" to 20.0)
        )
    )

    // ------------------------------------------------------------------------------------------
    // The gate: the closed form agrees with a brute-force search
    // ------------------------------------------------------------------------------------------

    private fun gapAt(sensitivity: ModaSensitivity, metric: String, a: String, b: String, weight: Double): Double {
        val values = sensitivity.overallValuesAt(metric, weight)
        return values[a]!! - values[b]!!
    }

    /**
     *  Finds the crossing by walking the whole range and watching for the gap to change sign, then
     *  placing it within the bracket where that happened. Deliberately naive: it knows nothing
     *  about the formula it is checking.
     */
    private fun searchForCrossing(
        sensitivity: ModaSensitivity,
        metric: String,
        a: String,
        b: String,
        steps: Int = 20_000
    ): Double? {
        var previousWeight = 0.0
        var previousGap = gapAt(sensitivity, metric, a, b, 0.0)
        if (previousGap == 0.0) return 0.0
        for (step in 1..steps) {
            val weight = step.toDouble() / steps.toDouble()
            val gap = gapAt(sensitivity, metric, a, b, weight)
            if (gap == 0.0) return weight
            if ((previousGap < 0.0) != (gap < 0.0)) {
                // The gap moves in a straight line, so placing the crossing within the bracket is
                // exact rather than approximate.
                return previousWeight + (weight - previousWeight) * (previousGap / (previousGap - gap))
            }
            previousWeight = weight
            previousGap = gap
        }
        return null
    }

    private fun randomStudy(rng: RNStreamIfc, metricCount: Int, alternativeCount: Int): ModaSnapshot {
        val metricNames = (1..metricCount).map { "M$it" }
        val scores = (1..alternativeCount).associate { index ->
            "Alt$index" to metricNames.associateWith { rng.randU01() * 100.0 }
        }
        val weights = metricNames.associateWith { 0.05 + rng.randU01() }
        return studyOf(scores, weights)
    }

    @Test
    @DisplayName("the weight where two alternatives change places agrees with searching for it")
    fun theWeightWhereTwoAlternativesChangePlacesAgreesWithSearchingForIt() {
        // Its own provider, so the studies generated here do not depend on what else has run.
        val rng = RNStreamProvider().rnStream(1)
        var crossingsFound = 0
        repeat(60) {
            val metricCount = 2 + (rng.randU01() * 3).toInt()          // 2..4 metrics
            val alternativeCount = 2 + (rng.randU01() * 4).toInt()     // 2..5 alternatives
            val snapshot = randomStudy(rng, metricCount, alternativeCount)
            val sensitivity = ModaSensitivity(snapshot)
            for (metric in sensitivity.metricNames) {
                for (a in snapshot.alternatives) {
                    for (b in snapshot.alternatives) {
                        if (a >= b) continue
                        val solved = sensitivity.criticalWeight(metric, a, b)
                        val searched = searchForCrossing(sensitivity, metric, a, b)
                        val case = "metric=$metric, a=$a, b=$b, solved=$solved, searched=$searched"
                        if (solved == null) {
                            assertNull(searched, "a crossing was found by search but not solved for: $case")
                        } else {
                            assertNotNull(searched, "a crossing was solved for but not found by search: $case")
                            assertEquals(searched, solved, 1.0e-7, "the two disagree on where: $case")
                            // And at that weight the two really are level.
                            assertEquals(
                                0.0, gapAt(sensitivity, metric, a, b, solved), 1.0e-9,
                                "the alternatives are not level at the solved weight: $case"
                            )
                            crossingsFound++
                        }
                    }
                }
            }
        }
        assertTrue(crossingsFound > 50, "too few crossings were exercised to be meaningful: $crossingsFound")
    }

    // ------------------------------------------------------------------------------------------
    // Cases where there is no crossing, each with a defined answer
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a study with one metric has no weight that could be varied")
    fun aStudyWithOneMetricHasNoWeightThatCouldBeVaried() {
        val snapshot = studyOf(
            mapOf(
                "Alpha" to mapOf("Only" to 20.0),
                "Beta" to mapOf("Only" to 80.0)
            )
        )
        val sensitivity = ModaSensitivity(snapshot)
        assertNull(sensitivity.criticalWeight("Only", "Alpha", "Beta"))
        assertTrue(sensitivity.flipPointsForWinner("Only").isEmpty())
        assertNull(sensitivity.mostCriticalMetric())
    }

    @Test
    @DisplayName("a metric already carrying all the weight leaves nothing to redistribute")
    fun aMetricAlreadyCarryingAllTheWeightLeavesNothingToRedistribute() {
        val snapshot = studyOf(
            mapOf(
                "Alpha" to mapOf("Cost" to 20.0, "Delay" to 80.0),
                "Beta" to mapOf("Cost" to 80.0, "Delay" to 20.0)
            ),
            // Weights are normalized, so this gives Cost the whole of it.
            weights = mapOf("Cost" to 1.0, "Delay" to 0.0)
        )
        val sensitivity = ModaSensitivity(snapshot)
        assertNull(sensitivity.criticalWeight("Cost", "Alpha", "Beta"))
    }

    /**
     *  Two alternatives separated by the same amount on every metric keep that separation however
     *  the weight is shared out, so there is no weight at which they change places.
     */
    @Test
    @DisplayName("alternatives separated equally on every metric never change places")
    fun alternativesSeparatedEquallyOnEveryMetricNeverChangePlaces() {
        val snapshot = studyOf(
            mapOf(
                "Alpha" to mapOf("Cost" to 20.0, "Delay" to 20.0),
                "Beta" to mapOf("Cost" to 60.0, "Delay" to 60.0)
            ),
            weights = mapOf("Cost" to 0.5, "Delay" to 0.5),
            allowRescaling = false
        )
        val sensitivity = ModaSensitivity(snapshot)
        assertNull(
            sensitivity.criticalWeight("Cost", "Alpha", "Beta"),
            "a constant separation should never produce a change of places"
        )
    }

    /**
     *  Alternatives that are level on every metric but one do change places, at the weight where
     *  the one that separates them stops counting. That weight is zero, and it has to be reported
     *  as an ordinary zero rather than as a negative one.
     */
    @Test
    @DisplayName("alternatives differing on only one metric change places where that metric stops counting")
    fun alternativesDifferingOnOnlyOneMetricChangePlacesWhereThatMetricStopsCounting() {
        val snapshot = studyOf(
            mapOf(
                "Alpha" to mapOf("Cost" to 50.0, "Delay" to 20.0),
                "Beta" to mapOf("Cost" to 50.0, "Delay" to 80.0)
            ),
            weights = mapOf("Cost" to 0.5, "Delay" to 0.5),
            allowRescaling = false
        )
        val sensitivity = ModaSensitivity(snapshot)
        val crossing = sensitivity.criticalWeight("Delay", "Alpha", "Beta")
        assertNotNull(crossing)
        assertEquals(0.0, crossing)
        assertTrue(1.0 / crossing > 0.0, "the weight was reported as a negative zero")
        assertEquals(
            0.0, gapAt(sensitivity, "Delay", "Alpha", "Beta", crossing), 1.0e-12,
            "the alternatives are not level at the reported weight"
        )
    }

    @Test
    @DisplayName("a crossing outside the range a weight can take is not reported")
    fun aCrossingOutsideTheRangeAWeightCanTakeIsNotReported() {
        // Alpha beats Beta on both metrics, so no redistribution of weight can displace it.
        val snapshot = studyOf(
            mapOf(
                "Alpha" to mapOf("Cost" to 10.0, "Delay" to 10.0),
                "Beta" to mapOf("Cost" to 90.0, "Delay" to 90.0)
            ),
            allowRescaling = false
        )
        val sensitivity = ModaSensitivity(snapshot)
        assertNull(sensitivity.criticalWeight("Cost", "Alpha", "Beta"))
        assertNull(sensitivity.mostCriticalMetric(), "a dominated alternative should never displace the winner")
    }

    @Test
    @DisplayName("asking about something that is not in the study is refused")
    fun askingAboutSomethingThatIsNotInTheStudyIsRefused() {
        val sensitivity = ModaSensitivity(threeByTwo())
        assertFailsWith<IllegalArgumentException> { sensitivity.criticalWeight("Nope", "Alpha", "Beta") }
        assertFailsWith<IllegalArgumentException> { sensitivity.criticalWeight("Cost", "Nope", "Beta") }
        assertFailsWith<IllegalArgumentException> { sensitivity.overallValuesAt("Cost", 1.5) }
    }

    // ------------------------------------------------------------------------------------------
    // Consistency with the study it came from
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("at its existing weight a metric reproduces the study's own values")
    fun atItsExistingWeightAMetricReproducesTheStudySOwnValues() {
        val snapshot = threeByTwo()
        val sensitivity = ModaSensitivity(snapshot)
        for (metric in sensitivity.metricNames) {
            val current = snapshot.metric(metric)!!.weight
            val values = sensitivity.overallValuesAt(metric, current)
            for (alternative in snapshot.alternatives) {
                assertEquals(
                    snapshot.overallValues[alternative]!!, values[alternative]!!, 1.0e-12,
                    "varying $metric to its existing weight changed $alternative"
                )
            }
        }
    }

    @Test
    @DisplayName("the winner at a metric's existing weight is the study's own recommendation")
    fun theWinnerAtAMetricSExistingWeightIsTheStudySOwnRecommendation() {
        val snapshot = threeByTwo()
        val sensitivity = ModaSensitivity(snapshot)
        for (metric in sensitivity.metricNames) {
            assertEquals(
                snapshot.primaryRecommendation,
                sensitivity.winnerAt(metric, snapshot.metric(metric)!!.weight)
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("flip points are reported nearest first")
    fun flipPointsAreReportedNearestFirst() {
        val snapshot = threeByTwo()
        val sensitivity = ModaSensitivity(snapshot)
        for (metric in sensitivity.metricNames) {
            val points = sensitivity.flipPointsForWinner(metric)
            assertEquals(points.sortedBy { it.margin }.map { it.margin }, points.map { it.margin })
            for (point in points) {
                assertEquals(metric, point.metric)
                assertTrue(point.displacedBy != snapshot.primaryRecommendation)
                assertTrue(point.criticalWeight in 0.0..1.0)
                assertEquals(abs(point.criticalWeight - point.currentWeight), point.margin, 1.0e-12)
            }
        }
    }

    @Test
    @DisplayName("the most critical weight is the nearest of all of them")
    fun theMostCriticalWeightIsTheNearestOfAllOfThem() {
        val sensitivity = ModaSensitivity(threeByTwo())
        val all = sensitivity.metricNames.flatMap { sensitivity.flipPointsForWinner(it) }
        val most = sensitivity.mostCriticalMetric()
        if (all.isEmpty()) {
            assertNull(most)
        } else {
            assertNotNull(most)
            assertEquals(all.minOf { it.margin }, most.margin, 1.0e-12)
        }
    }

    /**
     *  Crossing the critical weight has to actually change the recommendation, otherwise the number
     *  is arithmetic rather than a fact about the decision.
     */
    @Test
    @DisplayName("the recommendation really does change either side of a flip point")
    fun theRecommendationReallyDoesChangeEitherSideOfAFlipPoint() {
        val snapshot = threeByTwo()
        val sensitivity = ModaSensitivity(snapshot)
        val points = sensitivity.metricNames.flatMap { sensitivity.flipPointsForWinner(it) }
        assertTrue(points.isNotEmpty(), "the study under test has no flip point to check")
        for (point in points) {
            val nudge = 1.0e-6
            val below = (point.criticalWeight - nudge).coerceIn(0.0, 1.0)
            val above = (point.criticalWeight + nudge).coerceIn(0.0, 1.0)
            val valuesBelow = sensitivity.overallValuesAt(point.metric, below)
            val valuesAbove = sensitivity.overallValuesAt(point.metric, above)
            val winnerLeads = valuesBelow[snapshot.primaryRecommendation]!! - valuesBelow[point.displacedBy]!!
            val winnerTrails = valuesAbove[snapshot.primaryRecommendation]!! - valuesAbove[point.displacedBy]!!
            assertTrue(
                (winnerLeads > 0.0) != (winnerTrails > 0.0),
                "the pair do not change places around the reported weight for ${point.metric}"
            )
        }
    }

    @Test
    @DisplayName("a sweep covers the whole range including both ends")
    fun aSweepCoversTheWholeRangeIncludingBothEnds() {
        val sensitivity = ModaSensitivity(threeByTwo())
        val sweep = sensitivity.weightSweep("Cost", steps = 10)
        assertEquals(11, sweep.size, "a sweep of n steps should return n + 1 points")
        assertEquals(0.0, sweep.first().weight)
        assertEquals(1.0, sweep.last().weight)
        for (point in sweep) {
            assertEquals(sensitivity.winnerAt("Cost", point.weight), point.winner)
            assertTrue(point.overallValues.values.all { it.isFinite() })
        }
        assertFailsWith<IllegalArgumentException> { sensitivity.weightSweep("Cost", steps = 0) }
    }

    @Test
    @DisplayName("a sweep changes hands exactly where the flip points say it will")
    fun aSweepChangesHandsExactlyWhereTheFlipPointsSayItWill() {
        val snapshot = threeByTwo()
        val sensitivity = ModaSensitivity(snapshot)
        for (metric in sensitivity.metricNames) {
            val sweep = sensitivity.weightSweep(metric, steps = 2000)
            val handovers = sweep.zipWithNext().count { (before, after) -> before.winner != after.winner }
            val distinctFlips = sensitivity.flipPointsForWinner(metric)
                .map { it.criticalWeight }.distinct().size
            assertTrue(
                handovers <= distinctFlips + 1,
                "the sweep for $metric changes hands $handovers times but only $distinctFlips flip points were reported"
            )
        }
    }
}
