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

package ksl.app.dist.runner

import ksl.app.dist.config.BootstrapConfig
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.result.FitResultData
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the full live -> DTO extraction via FittingRunner.fit(), asserting
 * the fields R1 left null are now populated (goodness-of-fit, histogram, full
 * MODA, bootstrap), and that the surfaced engine parameters take effect.
 */
class FitResultExtractorTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private fun config(
        data: DoubleArray,
        bootstrap: BootstrapConfig? = null,
        evaluationMethod: ksl.app.dist.config.EvaluationMethod = ksl.app.dist.config.EvaluationMethod.SCORING
    ): FitConfiguration = FitConfiguration(
        dataSource = DataSourceReference.Inline(mapOf("x" to data)),
        estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle"),
        evaluationMethod = evaluationMethod,
        bootstrap = bootstrap
    )

    private fun runReport(
        bootstrap: BootstrapConfig? = null,
        streamNumber: Int = 51,
        evaluationMethod: ksl.app.dist.config.EvaluationMethod = ksl.app.dist.config.EvaluationMethod.SCORING
    ): FitResultData {
        val data = exponentialSample(mean = 2.0, n = 300, streamNumber = streamNumber)
        return FittingRunner.fit(config(data, bootstrap, evaluationMethod))
    }

    @Test
    fun `goodness of fit is populated for successful fits`() {
        val report = runReport()
        val top = report.fits.first { it.success }
        val gof = assertNotNull(top.goodnessOfFit, "successful fit should carry goodness of fit")
        assertTrue(gof.chiSquaredPValue in 0.0..1.0)
        assertTrue(gof.binBreakPoints.isNotEmpty())
        assertTrue(gof.expectedCounts.isNotEmpty())
        assertTrue(gof.observedCounts.isNotEmpty())
        // continuous-only statistics are present
        assertNotNull(gof.ksPValue)
        assertNotNull(gof.andersonDarlingPValue)
        assertNotNull(gof.cramerVonMisesPValue)
    }

    @Test
    fun `histogram is populated`() {
        val report = runReport()
        val hist = assertNotNull(report.histogram)
        assertTrue(hist.bins.isNotEmpty())
        val binTotal = hist.bins.sumOf { it.count }
        assertTrue(binTotal > 0.0)
    }

    @Test
    fun `full MODA scoring is populated and join keys are consistent`() {
        val report = runReport()
        val moda = assertNotNull(report.scoring)
        assertTrue(moda.metrics.isNotEmpty())
        assertTrue(moda.scores.isNotEmpty())
        assertTrue(moda.values.isNotEmpty())
        // every MODA score's alternative matches some fit's displayName (join-key integrity)
        val displayNames = report.fits.map { it.displayName }.toSet()
        assertTrue(moda.scores.all { it.alternative in displayNames })
        assertEquals("Ordinal", moda.rankingMethod)
    }

    @Test
    fun `bootstrap summaries are populated when requested`() {
        val report = runReport(bootstrap = BootstrapConfig(sampleSize = 50, level = 0.95, streamNumber = 1))
        val top = report.fits.first { it.success }
        val boots = assertNotNull(top.bootstrap, "bootstrap should be present when requested")
        assertTrue(boots.isNotEmpty())
        for (b in boots) {
            assertEquals(50, b.numBootstraps)
            assertEquals(0.95, b.ciLevel)
            assertTrue(b.normalCILower <= b.normalCIUpper)
            assertTrue(b.basicCILower <= b.basicCIUpper)
            assertTrue(b.percentileCILower <= b.percentileCIUpper)
        }
    }

    @Test
    fun `bootstrap is null when not requested`() {
        val report = runReport(bootstrap = null)
        assertTrue(report.fits.all { it.bootstrap == null })
    }

    @Test
    fun `failed fits carry no goodness of fit`() {
        val report = runReport()
        report.fits.filter { !it.success }.forEach { assertNull(it.goodnessOfFit) }
    }

    @Test
    fun `non-bootstrap fields are deterministic for identical data without shifting`() {
        // Build the data once so both runs fit the SAME numbers (constructing
        // ExponentialRV twice on one stream number would advance the shared
        // stream and yield different samples). Disable automatic shifting so
        // the engine's internal shift-decision bootstrap (default RNG stream)
        // does not perturb determinism — isolating the extractor's own behavior.
        val data = exponentialSample(mean = 2.0, n = 300, streamNumber = 99)
        val cfg = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("x" to data)),
            estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle"),
            automaticShifting = false
        )
        val a = FittingRunner.fit(cfg)
        val b = FittingRunner.fit(cfg)
        assertEquals(a.dataSummary, b.dataSummary)
        assertEquals(a.histogram, b.histogram)
        assertEquals(a.scoring, b.scoring)
        assertEquals(a.fits.map { it.goodnessOfFit }, b.fits.map { it.goodnessOfFit })
        assertEquals(a.recommendedFamilyId, b.recommendedFamilyId)
    }
}
