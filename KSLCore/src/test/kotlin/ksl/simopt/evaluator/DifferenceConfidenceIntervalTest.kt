package ksl.simopt.evaluator

import ksl.utilities.distributions.StudentT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * Unit tests for the confidence interval on the difference of two estimated responses.
 *
 * The first group covers degenerate (zero sample variance) estimates, which arise whenever a
 * response is a deterministic function of the decision variables. The difference is then known
 * exactly, so the interval is the point estimate.
 *
 * The remaining groups pin the paths the degenerate-variance guard does NOT touch, so that a
 * future change to the Welch-Satterthwaite arithmetic cannot pass unnoticed. The mixed case has
 * a closed form worth checking independently: when one sample variance is zero the
 * Welch-Satterthwaite degrees of freedom reduce exactly to n - 1 of the other sample, and the
 * standard error to that sample's own, so the interval must equal the one-sample interval.
 */
class DifferenceConfidenceIntervalTest {

    private companion object {
        const val NAME = "response"
        const val LEVEL = 0.95
        const val TOL = 1.0e-10
    }

    private fun estimate(average: Double, variance: Double, count: Double) =
        EstimatedResponse(NAME, average, variance, count)

    @Test
    @DisplayName("Two zero-variance estimates give the point interval, not a NaN degrees of freedom")
    fun zeroVarianceBothEstimatesGivesPointInterval() {
        val a = estimate(average = 10.0, variance = 0.0, count = 8.0)
        val b = estimate(average = 4.0, variance = 0.0, count = 5.0)

        val ci = EstimatedResponseIfc.differenceConfidenceInterval(a, b, LEVEL)
        assertEquals(6.0, ci.lowerLimit, TOL)
        assertEquals(6.0, ci.upperLimit, TOL)

        // the reversed comparison must mirror it
        val reversed = EstimatedResponseIfc.differenceConfidenceInterval(b, a, LEVEL)
        assertEquals(-6.0, reversed.lowerLimit, TOL)
        assertEquals(-6.0, reversed.upperLimit, TOL)
    }

    @Test
    @DisplayName("Two identical zero-variance estimates give a zero-width interval at zero")
    fun zeroVarianceIdenticalEstimatesGivesZeroInterval() {
        val a = estimate(average = 7.5, variance = 0.0, count = 10.0)
        val b = estimate(average = 7.5, variance = 0.0, count = 3.0)

        val ci = EstimatedResponseIfc.differenceConfidenceInterval(a, b, LEVEL)
        assertEquals(0.0, ci.lowerLimit, TOL)
        assertEquals(0.0, ci.upperLimit, TOL)
    }

    @Test
    @DisplayName("Zero-variance estimates compare exactly, honoring the indifference zone")
    fun zeroVarianceEstimatesCompareExactly() {
        val low = estimate(average = 4.0, variance = 0.0, count = 6.0)
        val high = estimate(average = 10.0, variance = 0.0, count = 6.0)
        val alsoLow = estimate(average = 4.0, variance = 0.0, count = 9.0)

        assertEquals(-1, EstimatedResponseIfc.compareEstimatedResponses(low, high, LEVEL))
        assertEquals(1, EstimatedResponseIfc.compareEstimatedResponses(high, low, LEVEL))
        assertEquals(0, EstimatedResponseIfc.compareEstimatedResponses(low, alsoLow, LEVEL))

        // a difference of 6.0 sits inside an indifference zone of 10.0
        assertEquals(
            0,
            EstimatedResponseIfc.compareEstimatedResponses(low, high, LEVEL, indifferenceZone = 10.0)
        )
    }

    @Test
    @DisplayName("One zero variance reduces to the other sample's one-sample interval")
    fun oneZeroVarianceReducesToOneSampleInterval() {
        val n2 = 12.0
        val var2 = 9.0
        val a = estimate(average = 20.0, variance = 0.0, count = 7.0)
        val b = estimate(average = 15.0, variance = var2, count = n2)

        val ci = EstimatedResponseIfc.differenceConfidenceInterval(a, b, LEVEL)

        // With v1 = 0 the Welch-Satterthwaite degrees of freedom collapse to n2 - 1 and the
        // standard error to sqrt(var2 / n2), independently of the first sample's count.
        val expectedHalfWidth = StudentT.invCDF(n2 - 1.0, 1.0 - (1.0 - LEVEL) / 2.0) * sqrt(var2 / n2)
        assertEquals(5.0 - expectedHalfWidth, ci.lowerLimit, TOL)
        assertEquals(5.0 + expectedHalfWidth, ci.upperLimit, TOL)
        assertTrue(expectedHalfWidth > 0.0) { "The mixed case must still produce a real interval" }
    }

    @Test
    @DisplayName("Two positive variances give a Welch-Satterthwaite interval centered on the difference")
    fun twoPositiveVariancesGiveWelchInterval() {
        val a = estimate(average = 10.0, variance = 4.0, count = 5.0)
        val b = estimate(average = 8.0, variance = 9.0, count = 10.0)

        val ci = EstimatedResponseIfc.differenceConfidenceInterval(a, b, LEVEL)

        val d = 2.0
        val center = (ci.lowerLimit + ci.upperLimit) / 2.0
        assertEquals(d, center, TOL)

        // The standard error does not depend on the degrees-of-freedom arithmetic, so it pins the
        // interval's width independently: se = sqrt(var1/n1 + var2/n2).
        val se = sqrt(4.0 / 5.0 + 9.0 / 10.0)
        val halfWidth = (ci.upperLimit - ci.lowerLimit) / 2.0
        val t = halfWidth / se
        // Welch degrees of freedom here are ~14, so the multiplier must sit just above the normal
        // quantile 1.96 and below the t(2) quantile 4.30.
        assertTrue(t > 1.96 && t < 4.30) {
            "Unexpected t multiplier $t for the Welch interval $ci"
        }
    }
}
