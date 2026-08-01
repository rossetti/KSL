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

package ksl.utilities.random.rvariable

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.distributions.LogLogistic
import ksl.utilities.distributions.Logistic
import ksl.utilities.distributions.metalog.Metalog2P
import ksl.utilities.distributions.metalog.Metalog3P
import ksl.utilities.distributions.metalog.Metalog4P
import ksl.utilities.distributions.metalog.Metalog5P
import ksl.utilities.distributions.metalog.Metalog6P
import ksl.utilities.distributions.metalog.MetalogBoundedness
import ksl.utilities.distributions.metalog.MetalogDistribution
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.metalog.Metalog2PRV
import ksl.utilities.random.rvariable.metalog.Metalog4PRV
import ksl.utilities.statistic.Statistic
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Evidence that a metalog random variable generates variates from the distribution it claims.
 *
 *  There is a circularity to be careful of. A metalog random variable generates by evaluating the
 *  quantile function at a uniform, and the distribution's cumulative function is a numerical
 *  inversion of that same quantile function. Comparing one against the other therefore checks
 *  that inversion is self-consistent, which is worth knowing but would not detect an error in the
 *  quantile function itself, since such an error would cancel.
 *
 *  This suite breaks the circle in two steps.
 *
 *  First, two members of the family are *exactly* equal to distributions the KSL already
 *  implements independently, and the identities are checked at the level of individual variates
 *  rather than in distribution. An unbounded two-term metalog is the logistic distribution, and a
 *  lower-bounded two-term metalog is a shifted log-logistic. If the metalog quantile function were
 *  wrong, these would disagree.
 *
 *  Second, with the quantile function anchored, the remaining question is whether generation
 *  actually follows it. That is answered for every arity and every member of the family by formal
 *  goodness-of-fit tests on large samples, together with order statistics and moments.
 */
class MetalogGenerationValidationTest {

    private val provider = RNStreamProvider()

    // ---------------------------------------------------------------------------------------
    // Independent anchors: exact identities against distributions implemented elsewhere
    // ---------------------------------------------------------------------------------------

    @Test
    fun theUnboundedTwoTermMetalogIsExactlyTheLogisticDistribution() {
        val location = 12.0
        val scale = 3.0
        val metalog = Metalog2P(location, scale)
        val logistic = Logistic(location, scale)

        for (p in doubleArrayOf(1e-6, 0.001, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 0.999, 1 - 1e-6)) {
            assertEquals(logistic.invCDF(p), metalog.invCDF(p), 1e-9, "quantile at p = $p")
        }
        for (x in doubleArrayOf(-20.0, -5.0, 0.0, 6.0, 12.0, 18.0, 30.0, 60.0)) {
            assertEquals(logistic.cdf(x), metalog.cdf(x), 1e-9, "cdf at x = $x")
            assertEquals(logistic.pdf(x), metalog.pdf(x), 1e-9, "pdf at x = $x")
        }
    }

    @Test
    fun theTwoTermMetalogGeneratesTheSameVariatesAsTheLogisticRandomVariable() {
        // Both are inverse transform on the same stream, so agreement is variate by variate, not
        // merely in distribution. This is the strongest available check that generation applies
        // the intended quantile function to the intended uniform.
        val location = 12.0
        val scale = 3.0
        val metalogRV = Metalog2PRV(location, scale, streamNum = 101, streamProvider = RNStreamProvider())
        val logisticRV = LogisticRV(location, scale, streamNum = 101, streamProvider = RNStreamProvider())

        var largestDifference = 0.0
        repeat(20_000) {
            val fromMetalog = metalogRV.value
            val fromLogistic = logisticRV.value
            largestDifference = max(largestDifference, abs(fromMetalog - fromLogistic))
        }
        assertTrue(
            largestDifference < 1e-9,
            "metalog and logistic variates diverged by $largestDifference on the same stream"
        )
    }

    @Test
    fun theLowerBoundedTwoTermMetalogIsExactlyAShiftedLogLogistic() {
        // A lower-bounded metalog is bl + exp(M). With two terms, exp(a1 + a2 L) = exp(a1) exp(a2 L),
        // which is the log-logistic quantile scale (p/(1-p))^(1/shape) with scale = exp(a1) and
        // shape = 1/a2.
        val a1 = 1.5
        val a2 = 0.4
        val shift = 7.0
        val metalog = Metalog2P(a1, a2, lowerBound = shift)
        val logLogistic = LogLogistic(shape = 1.0 / a2, scale = exp(a1))

        for (p in doubleArrayOf(1e-5, 0.001, 0.05, 0.25, 0.5, 0.75, 0.95, 0.999, 1 - 1e-5)) {
            val expected = shift + logLogistic.invCDF(p)
            assertEquals(expected, metalog.invCDF(p), 1e-8 * max(1.0, abs(expected)), "quantile at p = $p")
        }
        for (x in doubleArrayOf(7.5, 9.0, 12.0, 20.0, 50.0, 200.0)) {
            assertEquals(logLogistic.cdf(x - shift), metalog.cdf(x), 1e-8, "cdf at x = $x")
        }
    }

    @Test
    fun theLowerBoundedTwoTermMetalogGeneratesTheSameVariatesAsTheLogLogistic() {
        val a1 = 1.5
        val a2 = 0.4
        val metalogRV = Metalog2PRV(a1, a2, lowerBound = 0.0, streamNum = 103, streamProvider = RNStreamProvider())
        val logLogisticRV = LogLogisticRV(
            shape = 1.0 / a2, scale = exp(a1), streamNum = 103, streamProvider = RNStreamProvider()
        )
        var largestRelative = 0.0
        repeat(20_000) {
            val fromMetalog = metalogRV.value
            val fromLogLogistic = logLogisticRV.value
            largestRelative = max(
                largestRelative,
                abs(fromMetalog - fromLogLogistic) / max(1.0, abs(fromLogLogistic))
            )
        }
        assertTrue(
            largestRelative < 1e-9,
            "metalog and log-logistic variates diverged by $largestRelative relative on the same stream"
        )
    }

    @Test
    fun theUpperBoundedMetalogIsTheReflectionOfTheLowerBoundedOne() {
        // The lower-bounded member is bl + exp(M) and the upper-bounded one is bu - exp(-M), so
        // reflecting one through zero gives the other. The coefficients do not carry over
        // unchanged, though. Reflection sends p to 1 - p, and the basis terms are not all
        // symmetric under that: the logit and the centred probability both change sign, so
        // M(1 - p) = a1 - a2 L + a3 cL - a4 c. Matching that against -M(p) requires the sign of
        // every term that is *even* under the reflection to be flipped, which for three terms is
        // a1 and a3. Getting this wrong is easy, so it is asserted rather than assumed.
        val a1 = 1.2
        val a2 = 0.3
        val a3 = 0.05
        val upper = Metalog3P(a1, a2, a3, upperBound = 0.0)
        val lower = Metalog3P(-a1, a2, -a3, lowerBound = 0.0)
        for (p in doubleArrayOf(0.001, 0.05, 0.25, 0.5, 0.75, 0.95, 0.999)) {
            assertEquals(-lower.invCDF(1.0 - p), upper.invCDF(p), 1e-8, "at p = $p")
        }
        // The symmetric case, where the reflection is its own mirror and no sign change is needed.
        val symmetricLower = Metalog2P(0.0, a2, lowerBound = 0.0)
        val symmetricUpper = Metalog2P(0.0, a2, upperBound = 0.0)
        for (p in doubleArrayOf(0.01, 0.25, 0.5, 0.75, 0.99)) {
            assertEquals(-symmetricLower.invCDF(1.0 - p), symmetricUpper.invCDF(p), 1e-8, "at p = $p")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Distributional tests across every arity and every member of the family
    // ---------------------------------------------------------------------------------------

    /** Twenty feasible cases: five arities by the four members of the family. */
    private fun everyCase(): List<Pair<String, MetalogDistribution>> {
        val coefficients = mapOf(
            2 to doubleArrayOf(1.0, 0.4),
            3 to doubleArrayOf(1.0, 0.4, 0.1),
            4 to doubleArrayOf(1.0, 0.4, 0.1, 0.15),
            5 to doubleArrayOf(1.0, 0.4, 0.1, 0.15, -0.05),
            6 to doubleArrayOf(1.0, 0.4, 0.1, 0.15, -0.05, 0.08),
        )
        val cases = mutableListOf<Pair<String, MetalogDistribution>>()
        for ((terms, a) in coefficients) {
            for (boundedness in MetalogBoundedness.entries) {
                val lower = if (boundedness.hasLowerBound) 0.0 else Double.NEGATIVE_INFINITY
                val upper = if (boundedness.hasUpperBound) 40.0 else Double.POSITIVE_INFINITY
                val distribution = when (terms) {
                    2 -> Metalog2P(a[0], a[1], lower, upper)
                    3 -> Metalog3P(a[0], a[1], a[2], lower, upper)
                    4 -> Metalog4P(a[0], a[1], a[2], a[3], lower, upper)
                    5 -> Metalog5P(a[0], a[1], a[2], a[3], a[4], lower, upper)
                    else -> Metalog6P(a[0], a[1], a[2], a[3], a[4], a[5], lower, upper)
                }
                cases.add("${terms}-term $boundedness" to distribution)
            }
        }
        return cases
    }

    @Test
    fun everyVariantPassesAChiSquaredGoodnessOfFitTestAgainstItsOwnDistribution() {
        // Equiprobable bins built from the quantile function, so the expected count per bin is
        // exactly n / bins and no bin is sparse. Tested at the 0.001 level: with twenty cases a
        // 0.05 level would produce a false failure roughly once every run.
        val sampleSize = 100_000
        val bins = 50
        val expectedPerBin = sampleSize.toDouble() / bins
        val criticalValue = ChiSquaredDistribution((bins - 1).toDouble()).invCDF(0.999)

        var streamNumber = 200
        val report = StringBuilder()
        var worst = 0.0
        var worstLabel = ""
        for ((label, distribution) in everyCase()) {
            val edges = DoubleArray(bins - 1) { distribution.invCDF((it + 1).toDouble() / bins) }
            val counts = IntArray(bins)
            val rv = distribution.randomVariable(streamNumber++, provider)
            repeat(sampleSize) {
                val x = rv.value
                var bin = edges.binarySearch(x)
                if (bin < 0) bin = -(bin + 1)
                counts[bin]++
            }
            var statistic = 0.0
            for (count in counts) {
                val deviation = count - expectedPerBin
                statistic += deviation * deviation / expectedPerBin
            }
            report.appendLine("  %-24s chi-squared = %8.2f".format(label, statistic))
            if (statistic > worst) {
                worst = statistic
                worstLabel = label
            }
            assertTrue(
                statistic < criticalValue,
                "$label: chi-squared $statistic exceeded the 0.001 critical value $criticalValue"
            )
        }
        println(
            ("Chi-squared goodness of fit, n = $sampleSize, $bins bins, " +
                    "critical value %.2f at the 0.001 level, %d degrees of freedom")
                .format(criticalValue, bins - 1)
        )
        print(report)
        println("  worst: $worstLabel at %.2f".format(worst))
    }

    @Test
    fun everyVariantPassesAKolmogorovSmirnovTestAgainstItsOwnDistribution() {
        // The Kolmogorov critical value at the 0.001 level is about 1.95 / sqrt(n) for large n.
        val sampleSize = 20_000
        val criticalValue = 1.95 / sqrt(sampleSize.toDouble())

        var streamNumber = 400
        var worst = 0.0
        var worstLabel = ""
        for ((label, distribution) in everyCase()) {
            val rv = distribution.randomVariable(streamNumber++, provider)
            val sample = DoubleArray(sampleSize) { rv.value }
            sample.sort()
            var largestGap = 0.0
            for (i in 0 until sampleSize) {
                val theoretical = distribution.cdf(sample[i])
                val below = i.toDouble() / sampleSize
                val above = (i + 1).toDouble() / sampleSize
                largestGap = max(largestGap, max(abs(theoretical - below), abs(above - theoretical)))
            }
            if (largestGap > worst) {
                worst = largestGap
                worstLabel = label
            }
            assertTrue(
                largestGap < criticalValue,
                "$label: KS statistic $largestGap exceeded the critical value $criticalValue"
            )
        }
        println(
            ("Kolmogorov-Smirnov, n = $sampleSize, critical value %.5f at the 0.001 level; " +
                    "worst was $worstLabel at %.5f").format(criticalValue, worst)
        )
    }

    @Test
    fun theOrderStatisticsOfTheSampleTrackTheQuantileFunction() {
        // A different view of the same question: rather than the largest gap anywhere, check the
        // quantiles a modeller actually reads, including well into both tails.
        val sampleSize = 200_000
        val probabilities = doubleArrayOf(0.001, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 0.999)
        var streamNumber = 600
        for ((label, distribution) in everyCase()) {
            val rv = distribution.randomVariable(streamNumber++, provider)
            val sample = DoubleArray(sampleSize) { rv.value }
            sample.sort()
            for (p in probabilities) {
                val empirical = sample[(p * sampleSize).toInt()]
                val theoretical = distribution.invCDF(p)
                // Compared through the cumulative function, so the tolerance is in probability and
                // means the same thing everywhere, rather than in units that vary by many orders
                // of magnitude between the middle and the tails.
                val gap = abs(distribution.cdf(empirical) - p)
                assertTrue(
                    gap < 0.01,
                    "$label at p = $p: the sample quantile $empirical sits at probability " +
                            "${distribution.cdf(empirical)}, theoretical quantile $theoretical"
                )
            }
        }
    }

    @Test
    fun theSampleMomentsMatchTheTheoreticalOnesWhereTheyExist() {
        // Only where the distribution says its moments are trustworthy. A semi-bounded metalog
        // exponentiates its quantile function, so a heavy enough tail leaves the mean or the
        // variance undefined, and a sample average would then be estimating nothing.
        val sampleSize = 500_000
        var streamNumber = 800
        var checked = 0
        for ((label, distribution) in everyCase()) {
            val stream = streamNumber++
            if (!distribution.momentsAreReliable(order = 2)) {
                continue
            }
            val rv = distribution.randomVariable(stream, provider)
            val statistics = Statistic(DoubleArray(sampleSize) { rv.value })
            val standardError = sqrt(distribution.variance() / sampleSize)
            assertTrue(
                abs(statistics.average - distribution.mean()) < 5.0 * standardError,
                "$label: sample average ${statistics.average} against a theoretical mean of " +
                        "${distribution.mean()}, standard error $standardError"
            )
            // The variance is compared loosely, since its own sampling error depends on the
            // fourth moment, which for these shapes is large.
            val ratio = statistics.variance / distribution.variance()
            assertTrue(
                (ratio > 0.9) && (ratio < 1.1),
                "$label: sample variance ${statistics.variance} against a theoretical " +
                        "${distribution.variance()}, ratio $ratio"
            )
            checked++
        }
        println("Moments compared for $checked of ${everyCase().size} cases; " +
                "the rest report their moments as unreliable and were skipped")
        assertTrue(checked >= 5, "too few cases had usable moments to make this test meaningful")
    }

    @Test
    fun theGeneratedUniformsAreRecoveredByTheCumulativeFunction() {
        // Inverse transform means cdf(generate(u)) should return u. Applied to the raw stream, this
        // separates an error in generation from an error in inversion: both would have to be wrong
        // in exactly compensating ways to pass.
        val stream = provider.rnStream(900)
        val distribution = Metalog5P(1.0, 0.4, 0.1, 0.15, -0.05, lowerBound = 0.0)
        var worst = 0.0
        repeat(5_000) {
            val u = stream.randU01()
            val x = distribution.invCDF(u)
            worst = max(worst, abs(distribution.cdf(x) - u))
        }
        assertTrue(worst < 1e-8, "the round trip through the quantile and cumulative functions was off by $worst")
    }

    @Test
    fun aSampleFromOneMemberIsRejectedByTheOthers() {
        // A guard against the tests above passing for a trivial reason. If the goodness-of-fit
        // machinery could not tell these distributions apart, it would accept anything.
        val sampleSize = 20_000
        val criticalValue = 1.95 / sqrt(sampleSize.toDouble())
        val truth = Metalog3P(1.0, 0.4, 0.3, lowerBound = 0.0)
        val impostor = Metalog3P(1.0, 0.4, -0.3, lowerBound = 0.0)

        val rv = truth.randomVariable(950, provider)
        val sample = DoubleArray(sampleSize) { rv.value }
        sample.sort()

        fun ksAgainst(distribution: MetalogDistribution): Double {
            var largest = 0.0
            for (i in 0 until sampleSize) {
                val theoretical = distribution.cdf(sample[i])
                largest = max(
                    largest,
                    max(abs(theoretical - i.toDouble() / sampleSize), abs((i + 1.0) / sampleSize - theoretical))
                )
            }
            return largest
        }

        val againstTruth = ksAgainst(truth)
        val againstImpostor = ksAgainst(impostor)
        println(
            ("KS against the generating distribution %.5f, against a mis-signed one %.5f, " +
                    "critical value %.5f").format(againstTruth, againstImpostor, criticalValue)
        )
        assertTrue(againstTruth < criticalValue, "the truth was rejected at $againstTruth")
        assertTrue(
            againstImpostor > criticalValue,
            "a distribution with the skewness coefficient reversed was not rejected, " +
                    "so this suite cannot distinguish anything"
        )
    }

    @Test
    fun theQuantileFunctionAgreesWithItsOwnDefinitionAtTheGeneratedPoints() {
        // The metalog is defined by its quantile function. Evaluate that definition directly here,
        // independently of MetalogFunctions, and compare against what the random variable produced.
        val a = doubleArrayOf(1.0, 0.4, 0.1, 0.15)
        val distribution = Metalog4P(a[0], a[1], a[2], a[3])
        val stream = provider.rnStream(970)
        val rvStream = RNStreamProvider().rnStream(970)
        val rv = Metalog4PRV(a[0], a[1], a[2], a[3], streamNum = 970, streamProvider = RNStreamProvider())
        repeat(2_000) {
            val u = stream.randU01()
            // Keelin's M_4(y) = a1 + a2 L + a3 c L + a4 c, with L the logit and c = y - 0.5.
            val logit = ln(u / (1.0 - u))
            val centered = u - 0.5
            val byDefinition = a[0] + a[1] * logit + a[2] * centered * logit + a[3] * centered
            assertEquals(byDefinition, distribution.invCDF(u), 1e-9 * max(1.0, abs(byDefinition)))
            assertEquals(byDefinition, rv.value, 1e-9 * max(1.0, abs(byDefinition)))
            rvStream.randU01()
        }
    }
}
