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
 *  As pinned: the BCa interval fails with an exception for tied data (the bias correction is infinite for the
 *  minimum, the acceleration 0/0 for the median), and so does the bootstrap report; a negative confidence level
 *  passes the level check and fails later with an unrelated message.
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
    @DisplayName("BCa interval of the median of tied data")
    fun tiedMedian() {
        val bs = bootstrap(tied, BSEstimatorIfc.Median())
        assertFailsWith<IllegalArgumentException> { bs.bcaBootstrapCI(0.99) }
    }

    @Test
    @DisplayName("BCa interval of the minimum of tied data")
    fun tiedMinimum() {
        val bs = bootstrap(tied, BSEstimatorIfc.Minimum())
        assertFailsWith<IllegalArgumentException> { bs.bcaBootstrapCI(0.99) }
    }

    @Test
    @DisplayName("Bootstrap report of the median of tied data")
    fun tiedMedianReport() {
        val bs = bootstrap(tied, BSEstimatorIfc.Median())
        assertFailsWith<IllegalArgumentException> { report("Tied") { bootstrapEstimate(bs) }.renderToText() }
    }

    @Test
    @DisplayName("A confidence level below zero")
    fun negativeLevel() {
        val bs = bootstrap(untied, BSEstimatorIfc.Average())
        val error = assertFailsWith<IllegalArgumentException> { bs.percentileBootstrapCI(-0.5) }
        assertTrue(error.message!!.contains("lower limit"), "the level is not what is rejected: ${error.message}")
    }

    @Test
    @DisplayName("BCa interval of the mean of untied data")
    fun untiedMean() {
        val bs = bootstrap(untied, BSEstimatorIfc.Average())
        val ci = bs.bcaBootstrapCI(0.95)
        assertEquals(
            listOf(0.00501327755457324, 0.1228838557891167, 7.462813165465454, 13.444577612120362),
            listOf(bs.biasCorrectionFactor(), bs.accelerationFactor(), ci.lowerLimit, ci.upperLimit)
        )
    }

    private fun ReportNode.Document.renderToText(): String {
        val renderer = TextReportRenderer(RenderContext())
        accept(renderer)
        return renderer.result()
    }
}
