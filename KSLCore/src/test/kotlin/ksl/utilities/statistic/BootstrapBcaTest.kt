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

import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.extensions.bootstrapEstimate
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  The BCa bootstrap interval and the confidence-level check of the bootstrap intervals.
 *
 *  Before R1.8 the BCa interval failed with an exception for tied data (the bias correction was infinite for the
 *  minimum, the acceleration 0/0 for the median), and so did the bootstrap report; a negative confidence level
 *  passed the level check and failed later with an unrelated message. Now a bootstrap estimate equal to the
 *  original counts as half below it, the acceleration is 0 when the jackknife estimates do not vary, and what is
 *  still undefined is reported by canComputeBcaBootstrapCI() and a clear error.
 */
class BootstrapBcaTest {

    /** 200 observations of a discrete variable with many ties, as a simulation with discrete inputs produces. */
    private val tied = DoubleArray(200) { listOf(10.0, 20.0, 20.0, 30.0, 30.0, 30.0)[it % 6] }

    private fun bootstrap(data: DoubleArray, estimator: BSEstimatorIfc): Bootstrap {
        val bs = Bootstrap(data, estimator, streamNumber = 1, streamProvider = RNStreamProvider())
        bs.generateSamples(500)
        return bs
    }

    /** 50 observations of an exponential with mean 10, without ties. */
    private val untied: DoubleArray = ExponentialRV(10.0, 1, RNStreamProvider()).sample(50)

    @Test
    @DisplayName("The BCa interval of the median of tied data is defined")
    fun tiedMedian() {
        val bs = bootstrap(tied, BSEstimatorIfc.Median())
        assertTrue(bs.canComputeBcaBootstrapCI())
        assertEquals(0.0, bs.accelerationFactor(), "the jackknife medians do not vary")
        val ci = bs.bcaBootstrapCI(0.99)
        assertEquals(listOf(20.0, 30.0), listOf(ci.lowerLimit, ci.upperLimit))
    }

    @Test
    @DisplayName("The BCa interval of the minimum of tied data is defined")
    fun tiedMinimum() {
        val bs = bootstrap(tied, BSEstimatorIfc.Minimum())
        assertTrue(bs.biasCorrectionFactor().isFinite(), "bootstrap minima equal to the original count as half below it")
        val ci = bs.bcaBootstrapCI(0.99)
        assertEquals(listOf(10.0, 10.0), listOf(ci.lowerLimit, ci.upperLimit))
    }

    @Test
    @DisplayName("The bootstrap report of the median of tied data renders")
    fun tiedMedianReport() {
        val bs = bootstrap(tied, BSEstimatorIfc.Median())
        val text = report("Tied") { bootstrapEstimate(bs) }.renderToText()
        assertTrue(text.contains("BCa"), text)
    }

    /**
     *  The number of distinct values of distinct data: a resample almost always has fewer, so every bootstrap
     *  estimate lies below the original and the bias correction is infinite. The interval is not defined; the check
     *  says so, the interval fails with a clear error, and the report shows it as not defined.
     */
    @Test
    @DisplayName("When the BCa interval is not defined, the check says so, the interval fails clearly, the report shows it")
    fun undefinedBca() {
        val distinctValues = BSEstimatorIfc { data -> data.distinct().size.toDouble() }
        val bs = bootstrap(untied, distinctValues)
        assertEquals(false, bs.canComputeBcaBootstrapCI())
        val error = assertFailsWith<IllegalStateException> { bs.bcaBootstrapCI(0.95) }
        assertTrue(error.message!!.contains("not defined"), error.message)
        val text = report("Undefined") { bootstrapEstimate(bs) }.renderToText()
        assertTrue(text.contains("not defined"), text)
    }

    @Test
    @DisplayName("A confidence level of 0, 1 or below 0 is rejected by the level check")
    fun negativeLevel() {
        val bs = bootstrap(untied, BSEstimatorIfc.Average())
        for (level in listOf(-0.5, 0.0, 1.0)) {
            val error = assertFailsWith<IllegalArgumentException> { bs.percentileBootstrapCI(level) }
            assertTrue(error.message!!.contains("Confidence Level"), "level $level: ${error.message}")
        }
    }

    @Test
    @DisplayName("BCa interval of the mean of untied data")
    fun untiedMean() {
        val bs = bootstrap(untied, BSEstimatorIfc.Average())
        val ci = bs.bcaBootstrapCI(0.95)
        // before R1.8: 0.00501327755457324, 0.1228838557891167, 7.462813165465454, 13.444577612120362
        assertEquals(
            listOf(0.00501327755457324, 0.03254992428908746, 7.157336016122315, 12.353203468702207),
            listOf(bs.biasCorrectionFactor(), bs.accelerationFactor(), ci.lowerLimit, ci.upperLimit)
        )
    }

    /**
     *  For the mean of 1, 2, 3, 4, 10: the jackknife replicates are 4.75, 4.5, 4.25, 4.0 and 2.5, their mean 4.0, the
     *  deviations -0.75, -0.5, -0.25, 0 and 1.5, with sum of squares 25/8 and sum of cubes 2.8125, so the acceleration is
     *  2.8125 / (6 (25/8)^(3/2)) = 0.06 sqrt(2). Before R1.8 the 3/2 power was applied term by term, giving 0.119.
     */
    @Test
    @DisplayName("The acceleration is the sum of cubed jackknife deviations over 6 times the 3/2 power of the sum of squares")
    fun accelerationByHand() {
        val a = Bootstrap.accelerationFactor(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 10.0), BSEstimatorIfc.Average())
        assertEquals(0.06 * kotlin.math.sqrt(2.0), a, 1e-12)
    }

    private fun ReportNode.Document.renderToText(): String {
        val renderer = TextReportRenderer(RenderContext())
        accept(renderer)
        return renderer.result()
    }
}
