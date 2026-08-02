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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

/**
 *  Tests for how a model reports what it accepted when metrics and alternatives are defined.
 *
 *  Metrics are held by identity rather than by name, so scoring an alternative against a metric
 *  that was built a second time somewhere leaves that alternative out of the model entirely. It
 *  used to be left out in silence, which is hard to diagnose precisely because the metric name
 *  looks right in every report. These tests pin what is now reported instead.
 */
class ModaDefinitionTest {

    private fun modelWithTwoMetrics(): Triple<AdditiveMODAModel, Metric, Metric> {
        val cost = Metric("Cost", Interval(0.0, 100.0))
        val delay = Metric("Delay", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(
            mapOf(cost to LinearValueFunction(), delay to LinearValueFunction())
        )
        return Triple(model, cost, delay)
    }

    // ------------------------------------------------------------------------------------------
    // Alternatives that cannot be taken in are reported
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an alternative scored on a separately created metric is reported by name")
    fun anAlternativeScoredOnASeparatelyCreatedMetricIsReportedByName() {
        val (model, cost, delay) = modelWithTwoMetrics()
        // The same name, built separately. This is the mistake that used to pass unnoticed.
        val impostor = Metric("Cost", Interval(0.0, 100.0))
        val result = model.defineAlternativesReporting(
            mapOf(
                "Good" to listOf(Score(cost, 20.0), Score(delay, 30.0)),
                "Bad" to listOf(Score(impostor, 20.0), Score(delay, 30.0))
            )
        )
        assertContentEquals(listOf("Good"), result.accepted)
        val rejection = result.rejected.singleOrNull() as? AlternativeRejection.UnknownMetric
        assertNotNull(rejection, "the alternative was left out without saying why")
        assertEquals("Bad", rejection.alternative)
        assertEquals("Cost", rejection.metricName, "the rejection did not name the metric involved")
        assertTrue(rejection.message.contains("identity"), "the message does not explain the cause")
    }

    @Test
    @DisplayName("an alternative with the wrong number of scores is reported with both counts")
    fun anAlternativeWithTheWrongNumberOfScoresIsReportedWithBothCounts() {
        val (model, cost, _) = modelWithTwoMetrics()
        val result = model.defineAlternativesReporting(
            mapOf("Short" to listOf(Score(cost, 20.0)))
        )
        assertTrue(result.accepted.isEmpty())
        val rejection = result.rejected.singleOrNull() as? AlternativeRejection.WrongScoreCount
        assertNotNull(rejection, "the alternative was left out without saying why")
        assertEquals(2, rejection.expected)
        assertEquals(1, rejection.actual)
    }

    /**
     *  The right number of known metrics can still leave one of them unscored, if another was
     *  scored twice. That used to be taken in, leaving the model holding an alternative with no
     *  score for one of its metrics, which failed later and away from the cause.
     */
    @Test
    @DisplayName("an alternative that scores one metric twice and another not at all is reported")
    fun anAlternativeThatScoresOneMetricTwiceAndAnotherNotAtAllIsReported() {
        val (model, cost, _) = modelWithTwoMetrics()
        val result = model.defineAlternativesReporting(
            mapOf("Doubled" to listOf(Score(cost, 20.0), Score(cost, 40.0)))
        )
        assertTrue(result.accepted.isEmpty())
        val rejection = result.rejected.singleOrNull() as? AlternativeRejection.MissingScore
        assertNotNull(rejection, "the alternative was taken in with a metric left unscored")
        assertEquals("Delay", rejection.metricName)
    }

    @Test
    @DisplayName("taking in every alternative is reported as such")
    fun takingInEveryAlternativeIsReportedAsSuch() {
        val (model, cost, delay) = modelWithTwoMetrics()
        val result = model.defineAlternativesReporting(
            mapOf(
                "A" to listOf(Score(cost, 20.0), Score(delay, 30.0)),
                "B" to listOf(Score(cost, 60.0), Score(delay, 70.0))
            )
        )
        assertTrue(result.allAccepted)
        assertContentEquals(listOf("A", "B"), result.accepted)
        assertTrue(result.rejectionMessages().isEmpty())
    }

    /**
     *  The function that does not report keeps behaving as it did, so existing callers are
     *  unaffected by the reporting one being added.
     */
    @Test
    @DisplayName("defining alternatives without asking for a report still skips the ones it cannot take")
    fun definingAlternativesWithoutAskingForAReportStillSkipsTheOnesItCannotTake() {
        val (model, cost, delay) = modelWithTwoMetrics()
        val impostor = Metric("Cost", Interval(0.0, 100.0))
        model.defineAlternatives(
            mapOf(
                "Good" to listOf(Score(cost, 20.0), Score(delay, 30.0)),
                "Bad" to listOf(Score(impostor, 20.0), Score(delay, 30.0))
            )
        )
        assertContentEquals(listOf("Good"), model.alternatives)
    }

    @Test
    @DisplayName("defining alternatives before any metric is an error")
    fun definingAlternativesBeforeAnyMetricIsAnError() {
        val cost = Metric("Cost", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(cost to LinearValueFunction()))
        model.defineMetrics(emptyMap())
        assertFailsWith<IllegalStateException> {
            model.defineAlternativesReporting(mapOf("A" to listOf(Score(cost, 20.0))))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Metric names have to be distinct
    // ------------------------------------------------------------------------------------------

    /**
     *  Two separately created metrics sharing a name are two metrics in the model but one name in
     *  every report, data frame and database row it produces, so the results cannot be read. This
     *  used to be accepted silently.
     */
    @Test
    @DisplayName("two metrics with the same name are refused")
    fun twoMetricsWithTheSameNameAreRefused() {
        val first = Metric("Cost", Interval(0.0, 100.0))
        val second = Metric("Cost", Interval(0.0, 50.0))
        val error = assertFailsWith<IllegalArgumentException> {
            AdditiveMODAModel(mapOf(first to LinearValueFunction(), second to LinearValueFunction()))
        }
        assertTrue(error.message!!.contains("Cost"), "the error does not name the duplicated metric")
    }

    @Test
    @DisplayName("metrics with distinct names are accepted")
    fun metricsWithDistinctNamesAreAccepted() {
        val (model, _, _) = modelWithTwoMetrics()
        assertEquals(listOf("Cost", "Delay"), model.metrics.map { it.name })
    }

    // ------------------------------------------------------------------------------------------
    // Surrogate keys
    // ------------------------------------------------------------------------------------------

    /**
     *  Records built at the same time from different threads must not be given the same key. The
     *  counters behind these keys used to be plain mutable fields, so two threads could read the
     *  same value before either had written the next one.
     */
    @Test
    @DisplayName("records built from many threads at once get distinct keys")
    fun recordsBuiltFromManyThreadsAtOnceGetDistinctKeys() {
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = List(threads) {
                Callable { List(perThread) { ScoreData().id } }
            }
            val ids = pool.invokeAll(tasks).flatMap { it.get() }
            assertEquals(threads * perThread, ids.size)
            assertEquals(ids.size, ids.toSet().size, "the same key was handed out more than once")
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("each kind of record counts its keys separately")
    fun eachKindOfRecordCountsItsKeysSeparately() {
        // Distinctness is per table, since each is keyed independently.
        val scores = List(3) { ScoreData().id }
        val values = List(3) { ValueData().id }
        assertEquals(3, scores.toSet().size)
        assertEquals(3, values.toSet().size)
    }
}
