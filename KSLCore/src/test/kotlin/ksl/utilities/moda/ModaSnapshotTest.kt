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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

/**
 *  Tests for the recorded result of a study.
 *
 *  A model is live: defining more alternatives refits its domains and changes every value it
 *  reports. A snapshot is meant to be the opposite, so most of what is checked here is that it
 *  stands on its own and does not change afterwards, and that anything asked of it twice answers
 *  the same way.
 */
class ModaSnapshotTest {

    private fun studyModel(): Triple<AdditiveMODAModel, Metric, Metric> {
        // Declared wider than the scores reach, so fitting genuinely narrows rather than arriving
        // back at the declared limits, which is what makes the fitted-domain assertions meaningful.
        val cost = Metric("Cost", Interval(0.0, 1000.0))
        val delay = Metric("Delay", Interval(0.0, 1000.0))
        val model = AdditiveMODAModel(
            mapOf(cost to LinearValueFunction(), delay to LinearValueFunction()),
            name = "Study"
        )
        model.defineAlternatives(
            mapOf(
                "Alpha" to listOf(Score(cost, 20.0), Score(delay, 80.0)),
                "Beta" to listOf(Score(cost, 50.0), Score(delay, 50.0)),
                "Gamma" to listOf(Score(cost, 80.0), Score(delay, 20.0))
            )
        )
        return Triple(model, cost, delay)
    }

    // ------------------------------------------------------------------------------------------
    // Standing on its own
    // ------------------------------------------------------------------------------------------

    /**
     *  The point of taking a snapshot is that it survives the model moving on. Redefining the
     *  alternatives refits the domains and changes everything the model reports, and none of that
     *  may reach a snapshot already taken.
     */
    @Test
    @DisplayName("a snapshot does not change when the model it came from does")
    fun aSnapshotDoesNotChangeWhenTheModelItCameFromDoes() {
        val (model, cost, delay) = studyModel()
        val snapshot = ModaSnapshot.of(model)
        val before = snapshot.copy()

        model.defineAlternatives(
            mapOf(
                "Delta" to listOf(Score(cost, 5.0), Score(delay, 95.0)),
                "Epsilon" to listOf(Score(cost, 95.0), Score(delay, 5.0))
            )
        )
        assertEquals(before, snapshot, "the snapshot changed when the model was redefined")
        assertContentEquals(listOf("Alpha", "Beta", "Gamma"), snapshot.alternatives)
    }

    @Test
    @DisplayName("a snapshot survives the alternatives being cleared")
    fun aSnapshotSurvivesTheAlternativesBeingCleared() {
        val (model, _, _) = studyModel()
        val snapshot = ModaSnapshot.of(model)
        model.clearAlternatives()
        assertContentEquals(listOf("Alpha", "Beta", "Gamma"), snapshot.alternatives)
        assertEquals(3, snapshot.overallValues.size)
    }

    // ------------------------------------------------------------------------------------------
    // What it records
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("metrics and alternatives keep the order the study declared them in")
    fun metricsAndAlternativesKeepTheOrderTheStudyDeclaredThemIn() {
        val (model, _, _) = studyModel()
        val snapshot = ModaSnapshot.of(model)
        assertContentEquals(listOf("Cost", "Delay"), snapshot.metrics.map { it.name })
        assertContentEquals(listOf("Alpha", "Beta", "Gamma"), snapshot.alternatives)
    }

    @Test
    @DisplayName("every recorded value is a real number")
    fun everyRecordedValueIsARealNumber() {
        val (model, _, _) = studyModel()
        val snapshot = ModaSnapshot.of(model)
        for (alternative in snapshot.alternatives) {
            assertTrue(snapshot.overallValues[alternative]!!.isFinite(), "overall value not finite")
            assertTrue(snapshot.averageRankings[alternative]!!.isFinite(), "average ranking not finite")
            for (metric in snapshot.metrics) {
                assertTrue(snapshot.scores[alternative]!![metric.name]!!.isFinite(), "score not finite")
                val value = snapshot.values[alternative]!![metric.name]!!
                assertTrue(value.isFinite() && value in 0.0..1.0, "value $value out of contract")
            }
        }
    }

    @Test
    @DisplayName("both domains are recorded so the values can be explained")
    fun bothDomainsAreRecordedSoTheValuesCanBeExplained() {
        val (model, cost, _) = studyModel()
        val snapshot = ModaSnapshot.of(model)
        val record = snapshot.metric("Cost")!!
        assertEquals(0.0, record.declaredLowerLimit)
        assertEquals(1000.0, record.declaredUpperLimit)
        assertTrue(record.domainWasRescaled, "the domain was fitted but not recorded as such")
        assertEquals(model.effectiveDomainOf(cost).lowerLimit, record.effectiveLowerLimit)
        assertEquals(model.effectiveDomainOf(cost).upperLimit, record.effectiveUpperLimit)
        assertFalse(record.hadTiedScores)
        assertEquals("LinearValueFunction", record.valueFunctionId)
    }

    @Test
    @DisplayName("a metric everything ties on is recorded as such along with the reason")
    fun aMetricEverythingTiesOnIsRecordedAsSuchAlongWithTheReason() {
        val tied = Metric("Tied", Interval(0.0, 100.0))
        val deciding = Metric("Deciding", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(
            mapOf(tied to LinearValueFunction(), deciding to LinearValueFunction()), name = "Tied study"
        )
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(tied, 5.0), Score(deciding, 10.0)),
                "B" to listOf(Score(tied, 5.0), Score(deciding, 90.0))
            )
        )
        val snapshot = ModaSnapshot.of(model)
        assertTrue(snapshot.metric("Tied")!!.hadTiedScores)
        assertFalse(snapshot.metric("Deciding")!!.hadTiedScores)
        assertTrue(snapshot.hasTiedMetric)
        assertTrue(
            snapshot.warnings.any { it.contains("Tied") },
            "the snapshot does not carry the reason the metric was flagged"
        )
    }

    @Test
    @DisplayName("the weights are recorded against the metrics they belong to")
    fun theWeightsAreRecordedAgainstTheMetricsTheyBelongTo() {
        val cost = Metric("Cost", Interval(0.0, 100.0))
        val delay = Metric("Delay", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(
            mapOf(cost to LinearValueFunction(), delay to LinearValueFunction()),
            weights = mapOf(cost to 3.0, delay to 1.0)
        )
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(cost, 20.0), Score(delay, 80.0)),
                "B" to listOf(Score(cost, 80.0), Score(delay, 20.0))
            )
        )
        val snapshot = ModaSnapshot.of(model)
        assertEquals(0.75, snapshot.metric("Cost")!!.weight, 1.0e-12)
        assertEquals(0.25, snapshot.metric("Delay")!!.weight, 1.0e-12)
    }

    // ------------------------------------------------------------------------------------------
    // Arriving at a recommendation
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the recommendation is the best alternative by weighted value")
    fun theRecommendationIsTheBestAlternativeByWeightedValue() {
        val (model, _, _) = studyModel()
        val snapshot = ModaSnapshot.of(model)
        val best = snapshot.overallValues.maxByOrNull { it.value }!!.key
        assertEquals(best, snapshot.primaryRecommendation)
        assertEquals(best, snapshot.recommendationOrder.first())
    }

    @Test
    @DisplayName("counting first ranks can point somewhere else than the weighted value does")
    fun countingFirstRanksCanPointSomewhereElseThanTheWeightedValueDoes() {
        val (model, _, _) = studyModel()
        val byValue = ModaSnapshot.of(model, aggregation = AggregationMethod.WEIGHTED_VALUE)
        val byRank = ModaSnapshot.of(model, aggregation = AggregationMethod.FIRST_RANK_COUNT)
        assertEquals(AggregationMethod.WEIGHTED_VALUE, byValue.aggregationMethod)
        assertEquals(AggregationMethod.FIRST_RANK_COUNT, byRank.aggregationMethod)
        // Both must name an alternative that is actually in the study, whether or not they agree.
        assertTrue(byRank.primaryRecommendation in byRank.alternatives)
        assertEquals(
            byRank.firstRankCounts.maxOf { it.value },
            byRank.firstRankCounts[byRank.primaryRecommendation],
            "the recommendation is not among those ranked first most often"
        )
    }

    /**
     *  Alternatives that tie exactly are resolved by name. Something has to be recommended, and
     *  choosing by name means the same study recommends the same one every time rather than
     *  depending on the order a map happened to yield.
     */
    @Test
    @DisplayName("alternatives that tie exactly are resolved the same way every time")
    fun alternativesThatTieExactlyAreResolvedTheSameWayEveryTime() {
        val metric = Metric("Only", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()), name = "Ties")
        model.defineAlternatives(
            mapOf(
                "Zeta" to listOf(Score(metric, 40.0)),
                "Alpha" to listOf(Score(metric, 40.0)),
                "Mu" to listOf(Score(metric, 40.0))
            )
        )
        repeat(5) {
            val snapshot = ModaSnapshot.of(model)
            assertEquals("Alpha", snapshot.primaryRecommendation)
            assertContentEquals(listOf("Alpha", "Mu", "Zeta"), snapshot.recommendationOrder)
        }
    }

    @Test
    @DisplayName("taking the same snapshot twice gives the same result")
    fun takingTheSameSnapshotTwiceGivesTheSameResult() {
        val (model, _, _) = studyModel()
        assertEquals(ModaSnapshot.of(model), ModaSnapshot.of(model))
    }

    @Test
    @DisplayName("a model with no alternatives cannot be recorded and says why")
    fun aModelWithNoAlternativesCannotBeRecordedAndSaysWhy() {
        val metric = Metric("Cost", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        val error = assertFailsWith<IllegalArgumentException> { ModaSnapshot.of(model) }
        assertTrue(
            error.message!!.contains("defineAlternativesReporting"),
            "the error does not point at how to find out why there are no alternatives"
        )
    }
}
