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

package ksl.app.moda

import ksl.utilities.io.dbutil.WithinRepViewData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

/**
 *  Tests for reading scores out of simulation replications.
 *
 *  A simulated alternative has one observation per replication rather than one value, so comparing
 *  alternatives means reducing each series to a single score. What is checked here is that the
 *  reduction is the one asked for, that gaps are reported rather than filled in, and that
 *  observations are matched up by replication number rather than by position.
 */
class SimulationModaSourceTest {

    private fun record(
        alternative: String,
        response: String,
        replication: Int,
        value: Double?,
        replications: Int = 5
    ) = WithinRepViewData(
        exp_name = alternative,
        run_name = "run",
        num_reps = replications,
        start_rep_id = 1,
        last_rep_id = replications,
        stat_name = response,
        rep_id = replication,
        rep_value = value
    )

    /** Cost rises across alternatives and Delay falls, so neither dominates. */
    private fun data(replications: Int = 5): List<WithinRepViewData> {
        val list = mutableListOf<WithinRepViewData>()
        for ((index, alternative) in listOf("Alpha", "Beta", "Gamma").withIndex()) {
            for (replication in 1..replications) {
                list.add(record(alternative, "Cost", replication, 10.0 + 2.0 * index + 0.1 * replication, replications))
                list.add(record(alternative, "Delay", replication, 9.0 - 1.5 * index + 0.1 * replication, replications))
            }
        }
        return list
    }

    // ------------------------------------------------------------------------------------------
    // Reducing a series to a score
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("averaging is what happens unless something else is asked for")
    fun averagingIsWhatHappensUnlessSomethingElseIsAskedFor() {
        val source = SimulationModaSource(data())
        assertEquals(ReplicationAggregation.MEAN, source.aggregation)
        val table = source.scores(setOf("Alpha"), setOf("Cost"))
        // 10.1 through 10.5.
        assertEquals(10.3, table.values["Alpha"]!!["Cost"]!!, 1.0e-12)
    }

    @Test
    @DisplayName("each way of reducing a series gives what it says")
    fun eachWayOfReducingASeriesGivesWhatItSays() {
        val observations = listOf(1.0, 2.0, 3.0, 4.0, 100.0)
        val records = observations.mapIndexed { index, value ->
            record("Alpha", "Cost", index + 1, value, observations.size)
        }
        assertEquals(
            22.0,
            SimulationModaSource(records, ReplicationAggregation.MEAN)
                .scores(setOf("Alpha"), setOf("Cost")).values["Alpha"]!!["Cost"]!!,
            1.0e-12
        )
        assertEquals(
            3.0,
            SimulationModaSource(records, ReplicationAggregation.MEDIAN)
                .scores(setOf("Alpha"), setOf("Cost")).values["Alpha"]!!["Cost"]!!,
            1.0e-12,
            "the middle observation should not be pulled about by the extreme one"
        )
        assertEquals(
            100.0,
            SimulationModaSource(records, ReplicationAggregation.LAST)
                .scores(setOf("Alpha"), setOf("Cost")).values["Alpha"]!!["Cost"]!!,
            1.0e-12
        )
        val ninetieth = SimulationModaSource(records, ReplicationAggregation.PERCENTILE, 0.9)
            .scores(setOf("Alpha"), setOf("Cost")).values["Alpha"]!!["Cost"]!!
        assertTrue(ninetieth > 3.0, "the ninetieth percentile should sit above the middle: $ninetieth")
    }

    /**
     *  Asking for the middle observation and asking for the fiftieth percentile are the same
     *  question, so they must not give different answers.
     */
    @Test
    @DisplayName("the median is the fiftieth percentile")
    fun theMedianIsTheFiftiethPercentile() {
        for (observations in listOf(
            listOf(1.0, 2.0, 3.0, 4.0, 100.0),
            listOf(5.0, 1.0, 4.0, 2.0),
            listOf(7.0, 7.0, 7.0),
            listOf(2.0)
        )) {
            val records = observations.mapIndexed { index, value ->
                record("Alpha", "Cost", index + 1, value, observations.size)
            }
            val median = SimulationModaSource(records, ReplicationAggregation.MEDIAN)
                .scores(setOf("Alpha"), setOf("Cost")).values["Alpha"]!!["Cost"]!!
            val fiftieth = SimulationModaSource(records, ReplicationAggregation.PERCENTILE, 0.5)
                .scores(setOf("Alpha"), setOf("Cost")).values["Alpha"]!!["Cost"]!!
            assertEquals(fiftieth, median, 1.0e-12, "they disagree for $observations")
            val sorted = observations.sorted()
            assertTrue(
                median >= sorted.first() && median <= sorted.last(),
                "the middle observation fell outside the observations for $observations"
            )
        }
    }

    @Test
    @DisplayName("a percentile outside the range a percentile can take is refused")
    fun aPercentileOutsideTheRangeAPercentileCanTakeIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            SimulationModaSource(data(), ReplicationAggregation.PERCENTILE, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulationModaSource(data(), ReplicationAggregation.PERCENTILE, 1.0)
        }
    }

    // ------------------------------------------------------------------------------------------
    // The individual observations
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the observations come back in replication order")
    fun theObservationsComeBackInReplicationOrder() {
        val source = SimulationModaSource(data())
        assertContentEquals(listOf(1, 2, 3, 4, 5), source.replicationIds("Alpha"))
        val series = source.replicationScores("Alpha", "Cost")!!
        assertEquals(5, series.size)
        assertEquals(10.1, series.first(), 1.0e-12)
        assertEquals(10.5, series.last(), 1.0e-12)
    }

    @Test
    @DisplayName("asking about something that was never observed gives nothing rather than an empty answer")
    fun askingAboutSomethingThatWasNeverObservedGivesNothingRatherThanAnEmptyAnswer() {
        val source = SimulationModaSource(data())
        assertNull(source.replicationScores("Nope", "Cost"))
        assertNull(source.replicationScores("Alpha", "Nope"))
        assertTrue(source.replicationIds("Nope").isEmpty())
    }

    /**
     *  Replications are matched by number, so alternatives run over different replication numbers
     *  share none, even where they have the same count.
     */
    @Test
    @DisplayName("the replications alternatives share are found by number and not by position")
    fun theReplicationsAlternativesShareAreFoundByNumberAndNotByPosition() {
        val records = listOf(
            record("Alpha", "Cost", 1, 1.0), record("Alpha", "Cost", 2, 2.0), record("Alpha", "Cost", 3, 3.0),
            record("Beta", "Cost", 2, 4.0), record("Beta", "Cost", 3, 5.0), record("Beta", "Cost", 4, 6.0)
        )
        val source = SimulationModaSource(records)
        assertContentEquals(listOf(2, 3), source.commonReplicationIds(listOf("Alpha", "Beta")))

        val disjoint = listOf(
            record("Alpha", "Cost", 1, 1.0), record("Alpha", "Cost", 2, 2.0),
            record("Beta", "Cost", 8, 4.0), record("Beta", "Cost", 9, 5.0)
        )
        assertTrue(
            SimulationModaSource(disjoint).commonReplicationIds(listOf("Alpha", "Beta")).isEmpty(),
            "alternatives run over different replication numbers should share none"
        )
    }

    @Test
    @DisplayName("a replication recorded twice does not lengthen the series")
    fun aReplicationRecordedTwiceDoesNotLengthenTheSeries() {
        val records = listOf(
            record("Alpha", "Cost", 1, 1.0),
            record("Alpha", "Cost", 1, 99.0),
            record("Alpha", "Cost", 2, 2.0)
        )
        val source = SimulationModaSource(records)
        assertContentEquals(listOf(1, 2), source.replicationIds("Alpha"))
        assertContentEquals(doubleArrayOf(1.0, 2.0).toList(), source.replicationScores("Alpha", "Cost")!!.toList())
    }

    // ------------------------------------------------------------------------------------------
    // Gaps
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a response that was never recorded is reported apart from a score that is merely absent")
    fun aResponseThatWasNeverRecordedIsReportedApartFromAScoreThatIsMerelyAbsent() {
        val records = data().filterNot { it.exp_name == "Gamma" && it.stat_name == "Delay" }
        val source = SimulationModaSource(records)
        val table = source.scores(setOf("Alpha", "Beta", "Gamma"), setOf("Cost", "Delay"))

        assertEquals(listOf(MissingScore("Gamma", "Delay")), table.missing)
        assertEquals(listOf(EmptySeries("Gamma", "Delay")), source.emptySeries)
        assertTrue("Gamma" !in table.values, "an alternative missing a response was still scored")
        assertTrue("Alpha" in table.values)
    }

    @Test
    @DisplayName("replications that recorded nothing are left out of the average")
    fun replicationsThatRecordedNothingAreLeftOutOfTheAverage() {
        val records = listOf(
            record("Alpha", "Cost", 1, 10.0),
            record("Alpha", "Cost", 2, null),
            record("Alpha", "Cost", 3, 20.0)
        )
        val source = SimulationModaSource(records)
        assertEquals(2, source.replicationScores("Alpha", "Cost")!!.size)
        assertEquals(15.0, source.scores(setOf("Alpha"), setOf("Cost")).values["Alpha"]!!["Cost"]!!, 1.0e-12)
    }

    // ------------------------------------------------------------------------------------------
    // Replication counts
    // ------------------------------------------------------------------------------------------

    /**
     *  Aggregating does not need the counts to match: each alternative is summarised over whatever
     *  it has. Comparing replication by replication does, and that is enforced where it applies.
     */
    @Test
    @DisplayName("alternatives observed different numbers of times are still aggregated")
    fun alternativesObservedDifferentNumbersOfTimesAreStillAggregated() {
        val records = data() + listOf(
            record("Alpha", "Cost", 6, 11.0, 6), record("Alpha", "Delay", 6, 8.0, 6)
        )
        val source = SimulationModaSource(records)
        assertTrue(!source.hasEqualReplicationCounts(listOf("Alpha", "Beta")))
        assertEquals(mapOf("Alpha" to 6, "Beta" to 5), source.replicationCounts(listOf("Alpha", "Beta")))

        val table = source.scores(setOf("Alpha", "Beta"), setOf("Cost", "Delay"))
        assertTrue(table.isComplete, "aggregating should not require the counts to match")
        assertEquals(2, table.values.size)
    }

    @Test
    @DisplayName("the ranges come out the same way the analyzer works them out")
    fun theRangesComeOutTheSameWayTheAnalyzerWorksThemOut() {
        val records = data()
        val fromSource = SimulationModaSource.recommendedDomains(setOf("Cost", "Delay"), records)
        val fromAnalyzer = ksl.utilities.moda.MODAAnalyzer
            .recommendMetricDomainIntervals(setOf("Cost", "Delay"), records)
        assertEquals(fromAnalyzer, fromSource)
    }

    /**
     *  A limit someone stated is a fact about the response and outranks anything inferred from a
     *  handful of runs; a limit left open is exactly what the runs can speak to.
     */
    @Test
    @DisplayName("limits that were stated are kept and only open ones are filled in")
    fun limitsThatWereStatedAreKeptAndOnlyOpenOnesAreFilledIn() {
        val records = data()
        val specs = SimulationModaSource.metricSpecsFor(
            mapOf("Cost" to 1.0),
            records,
            declaredDomains = mapOf("Cost" to ksl.utilities.Interval(0.0, 42.0))
        )
        assertEquals(0.0, specs.single().lowerLimit)
        assertEquals(42.0, specs.single().upperLimit, "a stated limit was overwritten by an inferred one")

        val open = SimulationModaSource.metricSpecsFor(mapOf("Cost" to 1.0), records)
        assertEquals(0.0, open.single().lowerLimit, "the default floor of zero was not kept")
        assertTrue(open.single().upperLimit < Double.MAX_VALUE, "the open limit was not filled in")
    }

    @Test
    @DisplayName("a study built over responses holds its ranges rather than fitting them again")
    fun aStudyBuiltOverResponsesHoldsItsRangesRatherThanFittingThemAgain() {
        val document = SimulationModaSource.documentFor(
            "Sim study", listOf("Alpha", "Beta", "Gamma"), mapOf("Cost" to 1.0, "Delay" to 1.0), data()
        )
        assertEquals(RescalePolicy.NONE, document.rescalePolicy)
        assertTrue(document.metrics.none { it.allowUpperLimitAdjustment || it.allowLowerLimitAdjustment })
    }
}
