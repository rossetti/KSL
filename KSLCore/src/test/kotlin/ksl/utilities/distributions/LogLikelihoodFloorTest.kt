package ksl.utilities.distributions

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.LognormalRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/**
 *  The default log-likelihood treated any density below about 1.05e-8 as zero and gave it
 *  ln(Double.MIN_VALUE) = -744.44. A Gamma(2, 1) observation at 25 has density 3.47e-10 and log
 *  density -21.78, so that one point added about 1,450 to BIC. Only a density that is exactly zero
 *  is floored now.
 */
class LogLikelihoodFloorTest {

    private val floor = ln(Double.MIN_VALUE)

    @Test
    fun smallPositiveDensitiesKeepTheirTrueLog() {
        val g = Gamma(shape = 2.0, scale = 1.0)
        assertEquals(ln(g.pdf(25.0)), g.logLikelihood(25.0), 1e-10)
        // Generalized beta relies on the default method, and is the candidate the defect hit hardest.
        val b = GeneralizedBeta(alphaShape = 2.0, betaShape = 3.0, minimum = 0.0, maximum = 10.0)
        val x = 9.9999
        assertTrue(b.pdf(x) in 0.0..1.0e-8)
        assertEquals(ln(b.pdf(x)), b.logLikelihood(x), 1e-9)
    }

    @Test
    fun theSumIsTheSumOfLnPdf() {
        val g = Gamma(shape = 2.0, scale = 1.0)
        val data = doubleArrayOf(0.5, 2.0, 5.0, 25.0, 30.0)
        assertEquals(data.sumOf { ln(g.pdf(it)) }, g.sumLogLikelihood(data), 1e-9)
    }

    @Test
    fun aZeroDensityIsStillFloored() {
        assertEquals(floor, Gamma(shape = 2.0, scale = 1.0).logLikelihood(-1.0), 0.0)
        assertEquals(floor, Lognormal(mean = 1.0, variance = 1.0).logLikelihood(0.0), 0.0)
        assertEquals(floor, Weibull(shape = 2.0, scale = 1.0).logLikelihood(-3.0), 0.0)
        // PearsonType5 used to take the log of a non-positive x and return NaN.
        assertEquals(floor, PearsonType5(shape = 2.0, scale = 1.0).logLikelihood(0.0), 0.0)
        assertEquals(floor, PearsonType5(shape = 2.0, scale = 1.0).logLikelihood(-1.0), 0.0)
    }

    @Test
    fun theExactOverridesAgreeWithLnPdfOnTheSupport() {
        val grid = doubleArrayOf(0.01, 0.3, 1.0, 2.5, 7.0, 15.0)
        val families = listOf(
            Gamma(shape = 2.0, scale = 1.5), Gamma(shape = 0.7, scale = 2.0),
            Lognormal(mean = 3.0, variance = 4.0), Weibull(shape = 1.7, scale = 3.0), Weibull(shape = 0.8, scale = 1.0)
        )
        for (f in families) {
            for (x in grid) {
                assertEquals(ln(f.pdf(x)), f.logLikelihood(x), 1e-9) { "$f at $x" }
            }
        }
    }

    @Test
    fun theExactOverridesStayFiniteWherePdfUnderflows() {
        val g = Gamma(shape = 2.0, scale = 1.0)
        assertEquals(0.0, g.pdf(800.0))
        assertEquals(ln(800.0) - 800.0, g.logLikelihood(800.0), 1e-9)
        val w = Weibull(shape = 2.0, scale = 1.0)
        assertEquals(0.0, w.pdf(40.0))
        assertEquals(ln(2.0) + ln(40.0) - 1600.0, w.logLikelihood(40.0), 1e-9)
        // Far enough out that the exact value lies below the floor, which is allowed: the fit is
        // that bad, and says so.
        val l = Lognormal(mean = 1.0, variance = 0.01)
        assertEquals(0.0, l.pdf(1.0e6))
        val sigma = kotlin.math.sqrt(ln(1.0 + 0.01))
        val mu = -0.5 * sigma * sigma
        val z = (ln(1.0e6) - mu) / sigma
        val exact = -ln(1.0e6) - ln(sigma) - 0.5 * ln(2.0 * Math.PI) - 0.5 * z * z
        assertEquals(exact, l.logLikelihood(1.0e6), 1e-6 * kotlin.math.abs(exact))
        assertTrue(l.logLikelihood(1.0e6) < floor)
    }

    @Test
    fun thePdfModelerBootstrapCanUseItsOwnStreams() {
        val rv = LognormalRV(8.75, 3.5.pow(2), streamNum = 13, streamProvider = RNStreamProvider())
        val data = DoubleArray(180) { round(rv.value * 100.0) / 100.0 }
        val first = PDFModeler(data, bootstrapStreamNumber = 1, bootstrapStreamProvider = RNStreamProvider())
            .confidenceIntervalForMinimum(399, 0.95)
        val second = PDFModeler(data, bootstrapStreamNumber = 1, bootstrapStreamProvider = RNStreamProvider())
            .confidenceIntervalForMinimum(399, 0.95)
        assertEquals(first.lowerLimit, second.lowerLimit, 0.0)
        assertEquals(first.upperLimit, second.upperLimit, 0.0)
    }
}
