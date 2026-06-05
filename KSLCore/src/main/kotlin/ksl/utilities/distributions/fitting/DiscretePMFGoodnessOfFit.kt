package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.*

open class DiscretePMFGoodnessOfFit(
    data: DoubleArray,
    final override val distribution: DiscretePMFInRangeDistributionIfc,
    numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray = PMFModeler.makeZeroToInfinityBreakPoints(data.size, distribution),
) : DistributionGOF(data, numEstimatedParameters, breakPoints), DiscreteDistributionGOFIfc {

    final override val binProbabilities: DoubleArray = histogram.binProbabilities(distribution)

    final override val expectedCounts: DoubleArray = histogram.expectedCounts(distribution)

    override fun toString(): String {
        val sb = StringBuilder().apply {
            append(chiSquaredTestResults())
        }
        return sb.toString()
    }

    companion object {

        /**
         * Computes the upper, lower, and two-sided p-values for the Poisson
         * variance test statistic T, referred to a chi-squared distribution with
         * n−1 degrees of freedom. The upper p-value (P(chi-squared >= T)) tests for
         * overdispersion, the lower (P(chi-squared <= T)) for underdispersion, and
         * the two-sided is 2*min(upper, lower). Degenerate inputs (NaN/infinite T,
         * or a sample size less than two) yield NaN p-values.
         *
         * This is the single source of truth for the dispersion p-values shown in
         * the discrete report (both the data-summary and per-fit sections) and
         * carried on the result DTO.
         *
         * @param testStatistic the Poisson variance test statistic T
         * @param sampleSize    the number of observations
         * @return a triple of (upper, lower, two-sided) p-values
         */
        fun poissonDispersionPValues(
            testStatistic: Double,
            sampleSize: Double
        ): Triple<Double, Double, Double> {
            if (testStatistic.isNaN() || testStatistic.isInfinite() || sampleSize < 2.0) {
                return Triple(Double.NaN, Double.NaN, Double.NaN)
            }
            val chi = ChiSquaredDistribution(maxOf(1.0, sampleSize - 1.0))
            val upper = chi.complementaryCDF(testStatistic)
            val lower = chi.cdf(testStatistic)
            return Triple(upper, lower, 2.0 * minOf(upper, lower))
        }

        /**
         * Computes the full Poisson dispersion test from the sample moments: the
         * index of dispersion (Var/Mean), the test statistic T = (n−1)·Var/Mean,
         * its degrees of freedom (n−1), and the three p-values (via
         * [poissonDispersionPValues]). A zero mean yields NaN for the index and T.
         *
         * @param mean       the sample average
         * @param variance   the sample variance
         * @param sampleSize the number of observations
         */
        fun poissonDispersionTest(
            mean: Double,
            variance: Double,
            sampleSize: Double
        ): PoissonDispersionResult {
            val iod = if (mean == 0.0) Double.NaN else variance / mean
            val t = if (mean == 0.0) Double.NaN else (sampleSize - 1.0) * variance / mean
            val (upper, lower, two) = poissonDispersionPValues(t, sampleSize)
            return PoissonDispersionResult(
                iod, t, maxOf(1, (sampleSize - 1.0).toInt()), upper, lower, two
            )
        }
    }
}

/**
 * The result of a Poisson dispersion test (see
 * [DiscretePMFGoodnessOfFit.poissonDispersionTest]): the index of dispersion,
 * the test statistic T, its degrees of freedom (n−1), and the upper, lower,
 * and two-sided p-values referred to a chi-squared distribution.
 */
data class PoissonDispersionResult(
    val indexOfDispersion: Double,
    val testStatistic: Double,
    val degreesOfFreedom: Int,
    val upperPValue: Double,
    val lowerPValue: Double,
    val twoSidedPValue: Double
)

fun main() {
 //    testPoisson()

     tesNegBinomial()

 //   testBinomial()
}

fun testPoisson() {
    val dist = Poisson(5.0)
    val rv = dist.randomVariable()
    rv.advanceToNextSubStream()
    val data = rv.sample(200)
    var breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
    val pf = DiscretePMFGoodnessOfFit(data, dist, breakPoints = breakPoints)
    println()
    println(pf.chiSquaredTestResults())
}

fun tesNegBinomial() {
    val dist = NegativeBinomial(0.2, numSuccesses = 4.0)
    val rv = dist.randomVariable()
    rv.advanceToNextSubStream()
    val data = rv.sample(200)
    val breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
    val pf = DiscretePMFGoodnessOfFit(data, dist, numEstimatedParameters = 2, breakPoints = breakPoints)
    println(pf.chiSquaredTestResults())
}

fun testBinomial() {
    val dist = Binomial(0.2, nTrials = 100)
    val rv = dist.randomVariable()
    rv.advanceToNextSubStream()
    val data = rv.sample(200)
    var breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
    println("break points: ")
    println(breakPoints.joinToString())
    val pf = DiscretePMFGoodnessOfFit(data, dist, breakPoints = breakPoints)
    println("constructed goodness of fit")
    println(pf.chiSquaredTestResults())
}