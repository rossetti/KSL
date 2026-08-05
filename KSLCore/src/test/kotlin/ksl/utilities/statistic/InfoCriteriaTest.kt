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

import ksl.utilities.distributions.CDFIfc
import org.junit.jupiter.api.DisplayName
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  The information criteria, and the Watson statistic that sits beside them in the same companion.
 *
 *  Three of the four were wrong in R1.5 and earlier, each quietly:
 *
 *  `akaikeInfoCriterion` applied (n - 2p + 2)/(n - p + 1) as its penalty rather than 2p. That
 *  ratio lies in (0, 1] for any sample larger than the parameter count and *shrinks* as parameters
 *  are added, so the criterion decreased monotonically in p at fixed likelihood. Minimising it
 *  therefore selected the most complex model on offer whenever the likelihood improved at all.
 *
 *  `hannanQuinnInfoCriterion` carried the same ratio and also multiplied the log-likelihood by the
 *  parameter count, so its value was off by a factor of p in the fit term.
 *
 *  `watsonTestStatistic` computed (2i - 1)/2 * n where the definition is (2i - 1)/(2n) -- the
 *  identical expression one function above it, in `cramerVonMisesTestStatistic`, is parenthesised
 *  correctly.
 *
 *  The invariant tests below are deliberately formula-independent: holding the likelihood fixed,
 *  adding a parameter must never improve a complexity-penalising criterion. That alone fails on
 *  every broken form above without appealing to any particular published definition.
 */
class InfoCriteriaTest {

    /**
     *  The worked example from the report that surfaced the AIC defect: n = 400 at this
     *  log-likelihood, which makes -2L equal to 2057.93 exactly.
     */
    private val sampleSize = 400
    private val logLikelihood = -1028.965
    private val twiceNegativeLogLikelihood = 2057.93

    private val parameterCounts = listOf(1, 2, 5, 10, 14, 18, 30)

    @Test
    @DisplayName("AIC is minus twice the log-likelihood plus twice the parameter count")
    fun akaikeInfoCriterionIsTwoPMinusTwoLogLikelihood() {
        for (p in parameterCounts) {
            assertEquals(
                twiceNegativeLogLikelihood + 2.0 * p,
                Statistic.akaikeInfoCriterion(p, logLikelihood),
                1e-9,
            )
        }
    }

    @Test
    @DisplayName("AIC's penalty grows by exactly two for each added parameter")
    fun akaikePenaltyGrowsByTwoPerParameter() {
        // The defining property, stated without reference to the formula. The R1.5 implementation
        // moved this difference to about -0.0025 -- negative, and shrinking with p.
        for (p in 1..40) {
            val difference = Statistic.akaikeInfoCriterion(p + 1, logLikelihood) -
                    Statistic.akaikeInfoCriterion(p, logLikelihood)
            assertEquals(2.0, difference, 1e-9)
        }
    }

    @Test
    @DisplayName("Adding a parameter never improves any of the criteria")
    fun addingAParameterNeverImprovesACriterion() {
        // Formula-independent: at a fixed likelihood a complexity-penalising criterion must be
        // strictly increasing in the parameter count. The sample size here is well past e^e, so
        // Hannan-Quinn's per-parameter penalty is positive too.
        val aic = (1..20).map { Statistic.akaikeInfoCriterion(it, logLikelihood) }
        val aicc = (1..20).map { Statistic.akaikeInfoCriterionCorrected(sampleSize, it, logLikelihood) }
        val bic = (1..20).map { Statistic.bayesianInfoCriterion(sampleSize, it, logLikelihood) }
        val hqc = (1..20).map { Statistic.hannanQuinnInfoCriterion(sampleSize, it, logLikelihood) }
        for (values in listOf(aic, aicc, bic, hqc)) {
            assertTrue(values.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test
    @DisplayName("AICc adds the small-sample correction to AIC and converges to it")
    fun correctedAkaikeAddsTheSmallSampleCorrection() {
        // 2057.93 + 2(2) + 2(2)(3)/(400 - 2 - 1)
        assertEquals(2061.9602267002519, Statistic.akaikeInfoCriterionCorrected(400, 2, logLikelihood), 1e-9)
        for (p in parameterCounts) {
            val aic = Statistic.akaikeInfoCriterion(p, logLikelihood)
            val aicc = Statistic.akaikeInfoCriterionCorrected(sampleSize, p, logLikelihood)
            val correction = (2.0 * p * (p + 1.0)) / (sampleSize - p - 1.0)
            assertEquals(aic + correction, aicc, 1e-9)
            assertTrue(aicc > aic)
        }
        // The correction vanishes as the sample grows.
        val small = Statistic.akaikeInfoCriterionCorrected(20, 5, logLikelihood)
        val large = Statistic.akaikeInfoCriterionCorrected(1_000_000, 5, logLikelihood)
        val aic = Statistic.akaikeInfoCriterion(5, logLikelihood)
        assertTrue(small - aic > 4.0)
        assertTrue(large - aic < 1e-3)
    }

    @Test
    @DisplayName("AICc requires a sample larger than the parameter count plus one")
    fun correctedAkaikeRejectsAnUnusableSampleSize() {
        // n - p - 1 is the denominator of the correction, so it must be positive.
        assertFailsWith<IllegalArgumentException> {
            Statistic.akaikeInfoCriterionCorrected(6, 5, logLikelihood)
        }
        // One more observation makes it computable.
        Statistic.akaikeInfoCriterionCorrected(7, 5, logLikelihood)
    }

    @Test
    @DisplayName("BIC is minus twice the log-likelihood plus the parameter count times ln(n)")
    fun bayesianInfoCriterionUsesTheLogSampleSizePenalty() {
        assertEquals(2069.9129290942160, Statistic.bayesianInfoCriterion(400, 2, logLikelihood), 1e-9)
        for (p in parameterCounts) {
            assertEquals(
                twiceNegativeLogLikelihood + p * ln(sampleSize.toDouble()),
                Statistic.bayesianInfoCriterion(sampleSize, p, logLikelihood),
                1e-9,
            )
        }
    }

    @Test
    @DisplayName("Hannan-Quinn is minus twice the log-likelihood plus 2p ln(ln(n))")
    fun hannanQuinnUsesTheLogLogSampleSizePenalty() {
        // 2057.93 + 2(2)ln(ln(400)), with ln(ln(400)) = 1.790335881
        assertEquals(2065.0913435, Statistic.hannanQuinnInfoCriterion(400, 2, logLikelihood), 1e-6)
        for (p in parameterCounts) {
            assertEquals(
                twiceNegativeLogLikelihood + 2.0 * p * ln(ln(sampleSize.toDouble())),
                Statistic.hannanQuinnInfoCriterion(sampleSize, p, logLikelihood),
                1e-9,
            )
        }
    }

    @Test
    @DisplayName("Hannan-Quinn's fit term does not scale with the parameter count")
    fun hannanQuinnFitTermIsIndependentOfTheParameterCount() {
        // R1.5 returned ratio * ln(ln(n)) - 2p * L, so doubling the parameters roughly doubled the
        // whole value. Here the difference between two parameter counts depends on the penalty
        // alone, so it is the same whatever the likelihood is.
        val atOneThousand = Statistic.hannanQuinnInfoCriterion(sampleSize, 4, -1000.0) -
                Statistic.hannanQuinnInfoCriterion(sampleSize, 2, -1000.0)
        val atOne = Statistic.hannanQuinnInfoCriterion(sampleSize, 4, -1.0) -
                Statistic.hannanQuinnInfoCriterion(sampleSize, 2, -1.0)
        assertEquals(atOne, atOneThousand, 1e-9)
        assertEquals(4.0 * ln(ln(sampleSize.toDouble())), atOne, 1e-9)
    }

    @Test
    @DisplayName("Hannan-Quinn needs more than one observation")
    fun hannanQuinnRejectsASingleObservation() {
        // ln(ln(1)) is not finite, so a single observation cannot produce a criterion value.
        assertFailsWith<IllegalArgumentException> {
            Statistic.hannanQuinnInfoCriterion(1, 2, logLikelihood)
        }
        assertTrue(Statistic.hannanQuinnInfoCriterion(2, 2, logLikelihood).isFinite())
    }

    @Test
    @DisplayName("Every criterion rejects a non-finite log-likelihood")
    fun everyCriterionRejectsANonFiniteLogLikelihood() {
        for (value in listOf(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)) {
            assertFailsWith<IllegalArgumentException> { Statistic.akaikeInfoCriterion(2, value) }
            assertFailsWith<IllegalArgumentException> { Statistic.akaikeInfoCriterionCorrected(400, 2, value) }
            assertFailsWith<IllegalArgumentException> { Statistic.bayesianInfoCriterion(400, 2, value) }
            assertFailsWith<IllegalArgumentException> { Statistic.hannanQuinnInfoCriterion(400, 2, value) }
        }
    }

    @Test
    @DisplayName("The deprecated three-argument AIC ignores the sample size")
    @Suppress("DEPRECATION")
    fun deprecatedThreeArgumentAkaikeIgnoresTheSampleSize() {
        // Kept so that code written against R1.5 still compiles. Whatever sample size it is
        // handed, it must agree with the two-argument form.
        for (n in listOf(1, 5, 400, 1_000_000)) {
            assertEquals(
                Statistic.akaikeInfoCriterion(10, -3.0),
                Statistic.akaikeInfoCriterion(n, 10, -3.0),
                1e-9,
            )
        }
        // The plain criterion divides by nothing, so the R1.5 guard requiring n - p + 1 > 0 had no
        // basis and is gone. Over-parameterised candidates are exactly the ones the penalty exists
        // to rank, so refusing to score them was the wrong answer.
        assertEquals(26.0, Statistic.akaikeInfoCriterion(5, 10, -3.0), 1e-9)
        // The sample size is still checked for sanity, even though it does not enter the value.
        assertFailsWith<IllegalArgumentException> { Statistic.akaikeInfoCriterion(0, 10, -3.0) }
    }

    @Test
    @DisplayName("The Watson statistic is Cramer-von Mises less n times the mean-CDF correction")
    fun watsonStatisticIsCramerVonMisesLessTheCorrection() {
        val uniformCdf = CDFIfc { x -> x }

        // Five observations sitting exactly on the expected quantiles of a uniform: the sum of
        // squared deviations is zero and the mean CDF is one half, leaving 1/(12n).
        val onTheDiagonal = doubleArrayOf(0.1, 0.3, 0.5, 0.7, 0.9)
        assertEquals(1.0 / 60.0, Statistic.watsonTestStatistic(onTheDiagonal, uniformCdf), 1e-12)
        // R1.5 returned about 950.4 for this sample -- the expected quantiles were inflated by n^2.
        assertTrue(Statistic.watsonTestStatistic(onTheDiagonal, uniformCdf) < 1.0)

        // A sample whose mean CDF is 0.3, so the correction term is exercised: the Cramer-von
        // Mises statistic is 1/60 + 0.30 and the correction is 5 * (0.3 - 0.5)^2 = 0.2.
        val shiftedLow = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5)
        val cvm = Statistic.cramerVonMisesTestStatistic(shiftedLow, uniformCdf)
        assertEquals(1.0 / 60.0 + 0.30, cvm, 1e-12)
        assertEquals(cvm - 0.2, Statistic.watsonTestStatistic(shiftedLow, uniformCdf), 1e-12)
    }
}
