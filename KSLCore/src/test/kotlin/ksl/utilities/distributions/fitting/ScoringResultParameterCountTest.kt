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

package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.MetalogBoundedness
import ksl.utilities.distributions.MetalogDistribution
import ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.statistic.MVBSEstimatorIfc
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  `ScoringResult.numberOfParameters` reports what the estimator estimated, not the size of the
 *  distribution type's declared parameter schema.
 *
 *  The two agree for every family whose members all fit the same parameters. They diverge for the
 *  metalog, because boundedness is derived from the bound values rather than being part of the
 *  type, so all four members share one type and that type must declare both bounds. An unbounded
 *  fit estimates neither of them.
 *
 *  The distinction matters because the count is subtracted from the goodness-of-fit degrees of
 *  freedom. Counting a bound that was never estimated costs a degree of freedom and makes the test
 *  harsher than it should be.
 */
class ScoringResultParameterCountTest {

    private fun data(size: Int = 400): DoubleArray =
        LognormalRV(mean = 10.0, variance = 25.0, streamNum = 1).sample(size)

    @Test
    fun theCountComesFromTheEstimatorForEveryEstimatorInTheLibrary() {
        val observations = data()
        val estimators = PDFModeler.allEstimators + PDFModeler.metalogEstimators
        val results = PDFModeler(observations).estimateAndEvaluateScores(estimators)
        assertEquals(
            estimators.size, results.resultsSortedByScoring.size,
            "not every estimator in the library produced a scored result, so this test checked " +
                    "fewer than the whole library"
        )
        for (result in results.resultsSortedByScoring) {
            val declared = (result.estimationResult.estimator as MVBSEstimatorIfc).names.size
            assertEquals(
                declared, result.numberOfParameters,
                "${result.name}: the reported count did not match the estimator's own"
            )
        }
    }

    @Test
    fun aMetalogReportsOnlyTheBoundsItActuallyEstimated() {
        val observations = data()
        for (terms in 2..6) {
            for (boundedness in MetalogBoundedness.entries) {
                val estimator = MetalogParameterEstimator(terms, boundedness)
                val results = PDFModeler(observations)
                    .estimateAndEvaluateScores(setOf(estimator), automaticShifting = false)
                val result = results.resultsSortedByScoring.firstOrNull() ?: continue
                val expected = terms +
                        (if (boundedness.hasLowerBound) 1 else 0) +
                        (if (boundedness.hasUpperBound) 1 else 0)
                assertEquals(
                    expected, result.numberOfParameters,
                    "$terms term $boundedness reported the wrong number of estimated parameters"
                )
            }
        }
    }

    @Test
    fun theTypeStillDeclaresBothBoundsRegardlessOfWhatWasEstimated() {
        // The schema is deliberately unchanged: a metalog type always carries both bounds so that
        // one type can represent all four members and a bound can be changed without changing the
        // type. Only the *reported estimated* count moved.
        val observations = data()
        val estimator = MetalogParameterEstimator(4, MetalogBoundedness.Unbounded)
        val results = PDFModeler(observations)
            .estimateAndEvaluateScores(setOf(estimator), automaticShifting = false)
        val result = results.resultsSortedByScoring.first()
        assertEquals(4, result.numberOfParameters, "an unbounded four term metalog estimated four")
        assertEquals(
            6, result.rvType.rvParameters.numberOfParameters,
            "the type should still declare four coefficients and both bounds"
        )
        assertEquals(
            6, (result.distribution as MetalogDistribution).parameters().size,
            "the distribution's own parameter array is unchanged, which is what AIC and BIC use"
        )
    }

    @Test
    fun theClassicalFamiliesAreUnaffected() {
        // Every classical estimator fits exactly the parameters its type declares, so the change
        // must be invisible to them. If this fails, the switch moved more than intended.
        val observations = data()
        val results = PDFModeler(observations).estimateAndEvaluateScores(PDFModeler.allEstimators)
        // Pin the coverage before asserting anything about it. The loop below only inspects
        // estimators that produced a scored result, so an estimator that quietly stopped fitting
        // this data would shrink the loop rather than fail it, and the test would keep passing
        // while checking less. A legitimate drop-out should be an edit here, not a silent one.
        assertEquals(
            PDFModeler.allEstimators.size, results.resultsSortedByScoring.size,
            "not every classical estimator produced a scored result, so this test checked fewer " +
                    "families than it claims to"
        )
        for (result in results.resultsSortedByScoring) {
            assertEquals(
                result.rvType.rvParameters.numberOfParameters, result.numberOfParameters,
                "${result.name}: a classical family's reported count changed"
            )
        }
    }
}
