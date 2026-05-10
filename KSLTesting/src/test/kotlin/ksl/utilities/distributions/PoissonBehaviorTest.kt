package ksl.utilities.distributions

import org.junit.jupiter.api.Tag
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoissonBehaviorTest {

    private fun assertNearly(
        expected: Double,
        actual: Double,
        relTol: Double = 1e-8,
        absTol: Double = 1e-8,
        message: String = "",
    ) {
        val diff = abs(expected - actual)
        val threshold = max(absTol, relTol * max(1.0, abs(expected)))
        assertTrue(
            diff <= threshold,
            "$message Expected $expected, actual $actual, diff $diff, threshold $threshold",
        )
    }

    private fun assertProbability(value: Double, message: String = "") {
        assertTrue(
            value in 0.0..1.0,
            "$message Probability must be in [0, 1], actual $value",
        )
    }

    // -------- Step 1: PMF / CDF anchors --------

    @Test
    fun pmfAndCdfAnchors() {
        val poisson = Poisson(1.8)

        data class Case(
            val k: Int,
            val expectedCdf: Double,
            val expectedPmf: Double,
        )

        val cases = listOf(
            Case(-2, 0.0, 0.0),
            Case(-1, 0.0, 0.0),
            Case(0, 0.16529888822158653, 0.16529888822158653),
            Case(1, 0.46283688702044234, 0.2975379987988558),
            Case(2, 0.7306210859394124, 0.26778419891897026),
            Case(3, 0.8912916052907945, 0.16067051935138213),
            Case(5, 0.9896219631338404, 0.026028624134923906),
        )
        for (c in cases) {
            val cdf = if (c.k < 0) 0.0 else poisson.cdf(c.k)
            val pmf = if (c.k < 0) 0.0 else poisson.pmf(c.k)
            assertNearly(c.expectedCdf, cdf, message = "cdf(${c.k}):")
            assertNearly(c.expectedPmf, pmf, message = "pmf(${c.k}):")
            assertProbability(cdf, "cdf(${c.k}):")
            assertProbability(pmf, "pmf(${c.k}):")
        }
    }

    // -------- Step 2: Inverse CDF in the ordinary range --------

    @Test
    fun invCdfOrdinaryRange() {
        val poisson = Poisson(1.8)
        val cases = listOf(
            0.0  to 0.0,
            0.1  to 0.0,
            0.5  to 2.0,
            0.75 to 3.0,
            0.95 to 4.0,
        )
        for ((p, expected) in cases) {
            assertNearly(expected, poisson.invCDF(p), message = "invCDF($p):")
        }
    }

    // -------- Step 3: Upper-endpoint behavior --------

    /**
     * Documents current behavior: invCDF(1.0) returns +Infinity. This is
     * mathematically defensible (Poisson is unbounded), but is a behavior
     * worth pinning down in a test so any change is intentional.
     */
    @Test
    fun invCdfAtOneReturnsInfinity_currentBehavior() {
        assertEquals(Double.POSITIVE_INFINITY, Poisson(1.8).invCDF(1.0))
        assertEquals(Double.POSITIVE_INFINITY, Poisson(4.0).invCDF(1.0))
    }

    /**
     * cdf(Double) clamps via its Int.MAX_VALUE guard and returns exactly 1.0.
     */
    @Test
    fun cdfDoubleAtIntMaxIsOne() {
        val poisson = Poisson(1.8)
        val v = poisson.cdf(Int.MAX_VALUE.toDouble())
        assertEquals(1.0, v)
        assertProbability(v)
    }

    /**
     * recursiveCDF clamps the iterative sum at 1.0 to absorb floating-point
     * roundoff, so cdf(Int) stays in [0, 1] even when accumulating millions
     * of terms.
     */
    @Tag("slow")
    @Test
    fun cdfIntAtIntMaxStaysInRange() {
        val poisson = Poisson(1.8)
        val v = poisson.cdf(Int.MAX_VALUE)
        println("Poisson(1.8).cdf(Int.MAX_VALUE) = $v")
        assertProbability(v, "cdf(Int.MAX_VALUE) for mean=1.8:")
    }

    @Test
    fun cdfIntAtIntMaxStaysInRangeMean4() {
        val poisson = Poisson(4.0)
        val v = poisson.cdf(Int.MAX_VALUE)
        println("Poisson(4.0).cdf(Int.MAX_VALUE) = $v")
        assertProbability(v, "cdf(Int.MAX_VALUE) for mean=4.0:")
    }

    // -------- Step 4: First-order loss --------

    @Test
    fun firstOrderLossAnchors() {
        val poisson = Poisson(1.8)
        val cases = listOf(
            -2.0 to 3.8,
            -0.1 to 2.8,
            0.0  to 1.8,
            1.0  to 0.9652988882215866,
            1.9  to 0.9652988882215866,
            2.0  to 0.42813577524202895,
            3.9  to 0.15875686118144128,
            5.0  to 0.013641805471152013,
            10.0 to 3.659006526879338e-06,
        )
        for ((x, expected) in cases) {
            val actual = poisson.firstOrderLossFunction(floor(x))
            assertNearly(expected, actual, message = "G1(floor($x)):")
        }
    }

    // -------- Step 5: Second-order loss convention --------

    /**
     * Verifies the KSL convention as documented in [Poisson.poissonLF2]:
     *   G2(x) = (1/2) E[max(X-x, 0) * max(X-x-1, 0)]
     *
     * For Poisson at x=0 this gives 0.5 * mu^2 (NOT 0.5 * (mu^2 + mu)).
     * This is the second-factorial-moment convention and differs from the
     * Python/VBA "0.5 * E[max(X-x, 0)^2]" convention by mu/2 at x=0.
     */
    @Test
    fun secondOrderLossKslConvention() {
        val poisson = Poisson(1.8)
        val cases = listOf(
            -2.0 to 6.22,
            -0.1 to 3.42,
            0.0  to 1.62,                  // 0.5 * 1.8^2
            1.0  to 0.6547011117784135,
            1.9  to 0.6547011117784135,
            2.0  to 0.22656533653638442,
            3.9  to 0.06780847535494289,
            5.0  to 0.0041182034115533755,
            10.0 to 6.17891862368799e-7,
        )
        for ((x, expected) in cases) {
            val actual = poisson.secondOrderLossFunction(floor(x))
            assertNearly(expected, actual, message = "G2(floor($x)):")
        }
    }

    // -------- Step 6: Mean mutation --------

    @Test
    fun mean4FirstAndSecondOrderLossAtZero_kslConvention() {
        val poisson = Poisson(4.0)
        assertNearly(4.0, poisson.firstOrderLossFunction(0.0), message = "G1(0):")
        // KSL (Zipkin factorial-moment form): 0.5 * mean^2 = 8.0
        assertNearly(8.0, poisson.secondOrderLossFunction(0.0), message = "G2(0) Zipkin form:")
    }
}
