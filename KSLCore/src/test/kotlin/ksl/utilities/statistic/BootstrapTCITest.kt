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
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ksl.utilities.statistic

import ksl.utilities.random.rng.RNStreamProvider
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Gives a test the t values and the resamples they were computed from. */
private class VisibleBootstrap(data: DoubleArray, streamNumber: Int) :
    Bootstrap(data, BSEstimatorIfc.Average(), streamNumber, RNStreamProvider()) {
    val tValues: DoubleArray get() = myStudentizedTValues.savedData()
    val resamples: List<DoubleArray> get() = myBSArrayList.map { it.savedData() }
}

@DisplayName("Bootstrap-t confidence interval")
class BootstrapTCITest {

    private val sample = doubleArrayOf(6.0, 7.0, 5.0, 1.0, 0.0, 4.0, 6.0, 0.0, 6.0, 1.0)

    @Test
    @DisplayName("agrees with an independent computation in R on the same resamples")
    fun agreesWithR() {
        val bs = VisibleBootstrap(sample, 3)
        bs.generateSamples(400, numBootstrapTSamples = 399)
        val interval = bs.bootstrapTCI(0.95)
        assertEquals(1.305027, interval.lowerLimit, 1.0e-5)
        assertEquals(5.231439, interval.upperLimit, 1.0e-5)
    }

    @Test
    @DisplayName("the t values divide by the standard error of the bootstrap sample")
    fun tValuesUseTheStandardError() {
        val bs = VisibleBootstrap(sample, 4)
        bs.generateSamples(200, saveBootstrapSamples = true, numBootstrapTSamples = 2)
        val expected = bs.resamples.map { resample ->
            val stats = Statistic(resample)
            (stats.average - bs.originalDataEstimate) / (stats.standardDeviation / sqrt(resample.size.toDouble()))
        }
        assertEquals(expected.size, bs.tValues.size)
        for (i in expected.indices) {
            assertEquals(expected[i], bs.tValues[i], 1.0e-10)
        }
    }

    @Test
    @DisplayName("the multiplier stays the standard deviation of the bootstrap estimates")
    fun multiplierIsTheStandardDeviationOfTheEstimates() {
        val bs = VisibleBootstrap(sample, 5)
        bs.generateSamples(200, numBootstrapTSamples = 2)
        assertEquals(Statistic(bs.bootstrapEstimates).standardDeviation, bs.bootstrapStdErrEstimate, 1.0e-10)
    }

    @Test
    @DisplayName("a bootstrap sample with no spread is left out, and the interval stays finite")
    fun sampleWithNoSpreadIsLeftOut() {
        val bs = VisibleBootstrap(doubleArrayOf(1.0, 2.0, 4.0), 6)
        bs.generateSamples(500, saveBootstrapSamples = true, numBootstrapTSamples = 2)
        val noSpread = bs.resamples.count { resample -> resample.toSet().size == 1 }
        assertTrue(noSpread > 0, "the test needs at least one sample with no spread")
        assertEquals(500 - noSpread, bs.tValues.size)
        assertTrue(bs.tValues.all { it.isFinite() })
        val interval = bs.bootstrapTCI(0.95)
        assertTrue(interval.lowerLimit.isFinite() && interval.upperLimit.isFinite())
    }

    @Test
    @DisplayName("the average's interval is about as wide as the t interval")
    fun intervalWidthIsPlausible() {
        val bs = VisibleBootstrap(sample, 3)
        bs.generateSamples(400, numBootstrapTSamples = 399)
        val width = bs.bootstrapTCI(0.95).width
        val tWidth = Statistic(sample).confidenceInterval(0.95).width
        assertTrue(width > 0.75 * tWidth && width < 1.5 * tWidth, "width $width against the t interval's $tWidth")
    }
}
