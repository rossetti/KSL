package ksl.utilities.distributions.metalog

import ksl.utilities.distributions.Logistic
import ksl.utilities.distributions.Uniform
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  Cases where a metalog coincides exactly with a distribution the KSL already implements, taken
 *  from the interpretation tables of Keelin (2016). These are the strongest checks available at
 *  this stage: they validate the basis functions, the boundedness transforms, the numerical
 *  inversion, and the moments together, against independent implementations, at close to machine
 *  precision.
 */
class MetalogSpecialCasesTest {

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

    // -------- two terms, unbounded, is exactly a logistic --------

    @Test
    fun twoTermUnboundedQuantileMatchesTheLogistic() {
        val location = 12.0
        val scale = 3.0
        val metalog = Metalog2P(location, scale)
        val logistic = Logistic(location, scale)
        for (p in doubleArrayOf(1e-6, 0.001, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 0.999, 1.0 - 1e-6)) {
            assertNearly(logistic.invCDF(p), metalog.invCDF(p), message = "invCDF at $p:")
        }
    }

    @Test
    fun twoTermUnboundedCdfMatchesTheLogistic() {
        val metalog = Metalog2P(12.0, 3.0)
        val logistic = Logistic(12.0, 3.0)
        for (x in doubleArrayOf(-20.0, 0.0, 6.0, 11.0, 12.0, 13.0, 18.0, 40.0)) {
            assertNearly(logistic.cdf(x), metalog.cdf(x), relTol = 1e-9, absTol = 1e-9, message = "cdf at $x:")
        }
    }

    @Test
    fun twoTermUnboundedPdfMatchesTheLogistic() {
        val metalog = Metalog2P(12.0, 3.0)
        val logistic = Logistic(12.0, 3.0)
        for (x in doubleArrayOf(-10.0, 0.0, 6.0, 11.0, 12.0, 13.0, 18.0, 30.0)) {
            assertNearly(logistic.pdf(x), metalog.pdf(x), relTol = 1e-8, absTol = 1e-12, message = "pdf at $x:")
        }
    }

    @Test
    fun twoTermUnboundedMomentsMatchTheLogistic() {
        val metalog = Metalog2P(12.0, 3.0)
        val logistic = Logistic(12.0, 3.0)
        assertNearly(logistic.mean(), metalog.mean())
        assertNearly(logistic.variance(), metalog.variance())
        // The logistic is symmetric, so its skewness is zero and its excess kurtosis is 6/5,
        // which means a standardized fourth moment of 4.2.
        assertNearly(0.0, metalog.skewness, relTol = 1e-5, absTol = 1e-5)
        assertNearly(4.2, metalog.kurtosis, relTol = 1e-5, absTol = 1e-5)
    }

    @Test
    fun aNegativeLocationLogisticAlsoMatches() {
        val metalog = Metalog2P(-4.5, 0.25)
        val logistic = Logistic(-4.5, 0.25)
        for (p in doubleArrayOf(0.01, 0.3, 0.5, 0.7, 0.99)) {
            assertNearly(logistic.invCDF(p), metalog.invCDF(p), message = "invCDF at $p:")
        }
        assertNearly(logistic.mean(), metalog.mean())
        assertNearly(logistic.variance(), metalog.variance())
    }

    // -------- four terms with only location and kurtosis is exactly a uniform --------

    @Test
    fun fourTermUniformQuantileMatchesTheUniform() {
        val center = 10.0
        val width = 4.0
        val metalog = Metalog4P(center, 0.0, 0.0, width)
        val uniform = Uniform(center - width / 2.0, center + width / 2.0)
        for (p in doubleArrayOf(1e-8, 0.001, 0.1, 0.25, 0.5, 0.75, 0.9, 0.999, 1.0 - 1e-8)) {
            assertNearly(uniform.invCDF(p), metalog.invCDF(p), message = "invCDF at $p:")
        }
    }

    @Test
    fun fourTermUniformCdfAndPdfMatchTheUniform() {
        val metalog = Metalog4P(10.0, 0.0, 0.0, 4.0)
        val uniform = Uniform(8.0, 12.0)
        for (x in doubleArrayOf(8.5, 9.0, 10.0, 11.0, 11.5)) {
            assertNearly(uniform.cdf(x), metalog.cdf(x), relTol = 1e-9, absTol = 1e-9, message = "cdf at $x:")
            assertNearly(uniform.pdf(x), metalog.pdf(x), relTol = 1e-8, absTol = 1e-10, message = "pdf at $x:")
        }
        // Outside the support the density vanishes and the cumulative function saturates.
        assertNearly(0.0, metalog.pdf(7.0))
        assertNearly(0.0, metalog.pdf(13.0))
        assertNearly(0.0, metalog.cdf(8.0))
        assertNearly(1.0, metalog.cdf(12.0))
    }

    @Test
    fun fourTermUniformMomentsMatchTheUniform() {
        val metalog = Metalog4P(10.0, 0.0, 0.0, 4.0)
        val uniform = Uniform(8.0, 12.0)
        assertNearly(uniform.mean(), metalog.mean())
        assertNearly(uniform.variance(), metalog.variance())
    }

    @Test
    fun theSupportOfTheFourTermUniformIsFiniteDespiteInfiniteBounds() {
        // Both declared bounds are infinite, yet no coefficient carries the logit, so the quantile
        // function is a linear function of probability and the support really is finite. Keelin
        // notes this case explicitly. The support has to be derived from the coefficients rather
        // than read off the bounds, otherwise the density would be reported as positive outside
        // the support.
        val metalog = Metalog4P(10.0, 0.0, 0.0, 4.0)
        assertNearly(8.0, metalog.domain().lowerLimit)
        assertNearly(12.0, metalog.domain().upperLimit)
        // The declared bounds are still infinite; they are parameters, not the support.
        assertTrue(metalog.lowerBound == Double.NEGATIVE_INFINITY)
        assertTrue(metalog.upperBound == Double.POSITIVE_INFINITY)
        assertNearly(8.0, metalog.invCDF(0.0))
        assertNearly(12.0, metalog.invCDF(1.0))
        // Outside the support the density vanishes rather than reporting the interior value.
        assertNearly(0.0, metalog.pdf(7.0))
        assertNearly(0.0, metalog.pdf(13.0))
        assertNearly(0.0, metalog.cdf(7.0))
        assertNearly(1.0, metalog.cdf(13.0))
    }

    @Test
    fun aMetalogCarryingTheLogitHasUnboundedSupport() {
        // The contrast case: any nonzero logit-carrying coefficient restores the infinite support.
        val metalog = Metalog4P(10.0, 1.0, 0.0, 4.0)
        assertTrue(metalog.domain().lowerLimit == Double.NEGATIVE_INFINITY)
        assertTrue(metalog.domain().upperLimit == Double.POSITIVE_INFINITY)
    }

    // -------- two terms bounded on the unit interval with unit scale is exactly a uniform --------

    @Test
    fun boundedTwoTermWithUnitScaleIsTheStandardUniform() {
        val metalog = Metalog2P(0.0, 1.0, lowerBound = 0.0, upperBound = 1.0)
        val uniform = Uniform(0.0, 1.0)
        for (p in doubleArrayOf(1e-8, 0.01, 0.25, 0.5, 0.75, 0.99, 1.0 - 1e-8)) {
            assertNearly(uniform.invCDF(p), metalog.invCDF(p), message = "invCDF at $p:")
        }
        for (x in doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)) {
            assertNearly(uniform.cdf(x), metalog.cdf(x), relTol = 1e-9, absTol = 1e-9, message = "cdf at $x:")
            assertNearly(uniform.pdf(x), metalog.pdf(x), relTol = 1e-8, absTol = 1e-10, message = "pdf at $x:")
        }
        assertNearly(uniform.mean(), metalog.mean(), relTol = 1e-7, absTol = 1e-7)
        assertNearly(uniform.variance(), metalog.variance(), relTol = 1e-6, absTol = 1e-6)
    }

    @Test
    fun aBoundedTwoTermOnAGeneralIntervalIsThatUniform() {
        // Scaling the bounds scales the uniform, and the unit scale coefficient is what makes the
        // logit transform cancel the logistic quantile exactly.
        val metalog = Metalog2P(0.0, 1.0, lowerBound = -3.0, upperBound = 7.0)
        val uniform = Uniform(-3.0, 7.0)
        for (p in doubleArrayOf(0.01, 0.25, 0.5, 0.75, 0.99)) {
            assertNearly(uniform.invCDF(p), metalog.invCDF(p), message = "invCDF at $p:")
        }
        assertNearly(uniform.mean(), metalog.mean(), relTol = 1e-7, absTol = 1e-7)
        assertNearly(uniform.variance(), metalog.variance(), relTol = 1e-6, absTol = 1e-6)
    }

    @Test
    fun aBoundedTwoTermWithScaleBelowOneIsUnimodal() {
        // Keelin's interpretation table: below unit scale the bounded two-term member is
        // unimodal, at unit scale it is uniform, and above it the shape becomes U-shaped, so the
        // density at the midpoint must fall below the uniform value.
        val unimodal = Metalog2P(0.0, 0.5, lowerBound = 0.0, upperBound = 1.0)
        val uShaped = Metalog2P(0.0, 2.0, lowerBound = 0.0, upperBound = 1.0)
        assertTrue(
            unimodal.pdf(0.5) > 1.0,
            "a unimodal bounded metalog should exceed the uniform density at the midpoint, " +
                    "but was ${unimodal.pdf(0.5)}",
        )
        assertTrue(
            uShaped.pdf(0.5) < 1.0,
            "a U-shaped bounded metalog should fall below the uniform density at the midpoint, " +
                    "but was ${uShaped.pdf(0.5)}",
        )
        // A U shape is symmetric about the midpoint and rises toward both bounds.
        assertNearly(uShaped.pdf(0.1), uShaped.pdf(0.9), relTol = 1e-6, absTol = 1e-6)
        assertTrue(uShaped.pdf(0.1) > uShaped.pdf(0.5))
    }

    // -------- two terms with a lower bound is a log-logistic --------

    @Test
    fun lowerBoundedTwoTermIsALogLogistic() {
        // With a lower bound of zero, the log of the variable is logistic, so the quantile
        // function is the odds ratio raised to the scale coefficient.
        val scale = 0.4
        val metalog = Metalog2P(0.0, scale, lowerBound = 0.0)
        for (p in doubleArrayOf(0.01, 0.1, 0.5, 0.9, 0.99)) {
            val expected = Math.pow(p / (1.0 - p), scale)
            assertNearly(expected, metalog.invCDF(p), message = "invCDF at $p:")
        }
        // The median of a log-logistic with unit scale is one.
        assertNearly(1.0, metalog.invCDF(0.5))
    }

    @Test
    fun aShiftedLowerBoundOffsetsTheLogLogistic() {
        val shift = 25.0
        val unshifted = Metalog2P(0.0, 0.2, lowerBound = 0.0)
        val shifted = Metalog2P(0.0, 0.2, lowerBound = shift)
        for (p in doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)) {
            assertNearly(
                unshifted.invCDF(p) + shift, shifted.invCDF(p),
                message = "invCDF at $p:",
            )
        }
        assertNearly(unshifted.mean() + shift, shifted.mean(), relTol = 1e-6, absTol = 1e-6)
        assertNearly(unshifted.variance(), shifted.variance(), relTol = 1e-6, absTol = 1e-6)
    }

    // -------- an upper bounded metalog mirrors a lower bounded one --------

    @Test
    fun anUpperBoundedMetalogIsTheReflectionOfALowerBoundedOne() {
        // Negating the location and reflecting the bound should produce the mirror image, because
        // the two transforms differ only by a sign.
        val lower = Metalog2P(0.0, 0.2, lowerBound = 0.0)
        val upper = Metalog2P(0.0, 0.2, upperBound = 0.0)
        for (p in doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)) {
            assertNearly(
                -lower.invCDF(1.0 - p), upper.invCDF(p),
                message = "invCDF at $p:",
            )
        }
        assertNearly(-lower.mean(), upper.mean(), relTol = 1e-6, absTol = 1e-6)
        assertNearly(lower.variance(), upper.variance(), relTol = 1e-6, absTol = 1e-6)
    }
}

