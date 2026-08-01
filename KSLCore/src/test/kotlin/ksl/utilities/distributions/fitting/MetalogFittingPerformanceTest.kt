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

package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.metalog.MetalogDistribution
import ksl.utilities.distributions.fitting.estimators.MetalogPlottingPositions
import ksl.utilities.random.rng.RNStreamProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 *  A budget on the cost of fitting the whole metalog family to a realistically sized sample.
 *
 *  The budgets here are deliberately several times the measured cost. They exist to catch a
 *  change that makes the family an order of magnitude more expensive — an inversion that stops
 *  converging quickly, a bound ladder that turns back into a cross product — not to police
 *  ordinary variation between machines.
 *
 *  The breakdown is printed rather than asserted piecewise, because which half dominates is the
 *  useful diagnostic when the gate does trip.
 */
class MetalogFittingPerformanceTest {

    private companion object {
        const val SAMPLE_SIZE = 5_000

        /** The whole pipeline: twenty estimations, twenty reconstructions, four scoring models. */
        val FIT_AND_SCORE_BUDGET: Duration = 60.seconds

        /** Estimation alone, which the plotting-position resampling makes near constant in n. */
        val ESTIMATION_BUDGET: Duration = 20.seconds
    }

    private fun sample(size: Int = SAMPLE_SIZE, streamNum: Int = 1): DoubleArray {
        val rv = Lognormal(mean = 10.0, variance = 25.0).randomVariable(streamNum, RNStreamProvider())
        return DoubleArray(size) { rv.value }
    }

    @Test
    fun fittingAndScoringTheWholeFamilyStaysWithinBudget() {
        val data = sample()
        // Warm the JIT on a smaller sample so the measurement is not dominated by interpretation.
        PDFModeler(sample(size = 500, streamNum = 2)).estimateAndEvaluateScores(PDFModeler.metalogEstimators)

        val modeler = PDFModeler(data)
        val estimation = measureTimedValue { modeler.estimateParameters(PDFModeler.metalogEstimators) }
        val scoring = measureTimedValue { modeler.evaluateScores(estimation.value) }
        val total = estimation.duration + scoring.duration

        println(
            "metalog fit-and-score over $SAMPLE_SIZE observations across " +
                    "${PDFModeler.metalogEstimators.size} estimators: " +
                    "estimation ${estimation.duration}, scoring ${scoring.duration}, total $total"
        )

        assertTrue(
            scoring.value.scoringResults.size == PDFModeler.metalogEstimators.size,
            "the run did not score every estimator, so the timing is not comparable"
        )
        assertTrue(
            estimation.duration <= ESTIMATION_BUDGET,
            "estimation took ${estimation.duration}, over the $ESTIMATION_BUDGET budget"
        )
        assertTrue(
            total <= FIT_AND_SCORE_BUDGET,
            "fit and score took $total, over the $FIT_AND_SCORE_BUDGET budget"
        )
    }

    @Test
    fun metalogEstimationStaysCheapOnAMuchLargerSample() {
        // Measured with automatic shifting off, which isolates the family. `PDFModeler` otherwise
        // bootstraps a confidence interval for the minimum on every call to decide whether to
        // shift, which is linear in the sample size and, at five thousand observations, roughly
        // twice the cost of the twenty metalog fits put together — and is pure overhead here,
        // since every metalog estimator reports checkRange = false and can never consume the
        // shift. That is PDFModeler's behaviour for all callers, not the family's, so it is
        // excluded rather than asserted on.
        //
        // Stated as an absolute budget rather than as a ratio against a smaller sample: the
        // sample-size-dependent part of estimation is a minority of the total, so a ratio between
        // two single-shot timings mostly measures JIT warmth. The structural guarantee that the
        // fitted problem stops growing is asserted directly below.
        val estimators = PDFModeler.metalogEstimators
        PDFModeler(sample(size = 500, streamNum = 3)).estimateParameters(estimators, automaticShifting = false)

        val modeler = PDFModeler(sample(size = 4 * SAMPLE_SIZE, streamNum = 5))
        val elapsed = measureTime { modeler.estimateParameters(estimators, automaticShifting = false) }
        println("metalog estimation without shifting over ${4 * SAMPLE_SIZE} observations: $elapsed")
        assertTrue(
            elapsed <= ESTIMATION_BUDGET,
            "estimation over ${4 * SAMPLE_SIZE} observations took $elapsed, over the $ESTIMATION_BUDGET budget"
        )
    }

    @Test
    fun theShiftBootstrapNotTheMetalogFitsDominatesTheDefaultPath() {
        // Records where the time in the headline gate actually goes, so that a future regression
        // in one is not mistaken for the other.
        val data = sample()
        val modeler = PDFModeler(data)
        modeler.estimateParameters(PDFModeler.metalogEstimators, automaticShifting = false)

        val withoutShift = measureTime {
            modeler.estimateParameters(PDFModeler.metalogEstimators, automaticShifting = false)
        }
        val withShift = measureTime { modeler.estimateParameters(PDFModeler.metalogEstimators) }
        println(
            "estimation over $SAMPLE_SIZE observations: $withoutShift without the shift bootstrap, " +
                    "$withShift with it"
        )
        assertTrue(withoutShift <= ESTIMATION_BUDGET, "metalog estimation alone took $withoutShift")
    }

    @Test
    fun theLeastSquaresProblemStopsGrowingWithTheSampleSize() {
        // The structural reason estimation does not scale: past the resampling threshold the
        // plotting positions hand back a fixed probability grid regardless of how much data
        // stands behind it. Asserted directly, because a timing gate cannot distinguish this
        // from a machine that happened to be fast.
        val sizes = intArrayOf(500, 5_000, 50_000)
        val gridSizes = sizes.map { size ->
            val (values, probabilities) = MetalogPlottingPositions.cdfData(sample(size = size, streamNum = 6))
            assertTrue(values.size == probabilities.size, "cdf data is ragged at $size observations")
            values.size
        }
        println("metalog cdf grid sizes for ${sizes.toList()}: $gridSizes")
        assertTrue(
            gridSizes.toSet().size == 1,
            "the fitted grid grew with the sample size: ${sizes.toList()} gave $gridSizes"
        )
    }

    @Test
    fun repeatedCdfInversionOnAFittedMetalogIsAffordable() {
        // Scoring evaluates the cdf once per observation, and a metalog cdf is a numerical
        // inversion of its quantile function. This isolates that cost from everything else.
        val data = sample()
        val results = PDFModeler(data).estimateAndEvaluateScores(PDFModeler.metalogEstimators)
        val fitted = results.topResultByScore.distribution as MetalogDistribution
        repeat(1_000) { fitted.cdf(data[it]) }

        val elapsed = measureTime { for (x in data) fitted.cdf(x) }
        val perCall = elapsed / SAMPLE_SIZE
        println("metalog cdf inversion: $SAMPLE_SIZE calls in $elapsed, $perCall per call")
        assertTrue(elapsed <= 5.seconds, "$SAMPLE_SIZE cdf inversions took $elapsed")
    }
}
