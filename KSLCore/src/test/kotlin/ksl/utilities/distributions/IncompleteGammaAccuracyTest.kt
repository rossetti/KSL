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

package ksl.utilities.distributions

import ksl.utilities.exceptions.KSLTooManyIterationsException
import ksl.utilities.math.KSLMath
import org.junit.jupiter.api.DisplayName
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Accuracy of the incomplete gamma function, which underpins the gamma and chi-squared
 *  distributions and the non-recursive Poisson cdf.
 *
 *  The series stops on a bound for the whole remaining tail rather than on the size of the last
 *  term computed. The distinction is invisible for small shapes, where terms fall away quickly and
 *  the two agree, and decisive for large ones, where the terms near x = alpha form a slowly-decaying
 *  plateau and everything still to come outweighs the last term by roughly alpha/n. Testing the last
 *  term alone returned values that looked converged and were not: the error passed the library's
 *  numerical precision at a shape near 150 and grew like the square root of the shape from there.
 *
 *  Nothing here can be checked against the implementation's own output, since that is what is in
 *  question, nor against Hipparchus, which runs the same algorithm and would only agree about the
 *  epsilon. Every expected value below was computed independently at 40 decimal digits with mpmath
 *  by summing the series to a relative term threshold of 1e-30, roughly fourteen digits beyond what
 *  a Double can represent.
 */
class IncompleteGammaAccuracyTest {

    /** A shape, the argument the incomplete gamma is evaluated at, and the true probability. */
    private data class Row(val alpha: Double, val z: Double, val p: Double)

    /** A shape, an offset in standard deviations, the resulting argument, and the true probability. */
    private data class Offset(val alpha: Double, val k: Double, val z: Double, val p: Double)

    /**
     *  How close the result can be expected to land.
     *
     *  Both branches close with exp(-z + alpha*ln(z) - logGamma(alpha)), whose terms are of size
     *  alpha*ln(z) while their sum is of order one. The cancellation costs about
     *  |alpha*ln(z)| * 2^-53 in the exponent, and that carries through as a relative error in
     *  whatever the branch computes -- P below the boundary, Q above it. Scaling by that branch
     *  quantity matters rather than being a nicety: without it the tolerance depends on the shape
     *  alone and, five standard deviations into the lower tail at a shape of 1e8, exceeds the
     *  probability being tested, so returning zero would pass.
     *
     *  Below a shape of roughly 6e6 at the mean this is simply the library's numerical precision.
     *  Above it the formulation, not the convergence test, sets the error.
     */
    private fun tolerance(alpha: Double, z: Double, referenceP: Double): Double {
        val branchProbability = if (z < alpha + 1.0) referenceP else 1.0 - referenceP
        val cancellation = 2.0 * branchProbability * abs(alpha * ln(z)) * TWO_TO_MINUS_53
        return maxOf(KSLMath.defaultNumericalPrecision, cancellation)
    }

    private fun incompleteGamma(z: Double, alpha: Double): Double =
        Gamma.incompleteGammaFunction(z, alpha, Gamma.INC_GAMMA_MAX_ITERATIONS, KSLMath.defaultNumericalPrecision)

    // ----- criterion 1: the reported failure -----------------------------------------------

    @Test
    @DisplayName("The degenerate fitted shape returns its true probability instead of throwing")
    fun reproductionCaseReturnsTheCorrectProbability() {
        val g = Gamma(shape = REPRO_SHAPE, scale = REPRO_SCALE)
        val actual = g.cdf(12.42)
        assertTrue(
            abs(actual - REPRO_CDF) <= KSLMath.defaultNumericalPrecision,
            "cdf(12.42) was $actual, expected $REPRO_CDF within ${KSLMath.defaultNumericalPrecision}",
        )
    }

    // ----- criterion 3: truncation accuracy, where cancellation is not yet binding ----------

    @Test
    @DisplayName("Shapes up to a million land inside the library's numerical precision")
    fun truncationAccuracyHoldsWhereCancellationIsNotBinding() {
        // Below a shape of a million the cancellation floor is far under defaultNumericalPrecision,
        // so nothing but the convergence test can be responsible for the error. This is the case
        // that fails on the old single-term test and the one that shows the fix works.
        for (row in MEAN_TABLE.filter { it.alpha <= 1.0e6 }) {
            val actual = incompleteGamma(row.z, row.alpha)
            assertTrue(
                abs(actual - row.p) <= KSLMath.defaultNumericalPrecision,
                "alpha=${row.alpha}: got $actual, expected ${row.p}, " +
                        "error ${abs(actual - row.p)} exceeds ${KSLMath.defaultNumericalPrecision}",
            )
        }
    }

    // ----- criterion 2: accuracy against the achievable floor -------------------------------

    @Test
    @DisplayName("Accuracy at the mean tracks the achievable floor from a shape of ten to 1e8")
    fun accuracyAtTheMeanTracksTheAchievableFloor() {
        for (row in MEAN_TABLE) {
            val actual = incompleteGamma(row.z, row.alpha)
            val tol = tolerance(row.alpha, row.z, row.p)
            assertTrue(
                abs(actual - row.p) <= tol,
                "alpha=${row.alpha}: got $actual, expected ${row.p}, " +
                        "error ${abs(actual - row.p)} exceeds tolerance $tol",
            )
        }
    }

    // ----- criterion 4: both branches, standardized offsets ---------------------------------

    @Test
    @DisplayName("Accuracy holds at offsets spanning both branches, not merely at the mean")
    fun accuracyHoldsAcrossBothBranches() {
        // The series handles arguments below alpha + 1 and the continued fraction handles the rest,
        // so a sweep in standard deviations crosses from one to the other between k = 0 and k = 0.5.
        // Testing only at the mean would exercise one branch and miss the crossover entirely.
        var seriesPoints = 0
        var fractionPoints = 0
        for (row in OFFSET_GRID) {
            val actual = incompleteGamma(row.z, row.alpha)
            val tol = tolerance(row.alpha, row.z, row.p)
            assertTrue(
                abs(actual - row.p) <= tol,
                "alpha=${row.alpha} k=${row.k}: got $actual, expected ${row.p}, " +
                        "error ${abs(actual - row.p)} exceeds tolerance $tol",
            )
            if (row.z < row.alpha + 1.0) seriesPoints++ else fractionPoints++
        }
        // Guard the guard: if the grid ever stopped straddling the boundary this test would keep
        // passing while covering only one branch.
        assertTrue(seriesPoints > 0, "no grid point exercised the series branch")
        assertTrue(fractionPoints > 0, "no grid point exercised the continued-fraction branch")
    }

    // ----- criterion 5: the inverse ---------------------------------------------------------

    @Test
    @DisplayName("invCDF matches an independently computed quantile, not merely its own cdf")
    fun inverseMatchesAnIndependentlyComputedQuantile() {
        // invCDF falls back to bisection over the same cdf, so a round-trip test alone is partly
        // circular: a consistently wrong pair passes it. The median below was found by bisecting
        // the high-precision series, independent of anything in this library.
        val g = Gamma(shape = REPRO_SHAPE, scale = REPRO_SCALE)
        val actual = g.invCDF(0.5)
        val relativeError = abs(actual - REPRO_MEDIAN) / REPRO_MEDIAN
        assertTrue(
            relativeError <= KSLMath.defaultNumericalPrecision,
            "invCDF(0.5) was $actual, expected $REPRO_MEDIAN, relative error $relativeError",
        )
    }

    @Test
    @DisplayName("invCDF is monotone and round-trips through cdf")
    fun inverseIsMonotoneAndRoundTrips() {
        val g = Gamma(shape = REPRO_SHAPE, scale = REPRO_SCALE)
        val ps = listOf(0.001, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 0.999)
        var previous = Double.NEGATIVE_INFINITY
        for (p in ps) {
            val x = g.invCDF(p)
            assertTrue(x.isFinite(), "invCDF($p) was not finite: $x")
            assertTrue(x > previous, "invCDF is not increasing: invCDF($p)=$x followed $previous")
            previous = x
            assertTrue(
                abs(g.cdf(x) - p) <= KSLMath.defaultNumericalPrecision,
                "round trip failed at p=$p: cdf(invCDF($p)) = ${g.cdf(x)}",
            )
        }
    }

    // ----- criterion 6: the iteration cap is the caller's to set ----------------------------

    @Test
    @DisplayName("maxNumIterations is stored as given and actually bounds the work")
    fun maxNumIterationsIsHonouredRatherThanClamped() {
        val g = Gamma(shape = REPRO_SHAPE, scale = REPRO_SCALE)
        g.maxNumIterations = 10
        assertEquals(10, g.maxNumIterations, "a value below the default was not stored as given")
        // A cap that small cannot converge for this shape, and saying so is the point of being
        // allowed to set it: the caller asked for a bound and gets one.
        assertFailsWith<KSLTooManyIterationsException>("a cap of 10 should not have sufficed") {
            g.cdf(12.42)
        }
        g.maxNumIterations = Gamma.DEFAULT_MAX_ITERATIONS
        assertTrue(abs(g.cdf(12.42) - REPRO_CDF) <= KSLMath.defaultNumericalPrecision)
    }

    @Test
    @DisplayName("maxNumIterations rejects values that are not positive")
    fun maxNumIterationsRejectsNonPositiveValues() {
        val g = Gamma(shape = 2.0, scale = 1.0)
        assertFailsWith<IllegalArgumentException> { g.maxNumIterations = 0 }
        assertFailsWith<IllegalArgumentException> { g.maxNumIterations = -1 }
    }

    // ----- criterion 8: every entry point, across the window that would have regressed -------

    @Test
    @DisplayName("Every public entry point handles the shapes the shared cap governs")
    fun everyEntryPointHandlesTheSharedCapWindow() {
        // The three iteration constants were independently set to 5000. The stricter convergence
        // test needs more terms than the old one, so any path left at 5000 would have begun
        // throwing around a shape of 7.6e5 -- below the 1.1e6 where it throws today. These shapes
        // sit in that window, and each entry point reaches the same series by a different route.
        for (row in WINDOW_TABLE) {
            val fromStatic = incompleteGamma(row.z, row.alpha)
            assertTrue(
                abs(fromStatic - row.p) <= tolerance(row.alpha, row.z, row.p),
                "static incompleteGammaFunction at alpha=${row.alpha}: got $fromStatic, expected ${row.p}",
            )
            // The same computation through an instance, whose cap is a separate constant.
            val fromInstance = Gamma(shape = row.alpha, scale = 1.0).cdf(row.z)
            assertEquals(
                fromStatic, fromInstance, 0.0,
                "instance cdf disagreed with the static function at alpha=${row.alpha}",
            )
        }
    }

    @Test
    @DisplayName("invChiSquareDistribution reaches the shared cap and inverts consistently")
    fun inverseChiSquareUsesTheSharedCap() {
        // Degrees of freedom of 2e6 put the underlying gamma shape at 1e6, inside the window above.
        val dof = 2.0e6
        val p = 0.5
        val x = Gamma.invChiSquareDistribution(p, dof)
        assertTrue(x.isFinite() && x > 0.0, "invChiSquareDistribution returned $x")
        val back = ChiSquaredDistribution(dof).cdf(x)
        assertTrue(
            abs(back - p) <= 1.0e-6,
            "chi-squared inverse did not round trip: cdf($x) = $back, expected $p",
        )
    }

    @Test
    @DisplayName("The non-recursive Poisson cdf agrees with the recursive one at a large mean")
    fun poissonNonRecursivePathAgreesWithTheRecursiveOne() {
        // The non-recursive path hands its own cap straight to the incomplete gamma. The recursive
        // path does not touch it, so agreement between them tests the shared cap without needing an
        // external reference.
        for (mean in listOf(1.0e3, 1.0e5, 1.0e6)) {
            val j = mean.toInt()
            val recursive = Poisson.poissonCDF(j, mean, true)
            val nonRecursive = Poisson.poissonCDF(j, mean, false)
            assertTrue(
                abs(recursive - nonRecursive) <= 1.0e-7,
                "mean=$mean: recursive $recursive vs non-recursive $nonRecursive",
            )
        }
    }

    // ----- the fixtures ---------------------------------------------------------------------

    companion object {
        private const val TWO_TO_MINUS_53: Double = 1.1102230246251565E-16

        // The fit that provoked the original report: mean 12.4339, standard deviation 0.0074.
        private const val REPRO_SHAPE: Double = 2823002.373060391
        private const val REPRO_SCALE: Double = 4.4045057286650856E-6
        private const val REPRO_CDF: Double = 0.029859478840350871
        private const val REPRO_MEDIAN: Double = 12.433928656011078

        /** Evaluated at the distribution's own mean, where the series is slowest. */
        private val MEAN_TABLE: List<Row> = listOf(
        Row(10.0, 10.0, 0.54207028552814779),
        Row(50.0, 50.0, 0.51880831547204328),
        Row(100.0, 100.0, 0.51329879827914866),
        Row(150.0, 150.0, 0.51085822974935968),
        Row(500.0, 500.0, 0.50594714617076036),
        Row(1000.0, 1000.0, 0.50420524418021551),
        Row(10000.0, 10000.0, 0.50132980833995520),
        Row(100000.0, 100000.0, 0.50042052211036518),
        Row(1000000.0, 1000000.0, 0.50013298076087259),
        Row(10000000.0, 10000000.0, 0.50004205220872370),
        Row(100000000.0, 100000000.0, 0.50001329807601412),        )

        /** Offsets in standard deviations, straddling the series/continued-fraction boundary. */
        private val OFFSET_GRID: List<Offset> = listOf(
        Offset(1000.0, -5.0, 841.88611699158103, 6.4528195904468487e-8),
        Offset(1000.0, -3.0, 905.13167019494862, 0.0010012389950642878),
        Offset(1000.0, -2.0, 936.75444679663241, 0.021016500612428524),
        Offset(1000.0, -1.0, 968.37722339831621, 0.15861399679200106),
        Offset(1000.0, -0.5, 984.18861169915810, 0.31132267480906769),
        Offset(1000.0, 0.0, 1000.0000000000000, 0.50420524418021551),
        Offset(1000.0, 0.5, 1015.8113883008419, 0.69424393281844730),
        Offset(1000.0, 1.0, 1031.6227766016838, 0.84138418842872020),
        Offset(1000.0, 2.0, 1063.2455532033676, 0.97557011269816534),
        Offset(1000.0, 3.0, 1094.8683298050514, 0.99825275449853486),
        Offset(1000.0, 5.0, 1158.1138830084190, 0.99999907338682805),
        Offset(100000.0, -5.0, 98418.861169915810, 2.5100557446060458e-7),
        Offset(100000.0, -3.0, 99051.316701949486, 0.0013127698795992382),
        Offset(100000.0, -2.0, 99367.544467966324, 0.022579128220015534),
        Offset(100000.0, -1.0, 99683.772233983162, 0.15865484973790426),
        Offset(100000.0, -0.5, 99841.886116991581, 0.30881588912510575),
        Offset(100000.0, 0.0, 100000.00000000000, 0.50042052211036518),
        Offset(100000.0, 0.5, 100158.11388300842, 0.69174077499994112),
        Offset(100000.0, 1.0, 100316.22776601684, 0.84134514844832880),
        Offset(100000.0, 2.0, 100632.45553203368, 0.97707940422184562),
        Offset(100000.0, 3.0, 100948.68329805051, 0.99861248631916699),
        Offset(100000.0, 5.0, 101581.13883008419, 0.99999967366210492),
        Offset(1000000.0, -5.0, 995000.00000000000, 2.7495803592700708e-7),
        Offset(1000000.0, -3.0, 997000.00000000000, 0.0013381041673135997),
        Offset(1000000.0, -2.0, 998000.00000000000, 0.022696114006736803),
        Offset(1000000.0, -1.0, 999000.00000000000, 0.15865521357430365),
        Offset(1000000.0, -0.5, 999500.00000000000, 0.30862555689081532),
        Offset(1000000.0, 0.0, 1000000.0000000000, 0.50013298076087259),
        Offset(1000000.0, 0.5, 1000500.0000000000, 0.69155047577149718),
        Offset(1000000.0, 1.0, 1001000.0000000000, 0.84134478636834029),
        Offset(1000000.0, 2.0, 1002000.0000000000, 0.97719590410123014),
        Offset(1000000.0, 3.0, 1003000.0000000000, 0.99863825935378241),
        Offset(1000000.0, 5.0, 1005000.0000000000, 0.99999970125098599),
        Offset(10000000.0, -5.0, 9984188.6116991581, 2.8291057582969451e-7),
        Offset(10000000.0, -3.0, 9990513.1670194949, 0.0013461632071460833),
        Offset(10000000.0, -2.0, 9993675.4446796632, 0.022733055806554059),
        Offset(10000000.0, -1.0, 9996837.7223398316, 0.15865524989770455),
        Offset(10000000.0, -0.5, 9998418.8611699158, 0.30856537211728099),
        Offset(10000000.0, 0.0, 10000000.000000000, 0.50004205220872370),
        Offset(10000000.0, 0.5, 10001581.138830084, 0.69149029429857251),
        Offset(10000000.0, 1.0, 10003162.277660168, 0.84134475010048169),
        Offset(10000000.0, 2.0, 10006324.555320337, 0.97723279730929115),
        Offset(10000000.0, 3.0, 10009486.832980505, 0.99864636226885326),
        Offset(10000000.0, 5.0, 10015811.388300842, 0.99999970956704272),
        Offset(100000000.0, -5.0, 99950000.000000000, 2.8546421399586261e-7),
        Offset(100000000.0, -3.0, 99970000.000000000, 0.0013487164491615506),
        Offset(100000000.0, -2.0, 99980000.000000000, 0.022744732581593558),
        Offset(100000000.0, -1.0, 99990000.000000000, 0.15865525352814383),
        Offset(100000000.0, -0.5, 99995000.000000000, 0.30854634037749220),
        Offset(100000000.0, 0.0, 100000000.00000000, 0.50001329807601412),
        Offset(100000000.0, 0.5, 100005000.00000000, 0.69147126288884494),
        Offset(100000000.0, 1.0, 100010000.00000000, 0.84134474647179881),
        Offset(100000000.0, 2.0, 100020000.00000000, 0.97724446922514480),
        Offset(100000000.0, 3.0, 100030000.00000000, 0.99864891989839804),
        Offset(100000000.0, 5.0, 100050000.00000000, 0.99999971215703131),        )

        /** Shapes in the window that a partial cap increase would have made throw. */
        private val WINDOW_TABLE: List<Row> = listOf(
        Row(800000.0, 800000.0, 0.50014867701071187),
        Row(900000.0, 900000.0, 0.50014017402986639),
        Row(1050000.0, 1050000.0, 0.50012977593420198),        )
    }
}
