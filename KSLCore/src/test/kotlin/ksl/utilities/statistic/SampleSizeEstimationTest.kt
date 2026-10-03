package ksl.utilities.statistic

import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.StudentT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 *  `estimateSampleSizeViaStudentT` looped while the half-width was already small enough, and used n
 *  rather than n - 1 degrees of freedom. It returned 2 whenever two observations were not enough, and
 *  never returned when they were. The first two cases below are pilot values from a team assignment,
 *  for which it reported that 30 pilot replications sufficed when about 181 are needed.
 */
class SampleSizeEstimationTest {

    /** The documented answer, found by direct iteration: the smallest n >= 2 meeting the t condition. */
    private fun smallestStudentTN(h: Double, s: Double, level: Double): Long {
        val p = 1.0 - (1.0 - level) / 2.0
        var n = 2L
        while (StudentT.invCDF(n - 1.0, p) * s / sqrt(n.toDouble()) > h) n++
        return n
    }

    @Test
    fun studentTMatchesTheSmallestNMeetingTheHalfWidth() {
        assertEquals(181L, Statistic.estimateSampleSizeViaStudentT(10.0 / 60.0, 0.8603, 0.99))
        assertEquals(25L, Statistic.estimateSampleSizeViaStudentT(0.5, 1.2110, 0.95))
        assertEquals(387L, Statistic.estimateSampleSizeViaStudentT(1.0, 10.0, 0.95))
        for ((h, s, level) in listOf(Triple(0.3, 2.0, 0.9), Triple(2.0, 3.0, 0.95), Triple(0.05, 0.4, 0.99))) {
            assertEquals(smallestStudentTN(h, s, level), Statistic.estimateSampleSizeViaStudentT(h, s, level))
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun studentTReturnsTwoWhenTwoObservationsAlreadySuffice() {
        assertEquals(2L, Statistic.estimateSampleSizeViaStudentT(100.0, 1.0, 0.95))
        assertEquals(2L, Statistic.estimateSampleSizeViaStudentT(1.0, 0.0, 0.95))
    }

    @Test
    fun studentTIsNeverSmallerThanTheNormalApproximation() {
        for (s in listOf(0.5, 1.0, 5.0, 20.0)) {
            val h = 0.7
            assertTrue(Statistic.estimateSampleSizeViaStudentT(h, s) >= Statistic.estimateSampleSize(h, s))
        }
    }

    @Test
    fun theNormalApproximationRoundsUpAndIsNeverBelowTwo() {
        val z = Normal.stdNormalInvCDF(0.975)
        for ((h, s) in listOf(0.5 to 1.2110, 1.0 to 10.0, 0.25 to 3.0)) {
            val m = (z * s / h) * (z * s / h)
            assertEquals(maxOf(2L, ceil(m).toLong()), Statistic.estimateSampleSize(h, s, 0.95))
        }
        assertEquals(2L, Statistic.estimateSampleSize(100.0, 1.0, 0.95))
        assertEquals(2L, Statistic.estimateSampleSize(1.0, 0.0, 0.95))
    }

    @Test
    fun theProportionEstimateRoundsUpAndIsNeverBelowOne() {
        val z = Normal.stdNormalInvCDF(0.975)
        for ((h, p) in listOf(0.05 to 0.5, 0.03 to 0.2, 0.1 to 0.9)) {
            val m = (z / h) * (z / h) * p * (1.0 - p)
            assertEquals(maxOf(1L, ceil(m).toLong()), Statistic.estimateProportionSampleSize(h, p, 0.95))
        }
        // A half-width so loose that m is below one still asks for one observation.
        assertEquals(1L, Statistic.estimateProportionSampleSize(2.0, 0.5, 0.95))
    }
}
