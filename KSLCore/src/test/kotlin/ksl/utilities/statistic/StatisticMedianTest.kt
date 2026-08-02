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

package ksl.utilities.statistic

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.NormalRV
import org.junit.jupiter.api.DisplayName
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  `Statistic.median` for odd-sized samples.
 *
 *  The odd branch indexed the sorted array at ceil(size / 2.0), which is one position above the
 *  middle for every odd size: a three-element sample returned its largest value and a five-element
 *  sample its fourth smallest. The even branch, which averages the two middle observations, was
 *  always right, and a single observation was returned before the branch was reached, so the two
 *  cases most likely to be checked by hand were the two that worked.
 *
 *  The error was systematic rather than random -- always the next observation up -- so it biased
 *  every estimate built on it in one direction. That reaches the Laplace and logistic location
 *  parameters, which are the sample median by definition, the bootstrap median estimator, and the
 *  median line of every box plot.
 */
class StatisticMedianTest {

    /**
     *  A private provider, so the sample does not depend on how much of stream one the rest of the
     *  suite has already consumed. A stream taken by number alone would make these values move with
     *  the order the suite happens to run in.
     */
    private fun normalSample(size: Int): DoubleArray =
        NormalRV(mean = 0.0, variance = 1.0, streamNum = 1, streamProvider = RNStreamProvider())
            .sample(size)

    @Test
    @DisplayName("An odd-sized sample returns its middle observation")
    fun oddSizedSampleReturnsTheMiddleObservation() {
        assertEquals(2.0, Statistic.median(doubleArrayOf(1.0, 2.0, 3.0)))
        assertEquals(3.0, Statistic.median(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 100.0)))
        assertEquals(4.0, Statistic.median(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)))
        assertEquals(
            50.0,
            Statistic.median(doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0)),
        )
    }

    @Test
    @DisplayName("An outlier moves the mean but not the median")
    fun anOutlierDoesNotMoveTheMedian() {
        // The point of a median. Before the fix the large value pulled the reported median up to
        // 4.0, which is the behaviour a median exists to avoid.
        //
        // Both values are asserted, not merely their equality: the old code returned 4.0 for both
        // arrays, so an equality-only check passed while both answers were wrong.
        val withOutlier = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 100.0)
        val withoutOutlier = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, Statistic.median(withoutOutlier))
        assertEquals(3.0, Statistic.median(withOutlier))
    }

    @Test
    @DisplayName("An even-sized sample averages the two middle observations")
    fun evenSizedSampleAveragesTheTwoMiddleObservations() {
        assertEquals(1.5, Statistic.median(doubleArrayOf(1.0, 2.0)))
        assertEquals(2.5, Statistic.median(doubleArrayOf(1.0, 2.0, 3.0, 4.0)))
        assertEquals(3.5, Statistic.median(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)))
    }

    @Test
    @DisplayName("A single observation is its own median, and empty data is rejected")
    fun singleObservationAndEmptyData() {
        assertEquals(42.0, Statistic.median(doubleArrayOf(42.0)))
        assertFailsWith<IllegalArgumentException> { Statistic.median(doubleArrayOf()) }
    }

    @Test
    @DisplayName("Unsorted input gives the same answer as sorted input")
    fun unsortedInputGivesTheSameAnswer() {
        assertEquals(3.0, Statistic.median(doubleArrayOf(5.0, 1.0, 4.0, 2.0, 3.0)))
        assertEquals(2.5, Statistic.median(doubleArrayOf(4.0, 1.0, 3.0, 2.0)))
    }

    @Test
    @DisplayName("The median agrees with quantile and percentile at one half, at every size")
    fun medianAgreesWithTheQuantileFunctionsAtOneHalf() {
        // Three routes to the same number, and before the fix one of them disagreed with the other
        // two. Pinning the agreement is what stops them drifting apart again: an implementation of
        // the median that does not match the library's own half-quantile is wrong by construction,
        // whichever one moved.
        for (size in 1..40) {
            val sample = normalSample(size)
            val median = Statistic.median(sample.copyOf())
            val quantile = Statistic.quantile(sample.copyOf(), 0.5)
            val percentile = Statistic.percentile(sample.copyOf(), 0.5)
            assertTrue(
                abs(median - quantile) <= 1.0e-12,
                "size $size: median $median disagreed with quantile(0.5) $quantile",
            )
            assertTrue(
                abs(median - percentile) <= 1.0e-12,
                "size $size: median $median disagreed with percentile(0.5) $percentile",
            )
        }
    }

    @Test
    @DisplayName("Half the observations lie on each side of the median")
    fun theMedianSplitsTheSample() {
        // The defining property, checked without reference to any other function in the library.
        for (size in 1..40) {
            val sample = normalSample(size)
            val median = Statistic.median(sample.copyOf())
            val below = sample.count { it < median }
            val above = sample.count { it > median }
            assertTrue(
                below <= size / 2 && above <= size / 2,
                "size $size: $below below and $above above the median $median",
            )
        }
    }

    @Test
    @DisplayName("A box plot's median lies between its own quartiles")
    fun boxPlotSummaryIsInternallyConsistent() {
        // BoxPlotSummary takes its quartiles from percentile() and its median from Statistic.median,
        // so the two disagreed: on the sample below it reported a median of 4.0 against a first
        // quartile of 1.5 and a third of 52.0, placing the median line where it did not belong.
        for (size in 3..40) {
            val sample = normalSample(size)
            val summary = BoxPlotSummary(sample.copyOf())
            assertTrue(
                summary.firstQuartile <= summary.median && summary.median <= summary.thirdQuartile,
                "size $size: median ${summary.median} outside quartiles " +
                        "[${summary.firstQuartile}, ${summary.thirdQuartile}]",
            )
        }
        val reported = BoxPlotSummary(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 100.0))
        assertEquals(3.0, reported.median, "the reported case still gives the wrong median line")
    }
}
