package ksl.examples.general.variables

import ksl.utilities.Interval
import ksl.utilities.distributions.BivariateNormalDistribution
import ksl.utilities.distributions.Normal
import kotlin.math.abs

fun main() {
    println("--- BivariateNormalDistribution Validation Suite ---\n")

    testIndependence()
    testPositiveCorrelationKnownFraction()
    testPerfectPositiveCorrelation()
    testPerfectNegativeCorrelation()
    testFiniteRectangularRegion()

    println("--- All tests completed successfully ---")
}

/**
 * Test 1: Zero Correlation (Independence)
 * When rho = 0, P(Z1 <= 0, Z2 <= 0) should exactly equal P(Z1 <= 0) * P(Z2 <= 0) = 0.5 * 0.5 = 0.25
 */
fun testIndependence() {
    val bvn = BivariateNormalDistribution(corr = 0.0)
    val intervals = listOf(
        Interval(Double.NEGATIVE_INFINITY, 0.0),
        Interval(Double.NEGATIVE_INFINITY, 0.0)
    )

    val result = bvn.cdf(intervals)
    val expected = 0.25

    printTestResult("Independence (rho = 0.0)", expected, result)
}

/**
 * Test 2: Positive Correlation with Known Fractional Output
 * When rho = 0.5, the integral for the lower-left quadrant [-inf, 0] x [-inf, 0]
 * evaluates exactly to 1/3 (or 0.33333333...).
 */
fun testPositiveCorrelationKnownFraction() {
    val bvn = BivariateNormalDistribution(corr = 0.5)
    val intervals = listOf(
        Interval(Double.NEGATIVE_INFINITY, 0.0),
        Interval(Double.NEGATIVE_INFINITY, 0.0)
    )

    val result = bvn.cdf(intervals)
    val expected = 1.0 / 3.0

    printTestResult("Positive Correlation (rho = 0.5)", expected, result)
}

/**
 * Test 3: Perfect Positive Correlation (rho = 1.0)
 * Evaluates the analytical edge case. If perfectly correlated, P(Z1 <= 1, Z2 <= 1)
 * simply collapses to the 1D probability P(Z <= 1).
 */
fun testPerfectPositiveCorrelation() {
    val bvn = BivariateNormalDistribution(corr = 1.0)
    val intervals = listOf(
        Interval(Double.NEGATIVE_INFINITY, 1.0),
        Interval(Double.NEGATIVE_INFINITY, 1.0)
    )

    val result = bvn.cdf(intervals)
    val expected = Normal.stdNormalCDF(1.0) // approx 0.84134

    printTestResult("Perfect Positive Correlation (rho = 1.0)", expected, result)
}

/**
 * Test 4: Perfect Negative Correlation (rho = -1.0)
 * If Z1 and Z2 move exactly oppositely, the probability of both being <= 0 simultaneously
 * is logically 0.0, because if Z1 <= 0, Z2 must be >= 0.
 */
fun testPerfectNegativeCorrelation() {
    val bvn = BivariateNormalDistribution(corr = -1.0)
    val intervals = listOf(
        Interval(Double.NEGATIVE_INFINITY, 0.0),
        Interval(Double.NEGATIVE_INFINITY, 0.0)
    )

    val result = bvn.cdf(intervals)
    val expected = 0.0

    printTestResult("Perfect Negative Correlation (rho = -1.0)", expected, result)
}

/**
 * Test 5: Finite Rectangular Region (Independence)
 * Evaluates a bounded box [-1, 1] x [-1, 1] with rho = 0.
 * Should equal (P(-1 <= Z <= 1))^2.
 */
fun testFiniteRectangularRegion() {
    val bvn = BivariateNormalDistribution(corr = 0.0)
    val intervals = listOf(
        Interval(-1.0, 1.0),
        Interval(-1.0, 1.0)
    )

    val result = bvn.cdf(intervals)
    val p1D = Normal.stdNormalCDF(1.0) - Normal.stdNormalCDF(-1.0)
    val expected = p1D * p1D

    printTestResult("Finite Rectangle [-1, 1]x[-1, 1] (rho = 0.0)", expected, result)
}

/**
 * Helper function to format and validate test results.
 */
fun printTestResult(testName: String, expected: Double, actual: Double, tolerance: Double = 1e-7) {
    val diff = abs(expected - actual)
    val status = if (diff <= tolerance) "PASS" else "FAIL"

    println("Test: $testName")
    println("  Expected: $expected")
    println("  Actual:   $actual")
    println("  Diff:     $diff")
    println("  Status:   $status\n")
}