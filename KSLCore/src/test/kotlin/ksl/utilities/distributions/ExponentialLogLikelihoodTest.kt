package ksl.utilities.distributions

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  `Exponential.logLikelihood` added x/mean instead of subtracting it, so it disagreed with both
 *  ln(pdf) and `sumLogLikelihood`. At mean 2 and x = 3 it returned +0.807, a value no density bounded
 *  by 1/mean can have; the true value is -2.193.
 */
class ExponentialLogLikelihoodTest {

    private val e = Exponential(2.0)

    @Test
    fun perPointLogLikelihoodIsLnPdf() {
        for (x in doubleArrayOf(0.0, 0.1, 1.0, 3.0, 10.0)) {
            assertEquals(ln(e.pdf(x)), e.logLikelihood(x), 1e-12, "x = $x")
        }
    }

    @Test
    fun theSumAgreesWithThePerPointValues() {
        val data = doubleArrayOf(0.1, 1.0, 3.0, 10.0)
        assertEquals(data.sumOf { e.logLikelihood(it) }, e.sumLogLikelihood(data), 1e-12)
    }

    @Test
    fun aNegativeObservationGetsTheFloorInBothMethods() {
        val floor = ln(Double.MIN_VALUE)
        assertEquals(floor, e.logLikelihood(-1.0), 0.0)
        val data = doubleArrayOf(1.0, -1.0, 3.0)
        assertEquals(data.sumOf { e.logLikelihood(it) }, e.sumLogLikelihood(data), 1e-9)
    }

    @Test
    fun roundOffBelowZeroIsTreatedAsZeroAsThePdfDoes() {
        val x = -1.0e-12
        assertEquals(ln(e.pdf(x)), e.logLikelihood(x), 1e-12)
    }
}
