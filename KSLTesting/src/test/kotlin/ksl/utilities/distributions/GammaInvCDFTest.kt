package ksl.utilities.distributions

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

class GammaInvCDFTest {

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

    private fun assertProbabilityNearly(expected: Double, actual: Double, message: String = "") {
        assertNearly(expected, actual, relTol = 1e-6, absTol = 1e-12, message = message)
    }

    private fun gamma() = Gamma(100.0 / 9.0, 0.9)

    @Test
    fun confirmParameterization() {
        val g = gamma()
        assertNearly(10.0, g.mean())
        assertNearly(9.0, g.variance())
    }

    @Test
    fun cdfPdfAnchors() {
        val g = gamma()
        assertNearly(0.539910118229654, g.cdf(10.0))
        assertNearly(0.13198740184336707, g.pdf(10.0))
    }

    @Test
    fun medianInvCdfAnchor() {
        val g = gamma()
        assertNearly(9.701652924419337, g.invCDF(0.5))
    }

    @Test
    fun roundTripInvCdfOfCdf() {
        val g = gamma()
        val xs = listOf(1.0, 5.0, 10.0, 15.0, 25.0)
        for (x in xs) {
            val p = g.cdf(x)
            val actual = g.invCDF(p)
            assertNearly(
                x, actual,
                message = "Round-trip failed for x=$x (p=$p, observed invCDF=$actual)."
            )
        }
    }

    @Test
    fun directProbabilityInverses() {
        val g = gamma()
        val cases = listOf(
            0.001 to 3.1965849354688705,
            0.01  to 4.359143942436577,
            0.05  to 5.627137653247873,
            0.1   to 6.39914559993132,
            0.5   to 9.701652924419337,
            0.9   to 13.9855116840422,
            0.99  to 18.265424016015142,
            0.999 to 21.86700434958481,
        )
        for ((p, expected) in cases) {
            val x = g.invCDF(p)
            assertNearly(expected, x, message = "invCDF($p) wrong.")
            assertNearly(p, g.cdf(x), message = "cdf(invCDF($p)) wrong.")
        }
    }

    @Test
    fun verySmallProbabilityInverses() {
        val g = gamma()
        val smallPs = listOf(
            1e-12,
            1e-10,
            1e-8,
            2.2288038284173803e-8,
            1e-7,
            1e-6,
            1e-5,
            1e-4,
        )
        for (p in smallPs) {
            val x = g.invCDF(p)
            val roundTripP = g.cdf(x)
            println("p=$p invCDF=$x cdf(invCDF)=$roundTripP")
            assertTrue(x >= 0.0, "invCDF($p)=$x must be non-negative.")
            assertProbabilityNearly(
                p, roundTripP,
                message = "Lower-tail round-trip failed at p=$p (x=$x, cdf(x)=$roundTripP)."
            )
        }
    }

    @Test
    fun knownFailingCaseDiagnostic() {
        val g = gamma()
        val x = 1.0
        val p = g.cdf(x)
        val actual = g.invCDF(p)
        println("shape=${g.shape}, scale=${g.scale}")
        println("input x=$x, p=cdf(x)=$p, invCDF(p)=$actual, cdf(invCDF(p))=${g.cdf(actual)}")
        assertNearly(
            x, actual, relTol = 1e-6, absTol = 1e-6,
            message = "Lower-tail invCDF regression: expected ~1.0, got $actual."
        )
    }

    /**
     * Sweep across shape values to determine whether the lower-tail failure
     * is specific to a particular shape regime or general to small p.
     */
    @Test
    fun shapeSweepLowerTailRoundTrip() {
        val shapes = listOf(0.5, 1.0, 2.0, 5.0, 11.111111111111111, 25.0, 100.0)
        val scales = listOf(0.5, 0.9, 1.0, 3.0)
        val xs = listOf(0.1, 1.0, 5.0)
        for (shape in shapes) {
            for (scale in scales) {
                val g = Gamma(shape, scale)
                for (x in xs) {
                    val p = g.cdf(x)
                    // Skip below the practical noise floor of incompleteGamma for these args.
                    if (p < 1e-30 || p >= 1.0) continue
                    val back = g.invCDF(p)
                    // Round-trip on p (well-defined even where cdf is flat).
                    val pBack = g.cdf(back)
                    // Deep-tail CDF evaluation has its own numerical noise floor;
                    // accept the inverse if the round-trip is within 1e-6 relative.
                    val ok = abs(pBack - p) <= max(1e-12, 1e-6 * p)
                    if (!ok) {
                        println("MISMATCH shape=$shape scale=$scale x=$x p=$p invCDF(p)=$back cdf=$pBack")
                    }
                    assertTrue(
                        ok,
                        "shape=$shape scale=$scale x=$x p=$p invCDF(p)=$back cdf(invCDF(p))=$pBack"
                    )
                }
            }
        }
    }
}
