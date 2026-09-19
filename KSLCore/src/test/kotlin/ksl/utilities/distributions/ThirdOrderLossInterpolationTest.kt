package ksl.utilities.distributions

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 *  `interpolatedThirdOrderLoss` against the definition it claims to reproduce exactly.
 *
 *  The third order loss function is the one quantity an (r, Q) policy needs above the second: the
 *  variance of the backorder level collapses a sum of second order loss functions across the
 *  reorder band into a difference of third order ones. A reorder point compared against a
 *  continuous approximation is not a whole number, so the fractional case is the case that matters.
 *
 *  **Why this file exists separately from the closed forms.** The closed forms the distributions
 *  carry are derived for integral arguments. Every one of them went wrong differently when handed a
 *  fraction, and the fix for `G1` and `G2` was an exact interpolation rather than a formula
 *  correction. `G3` needs its own derivation — the `G2` one does not generalize by inspection,
 *  because a different number of clamped factors changes which support points contribute. Getting
 *  that derivation right is the whole of the risk, so it is checked here on its own, before any
 *  distribution depends on it.
 *
 *  The oracle is direct summation of the definition, fully clamped, and nothing else.
 */
class ThirdOrderLossInterpolationTest {

    companion object {

        @JvmStatic
        fun means(): List<Double> = listOf(0.75, 1.5, 6.0, 12.0)

        /** Enough of the tail that what remains cannot move the answer at these tolerances. */
        private const val SUM_LIMIT = 400
    }

    /**
     *  `G3(b) = (1/6) E[(X-b)+ (X-b-1)+ (X-b-2)+]`, every factor clamped.
     *
     *  All three clamps are load-bearing. A support point sitting strictly between `b` and `b+2`
     *  makes a later factor negative, and a product of clamped non-negative quantities cannot be,
     *  so guarding only the first would let the oracle go negative and agree with a wrong
     *  implementation.
     */
    private fun bruteForceG3(d: Poisson, b: Double): Double {
        var total = 0.0
        for (k in 0..SUM_LIMIT) {
            val first = max(k - b, 0.0)
            val second = max(k - b - 1.0, 0.0)
            val third = max(k - b - 2.0, 0.0)
            total += first * second * third * d.pmf(k)
        }
        return total / 6.0
    }

    /** Relative where the magnitude allows it, absolute near zero. */
    private fun closeEnough(expected: Double, actual: Double, tolerance: Double): Boolean =
        abs(expected - actual) <= tolerance * max(1.0, abs(expected))

    private fun interpolated(d: Poisson, x: Double): Double =
        interpolatedThirdOrderLoss(d, x) { n -> bruteForceG3(d, n) }

    @ParameterizedTest(name = "Poisson({0})")
    @MethodSource("means")
    @DisplayName("the interpolation reproduces the definition between whole numbers")
    fun interpolationIsExactAtFractionalArguments(mean: Double) {
        val d = Poisson(mean)
        val failures = mutableListOf<String>()
        var worst = 0.0
        var x = -3.0
        while (x <= 25.0) {
            if (x != floor(x)) {
                val expected = bruteForceG3(d, x)
                val actual = interpolated(d, x)
                worst = max(worst, abs(expected - actual))
                if (!closeEnough(expected, actual, 1.0e-9)) {
                    failures.add("G3($x): expected $expected, interpolated $actual")
                }
            }
            x += 0.14
        }
        assertTrue(failures.isEmpty()) {
            "Poisson($mean): the interpolation is not exact (worst $worst):\n  " +
                failures.take(8).joinToString("\n  ")
        }
    }

    /**
     *  Negative arguments are not a curiosity here: an optimizer examining a reorder point below
     *  zero reaches them, and the below-zero branches of the closed forms were right at a negative
     *  whole number and wrong between them — the same defect one order down.
     */
    @ParameterizedTest(name = "Poisson({0})")
    @MethodSource("means")
    @DisplayName("the interpolation is exact below zero as well")
    fun interpolationIsExactBelowZero(mean: Double) {
        val d = Poisson(mean)
        val failures = mutableListOf<String>()
        for (x in listOf(-2.75, -2.5, -2.25, -1.5, -0.75, -0.5, -0.25)) {
            val expected = bruteForceG3(d, x)
            val actual = interpolated(d, x)
            if (!closeEnough(expected, actual, 1.0e-9)) {
                failures.add("G3($x): expected $expected, interpolated $actual")
            }
        }
        assertTrue(failures.isEmpty()) {
            "Poisson($mean) below zero:\n  " + failures.joinToString("\n  ")
        }
    }

    @ParameterizedTest(name = "Poisson({0})")
    @MethodSource("means")
    @DisplayName("the interpolation agrees with the closed form at whole numbers")
    fun interpolationReducesToTheWholeNumberResult(mean: Double) {
        val d = Poisson(mean)
        val failures = mutableListOf<String>()
        for (n in -3..25) {
            val b = n.toDouble()
            val expected = bruteForceG3(d, b)
            // t = 0, so every correction term drops out and this must return G3(n) untouched.
            val actual = interpolated(d, b)
            if (!closeEnough(expected, actual, 1.0e-12)) {
                failures.add("G3($b): expected $expected, interpolated $actual")
            }
        }
        assertTrue(failures.isEmpty()) {
            "Poisson($mean) at whole numbers:\n  " + failures.joinToString("\n  ")
        }
    }

    /**
     *  The trap this derivation exists to avoid, pinned so that nobody replaces the interpolation
     *  with the recursion on the grounds that it is simpler.
     *
     *  `G3(b) = G3(b+1) + G2(b+1)` follows from Pascal's identity on binomial coefficients and is
     *  exact when `b` is a whole number. It is **false** between them, because the identity needs
     *  the arguments to differ by exactly one step of the support. An implementation built on it
     *  would look correct at every integer and be wrong everywhere else — which is precisely how
     *  the defects in the closed forms survived.
     */
    @ParameterizedTest(name = "Poisson({0})")
    @MethodSource("means")
    @DisplayName("the Pascal recursion holds at whole numbers and fails between them")
    fun theRecursionCannotServeTheFractionalCase(mean: Double) {
        val d = Poisson(mean)
        fun g2(b: Double) = d.secondOrderLossFunction(b)

        for (n in 0..12) {
            val b = n.toDouble()
            val viaRecursion = bruteForceG3(d, b + 1.0) + g2(b + 1.0)
            assertTrue(closeEnough(bruteForceG3(d, b), viaRecursion, 1.0e-9)) {
                "Poisson($mean): the recursion should be exact at the whole number $b"
            }
        }

        val offenders = listOf(0.25, 2.5, 5.25, 7.5)
            .map { x -> x to abs(bruteForceG3(d, x) - (bruteForceG3(d, x + 1.0) + g2(x + 1.0))) }
            .filter { (_, gap) -> gap > 1.0e-6 }
        assertTrue(offenders.isNotEmpty()) {
            "Poisson($mean): the recursion was expected to fail between whole numbers, but did " +
                "not — if that is now genuinely true, this test and the derivation it guards " +
                "need revisiting rather than deleting"
        }
    }
}
