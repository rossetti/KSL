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
package ksl.utilities.distributions.fitting.mixture.search

import ksl.utilities.distributions.fitting.estimators.ExponentialMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.GammaMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.distributions.fitting.estimators.LognormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.WeibullMLEParameterEstimator
import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.ComponentFitCache
import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.distributions.fitting.mixture.PDFComponentFitter
import ksl.utilities.distributions.fitting.mixture.partition.JenksPartitionGenerator
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.random.rvariable.NormalRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  The selectors' claims are about cost and about what they give up for it, so the tests check
 *  both: that the cheap one is genuinely cheap, that the exhaustive one genuinely wins on the
 *  criterion, and that a bounded search says when it was bounded.
 */
class SelectorTest {

    private val estimators: Set<ParameterEstimatorIfc> = setOf(
        NormalMLEParameterEstimator,
        LognormalMLEParameterEstimator,
        ExponentialMLEParameterEstimator,
        GammaMLEParameterEstimator(),
        WeibullMLEParameterEstimator()
    )

    /** A well-separated two-component sample, positive-valued so every catalog family can fit. */
    private fun sample(n: Int = 400): DoubleArray {
        val low = NormalRV(10.0, 1.0, streamNum = 41)
        val high = NormalRV(30.0, 1.0, streamNum = 42)
        val data = DoubleArray(n) { if (it % 2 == 0) low.value else high.value }
        return data.sortedArray()
    }

    private fun fitsFor(sorted: DoubleArray, k: Int): Pair<DataPartition, List<ksl.utilities.distributions.fitting.mixture.GroupFitResult>> {
        val certificate = AdmissibilityCertificate(sorted)
        val partition = JenksPartitionGenerator(sorted, k).generate(sorted, k, certificate)!!
        val fitter = ComponentFitCache(PDFComponentFitter(estimators))
        return partition to fitter.fitAll(sorted, partition)
    }

    @Test
    fun `the separable selector assembles exactly one mixture`() {
        val sorted = sample()
        val (partition, fits) = fitsFor(sorted, 2)
        val result = SeparableCMLSelector().select(sorted, partition, fits, MixtureBICCriterion())
        assertNotNull(result.candidate)
        assertEquals(1, result.numMixturesEvaluated)
        assertFalse(result.wasCapped)
        // it looked at each group's candidates once: linear in the catalog, not exponential
        assertEquals(fits.sumOf { it.candidates.size }, result.numCandidatesConsidered)
    }

    @Test
    fun `the exhaustive selector assembles the product of the per-group counts`() {
        val sorted = sample()
        val (partition, fits) = fitsFor(sorted, 2)
        val expected = fits.fold(1) { acc, fit -> acc * fit.candidates.size }
        val result = ExhaustiveProductSelector().select(sorted, partition, fits, MixtureBICCriterion())
        assertNotNull(result.candidate)
        assertEquals(expected, result.numMixturesEvaluated)
        assertFalse(result.wasCapped)
    }

    @Test
    fun `the exhaustive selector is never beaten by the separable one on the criterion`() {
        val sorted = sample()
        val criterion = MixtureBICCriterion()
        for (k in 2..4) {
            val (partition, fits) = fitsFor(sorted, k)
            if (fits.any { !it.hasCandidates }) continue
            val exhaustive = ExhaustiveProductSelector().select(sorted, partition, fits, criterion)
            val separable = SeparableCMLSelector().select(sorted, partition, fits, criterion)
            assertNotNull(exhaustive.candidate)
            assertNotNull(separable.candidate)
            // the exhaustive search optimizes the criterion over a superset of one point, so its
            // value can only be as good or better; a violation would mean a bug in one of them
            assertTrue(
                exhaustive.criterionValue <= separable.criterionValue + 1.0e-9,
                "k=$k: exhaustive ${exhaustive.criterionValue} should not exceed " +
                        "separable ${separable.criterionValue}"
            )
        }
    }

    @Test
    fun `the separable selector costs far less as the number of groups grows`() {
        val sorted = sample(600)
        val criterion = MixtureBICCriterion()
        val (partition, fits) = fitsFor(sorted, 4)
        if (fits.any { !it.hasCandidates }) return
        val exhaustive = ExhaustiveProductSelector().select(sorted, partition, fits, criterion)
        val separable = SeparableCMLSelector().select(sorted, partition, fits, criterion)
        // this is the whole tractability claim: assembled mixtures, not fitted candidates
        assertTrue(
            exhaustive.numMixturesEvaluated > 10 * separable.numMixturesEvaluated,
            "exhaustive assembled ${exhaustive.numMixturesEvaluated}, " +
                    "separable ${separable.numMixturesEvaluated}"
        )
    }

    @Test
    fun `a bounded exhaustive search reports that it was bounded`() {
        val sorted = sample()
        val (partition, fits) = fitsFor(sorted, 3)
        val total = fits.fold(1) { acc, fit -> acc * fit.candidates.size }
        if (total <= 2) return
        val bound = total - 1
        val result = ExhaustiveProductSelector(bound).select(sorted, partition, fits, MixtureBICCriterion())
        assertTrue(result.wasCapped, "a search stopped short must say so")
        assertEquals(bound, result.numMixturesEvaluated)
    }

    @Test
    fun `a group with no fitted candidates yields no selection`() {
        val sorted = sample()
        val (partition, fits) = fitsFor(sorted, 2)
        val empty = fits.mapIndexed { index, fit ->
            if (index == 0) ksl.utilities.distributions.fitting.mixture.GroupFitResult(
                fit.startIndex, fit.endIndex, emptyList(), fit.rejections
            ) else fit
        }
        val separable = SeparableCMLSelector().select(sorted, partition, empty, MixtureBICCriterion())
        val exhaustive = ExhaustiveProductSelector().select(sorted, partition, empty, MixtureBICCriterion())
        assertEquals(null, separable.candidate)
        assertEquals(null, exhaustive.candidate)
    }
}
