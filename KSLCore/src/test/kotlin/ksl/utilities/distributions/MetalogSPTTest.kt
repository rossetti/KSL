package ksl.utilities.distributions

import ksl.utilities.distributions.metalog.Metalog3P
import ksl.utilities.distributions.metalog.MetalogFeasibilityChecker
import ksl.utilities.distributions.metalog.MetalogFunctions
import org.hipparchus.linear.MatrixUtils
import org.hipparchus.linear.QRDecomposition
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  The symmetric percentile triplet parameterization of Keelin (2016), Propositions 1 through 4.
 *  Three terms fit three points exactly, so the coefficients follow in closed form from the three
 *  assessed quantiles with no least squares step. This is the decision-analysis workflow, where an
 *  expert supplies a low, a median, and a high quantile.
 */
class MetalogSPTTest {

    private fun assertNearly(
        expected: Double,
        actual: Double,
        relTol: Double = 1e-10,
        absTol: Double = 1e-10,
        message: String = "",
    ) {
        val diff = abs(expected - actual)
        val threshold = max(absTol, relTol * max(1.0, abs(expected)))
        assertTrue(
            diff <= threshold,
            "$message Expected $expected, actual $actual, diff $diff, threshold $threshold",
        )
    }

    /** Solves the three-by-three system directly, as an independent route to the coefficients. */
    private fun solveExactly(values: DoubleArray, probabilities: DoubleArray): DoubleArray {
        val design = MatrixUtils.createRealMatrix(
            MetalogFunctions.designMatrix(probabilities, 3)
        )
        return QRDecomposition(design).solver
            .solve(MatrixUtils.createRealVector(values)).toArray()
    }

    // -------- Proposition 1, the unbounded case --------

    @Test
    fun theClosedFormMatchesTheReferenceCoefficients() {
        val a = Metalog3P.sptCoefficients(3.0, 10.0, 30.0, alpha = 0.1)
        assertNearly(10.0, a[0])
        assertNearly(6.144114779731152, a[1])
        assertNearly(7.395693716343115, a[2])
    }

    @Test
    fun theClosedFormMatchesSolvingTheSystemDirectly() {
        val alpha = 0.1
        val values = doubleArrayOf(3.0, 10.0, 30.0)
        val probabilities = doubleArrayOf(alpha, 0.5, 1.0 - alpha)
        val closedForm = Metalog3P.sptCoefficients(values[0], values[1], values[2], alpha)
        val solved = solveExactly(values, probabilities)
        for (i in 0..2) {
            assertNearly(solved[i], closedForm[i], message = "a${i + 1}:")
        }
    }

    @Test
    fun theClosedFormMatchesSolvingTheSystemForOtherOffsets() {
        for (alpha in doubleArrayOf(0.05, 0.1, 0.25, 0.4)) {
            val values = doubleArrayOf(-4.0, 1.0, 9.0)
            val probabilities = doubleArrayOf(alpha, 0.5, 1.0 - alpha)
            val closedForm = Metalog3P.sptCoefficients(values[0], values[1], values[2], alpha)
            val solved = solveExactly(values, probabilities)
            for (i in 0..2) {
                assertNearly(solved[i], closedForm[i], message = "alpha $alpha, a${i + 1}:")
            }
        }
    }

    @Test
    fun theConstructedDistributionReproducesTheTriplet() {
        val alpha = 0.1
        val d = Metalog3P.fromSPT(3.0, 10.0, 30.0, alpha)
        assertNearly(3.0, d.invCDF(alpha), relTol = 1e-9, absTol = 1e-9)
        assertNearly(10.0, d.invCDF(0.5), relTol = 1e-9, absTol = 1e-9)
        assertNearly(30.0, d.invCDF(1.0 - alpha), relTol = 1e-9, absTol = 1e-9)
    }

    @Test
    fun theReferenceInteriorValuesMatch() {
        val d = Metalog3P.fromSPT(3.0, 10.0, 30.0, alpha = 0.1)
        assertNearly(5.281249999999983, d.invCDF(0.25), relTol = 1e-9, absTol = 1e-9)
        assertNearly(0.06764669956401198, d.pdf(d.invCDF(0.25)), relTol = 1e-7, absTol = 1e-9)
    }

    @Test
    fun theFirstCoefficientIsAlwaysTheMedian() {
        val a = Metalog3P.sptCoefficients(1.0, 4.0, 100.0, alpha = 0.1)
        assertNearly(4.0, a[0])
    }

    @Test
    fun aSymmetricTripletProducesALogistic() {
        // A median exactly midway between the outer quantiles leaves no skewness, so the third
        // coefficient vanishes and the result is the two-term logistic.
        val d = Metalog3P.fromSPT(0.0, 10.0, 20.0, alpha = 0.1)
        assertNearly(0.0, d.a3)
        val logistic = Logistic(d.a1, d.a2)
        for (p in doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)) {
            assertNearly(logistic.invCDF(p), d.invCDF(p), message = "at p = $p:")
        }
    }

    @Test
    fun aMedianNearerTheLowQuantileSkewsRight() {
        val right = Metalog3P.fromSPT(0.0, 5.0, 20.0, alpha = 0.1)
        val left = Metalog3P.fromSPT(0.0, 15.0, 20.0, alpha = 0.1)
        assertTrue(right.a3 > 0.0, "the third coefficient was ${right.a3}")
        assertTrue(left.a3 < 0.0, "the third coefficient was ${left.a3}")
        assertTrue(right.skewness > 0.0, "the skewness was ${right.skewness}")
        assertTrue(left.skewness < 0.0, "the skewness was ${left.skewness}")
    }

    // -------- Proposition 2, the feasibility limit --------

    @Test
    fun feasibilityMatchesTheClosedFormLimitOnTheMedianPosition() {
        // With the outer quantiles at zero and one, the median is the position within the range
        // that Keelin calls r. Proposition 2 makes the triplet feasible exactly when r lies
        // strictly between k and one minus k.
        val alpha = 0.1
        val k = 0.5 * (1.0 - MetalogFeasibilityChecker.THREE_TERM_RATIO_LIMIT * (0.5 - alpha))
        assertNearly(0.166578, k, relTol = 0.0, absTol = 1e-5)
        for (r in doubleArrayOf(0.2, 0.35, 0.5, 0.65, 0.8)) {
            assertTrue(
                Metalog3P.isFeasibleSPT(0.0, r, 1.0, alpha),
                "r = $r should be feasible, since it lies inside $k to ${1.0 - k}",
            )
        }
        for (r in doubleArrayOf(0.02, 0.10, 0.16, 0.84, 0.90, 0.98)) {
            assertFalse(
                Metalog3P.isFeasibleSPT(0.0, r, 1.0, alpha),
                "r = $r should be infeasible, since it lies outside $k to ${1.0 - k}",
            )
        }
    }

    @Test
    fun feasibilityIsSymmetricInTheMedianPosition() {
        for (r in doubleArrayOf(0.05, 0.17, 0.3, 0.5)) {
            assertTrue(
                Metalog3P.isFeasibleSPT(0.0, r, 1.0) ==
                        Metalog3P.isFeasibleSPT(0.0, 1.0 - r, 1.0),
                "feasibility differed between r = $r and its mirror",
            )
        }
    }

    @Test
    fun theFeasibleRangeNarrowsAsTheOffsetApproachesOneHalf() {
        // The band on the median position is k to one minus k, where k grows toward one half as the
        // offset does. So a triplet assessed at more extreme percentiles admits a more off-center
        // median, and one assessed close to the median admits almost none: as the three assessed
        // probabilities converge, the median has to sit nearly midway between the outer quantiles
        // or the implied density is inconsistent.
        assertTrue(Metalog3P.isFeasibleSPT(0.0, 0.14, 1.0, alpha = 0.05))
        assertFalse(Metalog3P.isFeasibleSPT(0.0, 0.14, 1.0, alpha = 0.1))
        assertFalse(Metalog3P.isFeasibleSPT(0.0, 0.14, 1.0, alpha = 0.45))
        // A centered median stays admissible at every offset.
        for (alpha in doubleArrayOf(0.02, 0.1, 0.25, 0.45)) {
            assertTrue(
                Metalog3P.isFeasibleSPT(0.0, 0.5, 1.0, alpha),
                "a centered median should be feasible at alpha = $alpha",
            )
        }
    }

    @Test
    fun anInfeasibleTripletIsRejectedByTheFactory() {
        assertFalse(Metalog3P.isFeasibleSPT(0.0, 0.02, 1.0, alpha = 0.1))
        assertFailsWith<IllegalArgumentException> {
            Metalog3P.fromSPT(0.0, 0.02, 1.0, alpha = 0.1)
        }
    }

    @Test
    fun thePredicateAgreesWithWhetherTheFactorySucceeds() {
        for (r in doubleArrayOf(0.05, 0.15, 0.166, 0.2, 0.5, 0.8, 0.85, 0.95)) {
            val predicted = Metalog3P.isFeasibleSPT(0.0, r, 1.0, alpha = 0.1)
            val succeeded = try {
                Metalog3P.fromSPT(0.0, r, 1.0, alpha = 0.1)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
            assertTrue(predicted == succeeded, "the predicate and the factory disagreed at r = $r")
        }
    }

    // -------- Proposition 3, the lower bounded case --------

    @Test
    fun theLowerBoundedClosedFormMatchesKeelinProposition3() {
        val alpha = 0.1
        val lowerBound = 2.0
        val qLower = 5.0
        val median = 9.0
        val qUpper = 40.0
        // Keelin's Proposition 3 expressed directly in terms of the shifted quantiles.
        val betaLower = qLower - lowerBound
        val betaMedian = median - lowerBound
        val betaUpper = qUpper - lowerBound
        val logOdds = ln((1.0 - alpha) / alpha)
        val expected1 = ln(betaMedian)
        val expected2 = 0.5 / logOdds * ln(betaUpper / betaLower)
        val expected3 = 1.0 / ((1.0 - 2.0 * alpha) * logOdds) *
                ln(betaUpper * betaLower / (betaMedian * betaMedian))
        val a = Metalog3P.sptCoefficients(qLower, median, qUpper, alpha, lowerBound = lowerBound)
        assertNearly(expected1, a[0], message = "a1:")
        assertNearly(expected2, a[1], message = "a2:")
        assertNearly(expected3, a[2], message = "a3:")
    }

    @Test
    fun theLowerBoundedDistributionReproducesTheTriplet() {
        val alpha = 0.1
        val d = Metalog3P.fromSPT(5.0, 9.0, 40.0, alpha, lowerBound = 2.0)
        assertNearly(5.0, d.invCDF(alpha), relTol = 1e-8, absTol = 1e-8)
        assertNearly(9.0, d.invCDF(0.5), relTol = 1e-8, absTol = 1e-8)
        assertNearly(40.0, d.invCDF(1.0 - alpha), relTol = 1e-8, absTol = 1e-8)
        assertNearly(2.0, d.lowerBound)
    }

    @Test
    fun aTripletBelowTheLowerBoundIsRejected() {
        assertFailsWith<IllegalArgumentException>("the low quantile is below the bound") {
            Metalog3P.fromSPT(1.0, 9.0, 40.0, lowerBound = 2.0)
        }
        assertFailsWith<IllegalArgumentException>("the low quantile sits on the bound") {
            Metalog3P.fromSPT(2.0, 9.0, 40.0, lowerBound = 2.0)
        }
    }

    @Test
    fun theLowerBoundedVariantAdmitsSkewnessTheUnboundedOneCannot() {
        // Keelin notes that adding a bound expands the reachable shapes, because the logarithm
        // compresses a long right tail and so moves the median position back inside the feasible
        // band. This triplet is strongly right skewed: unbounded it demands more skewness than
        // three terms can express, but with a lower bound of zero it is admissible.
        assertFalse(Metalog3P.isFeasibleSPT(1.0, 2.2, 100.0, alpha = 0.1))
        assertTrue(Metalog3P.isFeasibleSPT(1.0, 2.2, 100.0, alpha = 0.1, lowerBound = 0.0))
        // And the constructed distribution really does reproduce the triplet.
        val d = Metalog3P.fromSPT(1.0, 2.2, 100.0, alpha = 0.1, lowerBound = 0.0)
        assertNearly(1.0, d.invCDF(0.1), relTol = 1e-8, absTol = 1e-8)
        assertNearly(2.2, d.invCDF(0.5), relTol = 1e-8, absTol = 1e-8)
        assertNearly(100.0, d.invCDF(0.9), relTol = 1e-8, absTol = 1e-8)
    }

    // -------- Proposition 4, the bounded case --------

    @Test
    fun theBoundedClosedFormMatchesKeelinProposition4() {
        val alpha = 0.1
        val lowerBound = 0.0
        val upperBound = 1.0
        val qLower = 0.2
        val median = 0.4
        val qUpper = 0.75
        // Proposition 4 is Proposition 1 applied to the logit of the scaled quantiles.
        fun beta(q: Double) = (q - lowerBound) / (upperBound - q)
        val logOdds = ln((1.0 - alpha) / alpha)
        val expected1 = ln(beta(median))
        val expected2 = 0.5 / logOdds * ln(beta(qUpper) / beta(qLower))
        val expected3 = 1.0 / ((1.0 - 2.0 * alpha) * logOdds) *
                ln(beta(qUpper) * beta(qLower) / (beta(median) * beta(median)))
        val a = Metalog3P.sptCoefficients(
            qLower, median, qUpper, alpha, lowerBound = lowerBound, upperBound = upperBound
        )
        assertNearly(expected1, a[0], message = "a1:")
        assertNearly(expected2, a[1], message = "a2:")
        assertNearly(expected3, a[2], message = "a3:")
    }

    @Test
    fun theBoundedDistributionReproducesTheTripletAndStaysInside() {
        val alpha = 0.1
        val d = Metalog3P.fromSPT(0.2, 0.4, 0.75, alpha, lowerBound = 0.0, upperBound = 1.0)
        assertNearly(0.2, d.invCDF(alpha), relTol = 1e-8, absTol = 1e-8)
        assertNearly(0.4, d.invCDF(0.5), relTol = 1e-8, absTol = 1e-8)
        assertNearly(0.75, d.invCDF(1.0 - alpha), relTol = 1e-8, absTol = 1e-8)
        for (p in doubleArrayOf(1e-8, 0.3, 0.999999)) {
            val x = d.invCDF(p)
            assertTrue((x > 0.0) && (x < 1.0), "the value $x at p = $p left the bounds")
        }
    }

    @Test
    fun aTripletOutsideTheBoundsIsRejected() {
        assertFailsWith<IllegalArgumentException>("above the upper bound") {
            Metalog3P.fromSPT(0.2, 0.4, 1.5, lowerBound = 0.0, upperBound = 1.0)
        }
        assertFailsWith<IllegalArgumentException>("below the lower bound") {
            Metalog3P.fromSPT(-0.2, 0.4, 0.75, lowerBound = 0.0, upperBound = 1.0)
        }
    }

    @Test
    fun theUpperBoundedVariantWorksToo() {
        val alpha = 0.1
        val d = Metalog3P.fromSPT(-40.0, -9.0, -5.0, alpha, upperBound = -2.0)
        assertNearly(-40.0, d.invCDF(alpha), relTol = 1e-8, absTol = 1e-8)
        assertNearly(-9.0, d.invCDF(0.5), relTol = 1e-8, absTol = 1e-8)
        assertNearly(-5.0, d.invCDF(1.0 - alpha), relTol = 1e-8, absTol = 1e-8)
        assertNearly(-2.0, d.upperBound)
    }

    // -------- argument validation --------

    @Test
    fun theOffsetMustLieStrictlyBelowOneHalf() {
        assertFailsWith<IllegalArgumentException>("zero") {
            Metalog3P.sptCoefficients(1.0, 2.0, 3.0, alpha = 0.0)
        }
        assertFailsWith<IllegalArgumentException>("one half") {
            Metalog3P.sptCoefficients(1.0, 2.0, 3.0, alpha = 0.5)
        }
        assertFailsWith<IllegalArgumentException>("above one half") {
            Metalog3P.sptCoefficients(1.0, 2.0, 3.0, alpha = 0.7)
        }
        assertFailsWith<IllegalArgumentException>("negative") {
            Metalog3P.sptCoefficients(1.0, 2.0, 3.0, alpha = -0.1)
        }
    }

    @Test
    fun theTripletMustBeStrictlyIncreasing() {
        assertFailsWith<IllegalArgumentException>("median at the low quantile") {
            Metalog3P.sptCoefficients(1.0, 1.0, 3.0)
        }
        assertFailsWith<IllegalArgumentException>("median at the high quantile") {
            Metalog3P.sptCoefficients(1.0, 3.0, 3.0)
        }
        assertFailsWith<IllegalArgumentException>("out of order") {
            Metalog3P.sptCoefficients(3.0, 2.0, 1.0)
        }
    }

    @Test
    fun theDefaultOffsetIsTheTenthAndNinetiethPercentiles() {
        assertNearly(0.1, Metalog3P.DEFAULT_ALPHA)
        val explicit = Metalog3P.sptCoefficients(3.0, 10.0, 30.0, alpha = 0.1)
        val defaulted = Metalog3P.sptCoefficients(3.0, 10.0, 30.0)
        for (i in 0..2) {
            assertNearly(explicit[i], defaulted[i], message = "a${i + 1}:")
        }
    }

    @Test
    fun aNameCanBeAttached() {
        val d = Metalog3P.fromSPT(3.0, 10.0, 30.0, name = "expert assessment")
        assertTrue(d.name == "expert assessment", "the name was ${d.name}")
    }
}
