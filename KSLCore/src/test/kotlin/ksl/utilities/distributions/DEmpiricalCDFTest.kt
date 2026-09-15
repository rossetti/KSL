package ksl.utilities.distributions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * [DEmpiricalCDF] had no test of its own, which is the reason it is in this state.
 *
 * The `cdf` checks here are separate from the loss-function invariants because the defect they look
 * for is a loop-shape error rather than a formula error: a walk that consumes two support points per
 * pass examines the brackets (x0,x1), (x2,x3), … and never (x1,x2), (x3,x4), …. That misses
 * different arguments depending on the parity of the support size, and runs off the end of an
 * odd-length list, so the support lengths are varied deliberately.
 */
class DEmpiricalCDFTest {

    companion object {
        /**
         * Supports of even and odd length. A three-point support is the smallest that reproduces the
         * exhausted-iterator case; four points give the misread-bracket case.
         */
        @JvmStatic
        fun supports(): List<Support> = listOf(
            Support("3 points", doubleArrayOf(1.0, 4.0, 7.0), doubleArrayOf(0.25, 0.75, 1.0)),
            Support("4 points", doubleArrayOf(2.0, 5.0, 9.0, 14.0), doubleArrayOf(0.20, 0.50, 0.85, 1.0)),
            Support(
                "5 points",
                doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0),
                doubleArrayOf(0.2, 0.4, 0.6, 0.8, 1.0)
            ),
            Support("6 points", doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), doubleArrayOf(0.1, 0.3, 0.45, 0.7, 0.9, 1.0))
        )
    }

    data class Support(val label: String, val values: DoubleArray, val cdf: DoubleArray) {
        override fun toString(): String = label

        // DoubleArray members make the generated equals/hashCode identity-based; the data class is
        // only a test parameter holder, so define them explicitly rather than leave them surprising.
        override fun equals(other: Any?): Boolean =
            other is Support && label == other.label &&
                values.contentEquals(other.values) && cdf.contentEquals(other.cdf)

        override fun hashCode(): Int =
            31 * (31 * label.hashCode() + values.contentHashCode()) + cdf.contentHashCode()
    }

    /**
     * The defining property of a CDF built from an explicit table: at each support point it returns
     * the cumulative probability that was supplied for it. Nothing subtler is needed — an
     * implementation that cannot reproduce its own constructor arguments is wrong whatever else it
     * does.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("supports")
    @DisplayName("cdf at every support point returns the supplied cumulative probability")
    fun cdfAtSupportPointsReturnsTheSuppliedCumulativeProbability(support: Support) {
        val d = DEmpiricalCDF(support.values, support.cdf)
        val failures = mutableListOf<String>()
        for (i in support.values.indices) {
            val x = support.values[i]
            val expected = support.cdf[i]
            val actual = runCatching { d.cdf(x) }
                .getOrElse { e -> failures.add("cdf($x) threw ${e::class.simpleName}: ${e.message}"); return@getOrElse Double.NaN }
            if (!actual.isNaN() && kotlin.math.abs(expected - actual) > 1.0e-12) {
                failures.add("cdf($x): expected $expected, got $actual")
            }
        }
        assertTrue(failures.isEmpty()) {
            "${support.label} support:\n  " + failures.joinToString("\n  ")
        }
    }

    /**
     * Between support points the CDF is a step function: it holds the value of the largest support
     * point not exceeding the argument. This catches a walk that skips brackets even where it
     * happens to be right at the points themselves.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("supports")
    @DisplayName("cdf between support points holds the previous step")
    fun cdfBetweenSupportPointsHoldsThePreviousStep(support: Support) {
        val d = DEmpiricalCDF(support.values, support.cdf)
        val failures = mutableListOf<String>()
        for (i in 0 until support.values.size - 1) {
            val midpoint = (support.values[i] + support.values[i + 1]) / 2.0
            val expected = support.cdf[i]
            val actual = runCatching { d.cdf(midpoint) }
                .getOrElse { e -> failures.add("cdf($midpoint) threw ${e::class.simpleName}"); return@getOrElse Double.NaN }
            if (!actual.isNaN() && kotlin.math.abs(expected - actual) > 1.0e-12) {
                failures.add("cdf($midpoint): expected $expected (the step at ${support.values[i]}), got $actual")
            }
        }
        assertTrue(failures.isEmpty()) {
            "${support.label} support:\n  " + failures.joinToString("\n  ")
        }
    }

    /** Outside the support the answer is 0 below and 1 above, whatever the support's length. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("supports")
    @DisplayName("cdf is 0 below the support and 1 at or above its top")
    fun cdfOutsideTheSupport(support: Support) {
        val d = DEmpiricalCDF(support.values, support.cdf)
        assertEquals(0.0, d.cdf(support.values.first() - 1.0), 1.0e-12)
        assertEquals(1.0, d.cdf(support.values.last()), 1.0e-12)
        assertEquals(1.0, d.cdf(support.values.last() + 1.0), 1.0e-12)
    }

    /**
     * `cdf` must never throw on an argument inside its own support. The two-advances-per-pass walk
     * runs the iterator off the end when the support has an odd number of points, so an ordinary
     * lookup raises `NoSuchElementException` — a failure mode that has nothing to do with the value
     * being asked for.
     */
    @Test
    @DisplayName("cdf does not throw anywhere across its support")
    fun cdfNeverThrowsAcrossItsSupport() {
        val failures = mutableListOf<String>()
        for (support in supports()) {
            val d = DEmpiricalCDF(support.values, support.cdf)
            val lower = support.values.first() - 1.0
            val upper = support.values.last() + 1.0
            var x = lower
            while (x <= upper) {
                runCatching { d.cdf(x) }.onFailure { e ->
                    failures.add("${support.label}: cdf($x) threw ${e::class.simpleName}")
                }
                x += 0.25
            }
        }
        assertTrue(failures.isEmpty()) {
            "cdf threw on arguments inside the support:\n  " + failures.take(10).joinToString("\n  ") +
                if (failures.size > 10) "\n  ... and ${failures.size - 10} more" else ""
        }
    }

    /**
     * `invCDF` walks the same list with a single advance per pass, so variate generation does not
     * inherit the defect. Recorded so that a fix to `cdf` is not mistaken for a fix to something
     * that was never broken, and so the two stay consistent afterwards.
     */
    @Test
    @DisplayName("invCDF inverts cdf at the supplied probabilities")
    fun invCDFIsConsistentWithTheSuppliedTable() {
        val values = doubleArrayOf(2.0, 5.0, 9.0, 14.0)
        val cdf = doubleArrayOf(0.20, 0.50, 0.85, 1.00)
        val d = DEmpiricalCDF(values, cdf)
        for (i in values.indices) {
            // Just inside each step: the value returned is that step's support point.
            val p = cdf[i] - 1.0e-9
            assertEquals(values[i], d.invCDF(p), 1.0e-12) { "invCDF($p) should land on ${values[i]}" }
        }
    }

    /** The mean reads straight off the probability points and is unaffected by the walk. */
    @Test
    @DisplayName("mean equals the probability-weighted sum of the support")
    fun meanMatchesTheTable() {
        val values = doubleArrayOf(2.0, 5.0, 9.0, 14.0)
        val cdf = doubleArrayOf(0.20, 0.50, 0.85, 1.00)
        val expected = 2.0 * 0.20 + 5.0 * 0.30 + 9.0 * 0.35 + 14.0 * 0.15
        assertEquals(expected, DEmpiricalCDF(values, cdf).mean(), 1.0e-12)
    }
}
