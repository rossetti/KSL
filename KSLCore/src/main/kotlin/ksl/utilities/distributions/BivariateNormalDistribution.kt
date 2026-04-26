package ksl.utilities.distributions

import org.hipparchus.analysis.UnivariateFunction
import org.hipparchus.analysis.integration.IterativeLegendreGaussIntegrator
import kotlin.math.*

/**
 * Evaluates the cumulative distribution function (CDF) for a bivariate normal distribution
 * over a rectangular region. Uses Alan Genz's Arcsine Transformation for numerical
 * integration and exact analytical overlaps for perfectly correlated edge cases.
 *
 * @param mean1 mean of the first coordinate
 * @param v1 variance of the first coordinate (must be > 0)
 * @param mean2 mean of the second coordinate
 * @param v2 variance of the second coordinate (must be > 0)
 * @param corr correlation coefficient between the coordinates (must be in [-1, 1])
 */
class BivariateNormalDistribution(
    val mean1: Double = 0.0,
    val v1: Double = 1.0,
    val mean2: Double = 0.0,
    val v2: Double = 1.0,
    val corr: Double = 0.0
) : MVCDF(2) {

    private val sd1: Double = sqrt(v1)
    private val sd2: Double = sqrt(v2)

    // Hipparchus integrator using 20 points per interval for high precision
    private val integrator = IterativeLegendreGaussIntegrator(
        20,
        IterativeLegendreGaussIntegrator.DEFAULT_RELATIVE_ACCURACY,
        IterativeLegendreGaussIntegrator.DEFAULT_ABSOLUTE_ACCURACY
    )

    init {
        require(v1 > 0) { "Variance 1 must be > 0" }
        require(v2 > 0) { "Variance 2 must be > 0" }
        require(corr in -1.0..1.0) { "Correlation must be in [-1.0, 1.0]" }
    }

    override fun computeCDF(): Double {
        // 1. Standardize bounds using mean and standard deviation
        val h0 = (a[0] - mean1) / sd1
        val h1 = (b[0] - mean1) / sd1
        val k0 = (a[1] - mean2) / sd2
        val k1 = (b[1] - mean2) / sd2

        // 2. Handle Highly Correlated Edge Cases (Avoids numerical singularities)
        val tolerance = 0.999999
        if (corr > tolerance) {
            // Perfect Positive Correlation
            val lowerBound = maxOf(h0, k0)
            val upperBound = minOf(h1, k1)
            return if (lowerBound < upperBound) {
                Normal.stdNormalCDF(upperBound) - Normal.stdNormalCDF(lowerBound)
            } else 0.0
        } else if (corr < -tolerance) {
            // Perfect Negative Correlation
            val lowerBound = maxOf(h0, -k1)
            val upperBound = minOf(h1, -k0)
            return if (lowerBound < upperBound) {
                Normal.stdNormalCDF(upperBound) - Normal.stdNormalCDF(lowerBound)
            } else 0.0
        }

        // 3. Compute the Independent Base Probability
        val p1 = Normal.stdNormalCDF(h1) - Normal.stdNormalCDF(h0)
        val p2 = Normal.stdNormalCDF(k1) - Normal.stdNormalCDF(k0)
        val baseProb = p1 * p2

        // Short-circuit if correlation is zero
        if (corr == 0.0) return baseProb.coerceIn(0.0, 1.0)

        // 4. Genz Arcsine Integration for standard correlation limits
        val integrand = UnivariateFunction { theta ->
            val sinTheta = sin(theta)
            val cos2Theta = cos(theta) * cos(theta)

            // Evaluates the integrand for all 4 corners of the rectangular domain
            val t1 = evalExp(h1, k1, sinTheta, cos2Theta)
            val t2 = evalExp(h0, k1, sinTheta, cos2Theta)
            val t3 = evalExp(h1, k0, sinTheta, cos2Theta)
            val t4 = evalExp(h0, k0, sinTheta, cos2Theta)

            t1 - t2 - t3 + t4
        }

        // Integrate from 0 to arcsin(rho)
        val integral = integrator.integrate(10000, integrand, 0.0, asin(corr))
        val p = baseProb + integral / (2.0 * PI)

        return p.coerceIn(0.0, 1.0)
    }

    /**
     * Helper function to safely evaluate the exponential term in Genz's formula.
     * Evaluates to 0 immediately if an infinite bound is passed.
     */
    private fun evalExp(h: Double, k: Double, sinTheta: Double, cos2Theta: Double): Double {
        if (h.isInfinite() || k.isInfinite()) return 0.0
        val num = h * h + k * k - 2.0 * h * k * sinTheta
        return exp(-num / (2.0 * cos2Theta))
    }
}