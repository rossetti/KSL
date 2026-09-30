package ksl.simopt.problem

import ksl.simopt.evaluator.EstimatedResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

/**
 *  A zero sample variance gives the normal-theory interval zero width, so an estimate below the limit
 *  was certified feasible with no evidence. On FacilitySizing, a 0/1 stockout indicator at 30
 *  replications, 187 of 210 run bests had zero variance and the winner verified at P(stockout) = 0.122
 *  against a 0.05 limit. An indicator constraint now uses the exact binomial bound.
 */
class IndicatorFeasibilityTest {

    private fun estimate(events: Int, n: Int): EstimatedResponse {
        val p = events.toDouble() / n
        val variance = if (n > 1) n * p * (1.0 - p) / (n - 1) else Double.NaN
        return EstimatedResponse("stockout", p, variance, n.toDouble())
    }

    private fun lessThan(indicator: Boolean) =
        ResponseConstraint("stockout", 0.05, InequalityType.LESS_THAN, indicator = indicator)

    /** P(X <= x) for X ~ Binomial(n, p). */
    private fun binomialCdf(x: Int, n: Int, p: Double): Double {
        var sum = 0.0
        var coefficient = 1.0
        for (k in 0..x) {
            if (k > 0) coefficient = coefficient * (n - k + 1) / k
            sum += coefficient * p.pow(k) * (1.0 - p).pow(n - k)
        }
        return sum
    }

    @Test
    fun zeroEventsInThirtyIsNotEnoughEvidence() {
        val rc = lessThan(indicator = true)
        assertFalse(rc.testFeasibility(estimate(0, 30), 0.99))
        assertFalse(rc.testFeasibility(estimate(0, 30), 0.95))
        assertEquals(1.0 - 0.01.pow(1.0 / 30) - 0.05, rc.oneSidedUpperResponseInterval(estimate(0, 30), 0.99).upperLimit, 1e-12)
        assertEquals(0.0950, rc.oneSidedUpperResponseInterval(estimate(0, 30), 0.95).upperLimit + 0.05, 5e-4)
    }

    @Test
    fun zeroEventsInOneHundredIsEnough() {
        val rc = lessThan(indicator = true)
        assertTrue(rc.testFeasibility(estimate(0, 100), 0.99))
        assertTrue(rc.testFeasibility(estimate(0, 100), 0.95))
    }

    @Test
    fun theUndeclaredConstraintKeepsTodaysAnswer() {
        // Unchanged by design: only a declared indicator gets the binomial bound.
        assertTrue(lessThan(indicator = false).testFeasibility(estimate(0, 30), 0.99))
    }

    @Test
    fun theBoundIsTheExactClopperPearsonLimit() {
        val rc = lessThan(indicator = true)
        for ((x, n) in listOf(1 to 30, 3 to 50, 10 to 40)) {
            val upper = rc.oneSidedUpperResponseInterval(estimate(x, n), 0.95).upperLimit + 0.05
            assertEquals(0.05, binomialCdf(x, n, upper), 1e-6) { "x = $x, n = $n, upper = $upper" }
        }
    }

    @Test
    fun theGreaterThanMirrorUsesTheLowerBound() {
        val rc = ResponseConstraint("stockout", 0.9, InequalityType.GREATER_THAN, indicator = true)
        // 30 of 30: the 95% lower bound is 0.05^(1/30) = 0.9050, above 0.9.
        assertTrue(rc.testFeasibility(estimate(30, 30), 0.95))
        // 29 of 30: the lower bound falls below 0.9.
        assertFalse(rc.testFeasibility(estimate(29, 30), 0.95))
        val lower = 0.9 - rc.oneSidedUpperResponseInterval(estimate(29, 30), 0.95).upperLimit
        assertEquals(0.95, binomialCdf(28, 30, lower), 1e-6)
    }

    @Test
    fun anIndicatorMustLookLikeOne() {
        val rc = lessThan(indicator = true)
        assertThrows(IllegalArgumentException::class.java) {
            rc.testFeasibility(EstimatedResponse("stockout", 1.3, 0.1, 30.0), 0.95)
        }
        // 3.5 events in 7 replications: a within-replication proportion, not a 0/1 response.
        assertThrows(IllegalArgumentException::class.java) {
            rc.testFeasibility(EstimatedResponse("stockout", 0.5, 0.1, 7.0), 0.95)
        }
    }
}
