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

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.result.FitResultData
import ksl.utilities.random.rvariable.PoissonRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the discrete (PMF) fitting path: integer data -> PMFModeler ->
 * chi-squared goodness of fit, ranked by p-value, with the PDF/PMF asymmetry
 * preserved (no MODA scoring, no histogram, no bootstrap).
 */
class DiscreteFittingTest {

    private fun poissonSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = PoissonRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value } // integer-valued doubles
    }

    private fun discreteReport(streamNumber: Int = 91): FitResultData {
        val data = poissonSample(mean = 5.0, n = 400, streamNumber = streamNumber)
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("counts" to data)),
            kind = DistributionKind.DISCRETE
            // empty estimatorIds -> catalog discrete defaults
        )
        return FittingRunner.fit(config)
    }

    @Test
    fun `discrete fit produces a DISCRETE result with ranked chi-squared fits`() {
        val report = discreteReport()
        assertEquals(DistributionKind.DISCRETE, report.kind)
        assertEquals("counts", report.datasetName)
        assertTrue(report.fits.isNotEmpty())
        assertNotNull(report.recommendedFamilyId)
        // ranks are 1..n in order
        assertEquals((1..report.fits.size).toList(), report.fits.map { it.rank })
    }

    @Test
    fun `discrete asymmetry - no MODA scoring, no histogram, no bootstrap`() {
        val report = discreteReport()
        assertNull(report.scoring, "discrete fits do not use MODA scoring")
        assertNull(report.histogram, "discrete fits do not produce a bin histogram")
        report.fits.forEach {
            assertNull(it.weightedValue)
            assertNull(it.averageRanking)
            assertNull(it.firstRankCount)
            assertNull(it.bootstrap)
        }
    }

    @Test
    fun `successful discrete fits carry chi-squared and dispersion statistics`() {
        val report = discreteReport()
        val success = report.fits.filter { it.success }
        assertTrue(success.isNotEmpty())
        for (fit in success) {
            assertNotNull(fit.chiSquaredPValue)
            val gof = assertNotNull(fit.goodnessOfFit)
            assertTrue(gof.chiSquaredPValue in 0.0..1.0)
            assertNotNull(gof.indexOfDispersion, "discrete GoF should carry index of dispersion")
            assertNotNull(gof.poissonVarianceTestStatistic, "discrete GoF should carry the Poisson variance test")
            // continuous-only statistics are absent
            assertNull(gof.ksPValue)
            assertNull(gof.andersonDarlingPValue)
        }
    }

    @Test
    fun `discrete fits are ordered by descending chi-squared p-value`() {
        val report = discreteReport()
        val pValues = report.fits.filter { it.success }.map { it.chiSquaredPValue!! }
        assertEquals(pValues.sortedDescending(), pValues)
        // recommendation is the rank-1 successful family
        assertEquals(report.fits.first { it.success }.familyId, report.recommendedFamilyId)
    }

    @Test
    fun `Poisson data yields a successful Poisson fit`() {
        val report = discreteReport()
        val poisson = report.fits.firstOrNull { it.familyId == "poisson" }
        assertNotNull(poisson, "a Poisson fit should be present")
        assertTrue(poisson.success)
    }
}
