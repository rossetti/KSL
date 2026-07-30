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

import ksl.utilities.distributions.Metalog2P
import ksl.utilities.distributions.Metalog3P
import ksl.utilities.distributions.Metalog4P
import ksl.utilities.distributions.Metalog5P
import ksl.utilities.distributions.MetalogBoundedness
import ksl.utilities.distributions.MetalogDistribution
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  Whether fitting data drawn from a known metalog recovers that metalog.
 *
 *  The question has to be asked precisely, because the obvious phrasing is not quite the right
 *  one. "Does the fit return the generating arity and boundedness?" is a question about *labels*,
 *  and the answer is legitimately no in general. The arities nest: a four-term metalog whose
 *  fourth coefficient is small is nearly a three-term metalog, and no amount of data separates
 *  them. The information criteria then prefer the smaller model, which is the correct statistical
 *  answer rather than a defect. Boundedness has the same problem, since an unbounded metalog and
 *  a lower-bounded one with a distant bound agree everywhere the data lives.
 *
 *  The question worth answering is about the *distribution*: does fitting recover something close
 *  to the law that generated the data, and does the metalog family beat the classical families on
 *  data that genuinely came from a metalog? Those are asserted here. Label recovery is measured
 *  and reported rather than asserted, so the rate is on record instead of being assumed.
 *
 *  Every rate below is measured across independent replications rather than from a single sample,
 *  since a single fit tells you about one draw and nothing about the procedure.
 */
class MetalogRecoveryTest {

    /** One generating distribution and a name for it. */
    private class Truth(val label: String, val distribution: MetalogDistribution)

    private fun generatingDistributions(): List<Truth> = listOf(
        Truth("2-term unbounded", Metalog2P(10.0, 2.0)),
        Truth("3-term unbounded", Metalog3P(10.0, 2.0, 0.6)),
        Truth("4-term unbounded", Metalog4P(10.0, 2.0, 0.6, 1.5)),
        Truth("3-term lower bounded", Metalog3P(1.4, 0.35, 0.12, lowerBound = 2.0)),
        Truth("4-term lower bounded", Metalog4P(1.4, 0.35, 0.12, 0.4, lowerBound = 2.0)),
        Truth("5-term bounded", Metalog5P(0.2, 0.5, 0.1, 0.3, -0.15, lowerBound = 0.0, upperBound = 25.0)),
    )

    /**
     *  The largest gap in probability between two distributions, evaluated on a grid of the first
     *  one's quantiles. This is the Kolmogorov distance, and it is the right measure here because
     *  it does not care how a distribution is parameterized, only how it behaves.
     */
    private fun kolmogorovDistance(fitted: MetalogDistribution, truth: MetalogDistribution): Double {
        var largest = 0.0
        var p = 0.005
        while (p < 1.0) {
            largest = max(largest, abs(fitted.cdf(truth.invCDF(p)) - p))
            p += 0.005
        }
        return largest
    }

    // One provider for the whole suite, with small stream numbers. A provider materializes every
    // stream up to the number asked for, so a large stream number is expensive rather than merely
    // arbitrary.
    private val provider = RNStreamProvider()

    private fun sample(truth: MetalogDistribution, size: Int, streamNumber: Int): DoubleArray {
        val rv = truth.randomVariable(streamNumber, provider)
        return DoubleArray(size) { rv.value }
    }

    /** The outcome of one fit of one sample. */
    private class Outcome(
        val topIsMetalog: Boolean,
        val topRvType: RVParametersTypeIfc,
        val topDistance: Double,
        val bestMetalogDistance: Double,
        val classicalRank: Int
    )

    private fun fitOnce(truth: MetalogDistribution, size: Int, streamNumber: Int): Outcome {
        val data = sample(truth, size, streamNumber)
        val estimators = PDFModeler.allEstimators + PDFModeler.metalogEstimators
        val results = PDFModeler(data).estimateAndEvaluateScores(estimators)
        val ranked = results.resultsSortedByScoring
        val top = ranked.first()
        val bestMetalog = ranked.first { it.distribution is MetalogDistribution }
        val classicalRank = ranked.indexOfFirst { it.distribution !is MetalogDistribution } + 1
        return Outcome(
            topIsMetalog = top.distribution is MetalogDistribution,
            topRvType = top.rvType,
            topDistance = if (top.distribution is MetalogDistribution) {
                kolmogorovDistance(top.distribution as MetalogDistribution, truth)
            } else {
                Double.NaN
            },
            bestMetalogDistance = kolmogorovDistance(bestMetalog.distribution as MetalogDistribution, truth),
            classicalRank = classicalRank
        )
    }

    // ---------------------------------------------------------------------------------------
    // The claims worth asserting
    // ---------------------------------------------------------------------------------------

    @Test
    fun theRecoveredDistributionIsCloseToTheGeneratingOne() {
        // The substantive claim. Recovery is judged by distributional distance, not by whether the
        // arity label came back, and it is judged across replications.
        val replications = 8
        val sampleSize = 1_000
        println("Recovery of the generating distribution, n = $sampleSize, $replications replications")
        println("  %-24s %10s %10s %10s".format("generating distribution", "median KS", "worst KS", "over 0.05"))

        var overallWorst = 0.0
        for (truth in generatingDistributions()) {
            val distances = (0 until replications).map { r ->
                fitOnce(truth.distribution, sampleSize, 11 + r).bestMetalogDistance
            }.sorted()
            val median = distances[distances.size / 2]
            val worst = distances.last()
            val exceeding = distances.count { it > 0.05 }
            println("  %-24s %10.4f %10.4f %10d".format(truth.label, median, worst, exceeding))
            overallWorst = max(overallWorst, worst)

            // A Kolmogorov distance of 0.05 at a thousand observations is roughly the sampling
            // noise floor: the critical value of the one-sample test at the 0.05 level is 0.043,
            // so a fit this close is not distinguishable from the truth by the data itself.
            assertTrue(
                median < 0.05,
                "${truth.label}: the median recovered distribution was $median away from the truth"
            )
        }
        println("  worst across every case: %.4f".format(overallWorst))
    }

    @Test
    fun moreDataRecoversTheGeneratingDistributionMoreClosely() {
        // Consistency. If the recovered distance did not fall with the sample size, the fit would
        // be converging to something other than the truth, which no amount of closeness at a
        // single sample size would reveal.
        val truth = Metalog4P(10.0, 2.0, 0.6, 1.5)
        println("Recovery against sample size, 5 replications each")
        val averages = mutableListOf<Pair<Int, Double>>()
        for (size in intArrayOf(100, 500, 2_500, 12_500)) {
            val distances = (0 until 5).map { r ->
                fitOnce(truth, size, 31 + r).bestMetalogDistance
            }
            val average = distances.average()
            averages.add(size to average)
            println("  n = %6d  average KS = %.4f".format(size, average))
        }
        val smallest = averages.first().second
        val largest = averages.last().second
        assertTrue(
            largest < smallest,
            "the recovered distance did not improve with more data: $averages"
        )
        assertTrue(
            largest < 0.02,
            "even at ${averages.last().first} observations the fit was $largest away from the truth"
        )
    }

    @Test
    fun theMetalogFamilyOutranksTheClassicalFamiliesOnMetalogData() {
        // The other half of the question. On data that really did come from a metalog, the family
        // should win the ranking. If it did not, the estimators would be failing to exploit
        // information that is genuinely there.
        val replications = 6
        val sampleSize = 1_000
        var wins = 0
        var total = 0
        println("Whether a metalog tops the ranking on metalog data, n = $sampleSize")
        for (truth in generatingDistributions()) {
            var caseWins = 0
            val ranksOfBestClassical = mutableListOf<Int>()
            for (r in 0 until replications) {
                val outcome = fitOnce(truth.distribution, sampleSize, 51 + r)
                if (outcome.topIsMetalog) caseWins++
                ranksOfBestClassical.add(outcome.classicalRank)
                total++
            }
            wins += caseWins
            println("  %-24s metalog top in %d of %d; best classical ranked %s"
                .format(truth.label, caseWins, replications, ranksOfBestClassical))
        }
        println("  overall: $wins of $total")
        assertTrue(
            wins.toDouble() / total >= 0.75,
            "a metalog topped the ranking in only $wins of $total runs on metalog data"
        )
    }

    // ---------------------------------------------------------------------------------------
    // Measured and reported, deliberately not asserted
    // ---------------------------------------------------------------------------------------

    @Test
    fun labelRecoveryIsMeasuredRatherThanAssumed() {
        // How often the exact generating arity comes back on top. This is reported rather than
        // asserted because a lower arity winning is the statistically correct outcome when the
        // extra terms buy nothing: the criteria include a parameter penalty, and the arities nest.
        // The number is here so that nobody has to guess at it, and so that a change in it is
        // visible.
        // Measured at two sample sizes, because the two explanations for poor label recovery have
        // opposite consequences. If the arities are simply not separable at a practical sample
        // size, recovery improves with more data. If something is systematically biased, it does
        // not. Only the second would be a defect.
        for ((sampleSize, replications) in listOf(1_000 to 6, 20_000 to 3)) {
            println("Arity of the top-ranked metalog, by generating distribution (n = $sampleSize)")
            for (truth in generatingDistributions()) {
                val types = (0 until replications).map { r ->
                    val data = sample(truth.distribution, sampleSize, 71 + r)
                    val results = PDFModeler(data)
                        .estimateAndEvaluateScores(PDFModeler.metalogEstimators, automaticShifting = false)
                    val best = results.resultsSortedByScoring.first()
                    Pair(
                        (best.distribution as MetalogDistribution).numTerms,
                        best.distribution.boundedness
                    )
                }
                val trueTerms = truth.distribution.numTerms
                val trueBoundedness = truth.distribution.boundedness
                val exact = types.count { it.first == trueTerms && it.second == trueBoundedness }
                val rightBoundedness = types.count { it.second == trueBoundedness }
                val atLeastEnoughTerms = types.count { it.first >= trueTerms }
                println("  %-24s truth %d/%-12s exact %d/%d, boundedness %d/%d, terms>=truth %d/%d, saw %s"
                    .format(
                        truth.label, trueTerms, trueBoundedness, exact, replications,
                        rightBoundedness, replications, atLeastEnoughTerms, replications,
                        types.map { "${it.first}/${it.second}" }.distinct()
                    ))
            }
        }
        println(
            "  Read these as a description of the procedure, not a target. A smaller arity " +
                    "winning means the extra terms did not pay for themselves on that sample, " +
                    "which is what a parameter-penalized criterion is supposed to conclude."
        )
    }

    @Test
    fun theGeneratingDistributionIsNotRejectedByItsOwnData() {
        // A sanity check on the whole exercise. Whatever the ranking says, the true distribution
        // must itself be an acceptable fit to the data it generated, or something is wrong with
        // the generator, the cumulative function, or both.
        val sampleSize = 5_000
        val criticalValue = 1.36 / kotlin.math.sqrt(sampleSize.toDouble()) // 0.05 level
        for (truth in generatingDistributions()) {
            val data = sample(truth.distribution, sampleSize, 91)
            data.sort()
            var largestGap = 0.0
            for (i in data.indices) {
                val theoretical = truth.distribution.cdf(data[i])
                largestGap = max(
                    largestGap,
                    max(
                        abs(theoretical - i.toDouble() / sampleSize),
                        abs((i + 1.0) / sampleSize - theoretical)
                    )
                )
            }
            assertTrue(
                largestGap < criticalValue,
                "${truth.label}: its own sample rejected it, KS = $largestGap against $criticalValue"
            )
        }
    }

    @Test
    fun aFitToOneShapeDoesNotMatchAVeryDifferentShape() {
        // Guards against the distance measure being too forgiving to mean anything. If a metalog
        // fitted to one shape were close to an unrelated one, the recovery numbers above would be
        // meaningless.
        val left = Metalog3P(10.0, 2.0, 0.6)
        val right = Metalog3P(40.0, 6.0, -1.0)
        val distance = kolmogorovDistance(left, right)
        assertTrue(distance > 0.3, "two very different metalogs were only $distance apart")

        val data = sample(left, 1_000, 97)
        val estimator = MetalogParameterEstimatorFor(3, MetalogBoundedness.Unbounded)
        val result = PDFModeler(data).estimateParameters(estimator, automaticShifting = false)
        val fitted = PDFModeler.createDistribution(result.parameters!!) as MetalogDistribution
        assertTrue(
            kolmogorovDistance(fitted, left) < 0.06,
            "the fit did not recover the shape it was given"
        )
        assertTrue(
            kolmogorovDistance(fitted, right) > 0.3,
            "the fit was equally close to an unrelated shape, so the measure proves nothing"
        )
    }

    private fun MetalogParameterEstimatorFor(terms: Int, boundedness: MetalogBoundedness) =
        ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator(terms, boundedness)
}
