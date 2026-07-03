package ksl.simopt.solvers.algorithms.isc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [FixedScheduleSAR]: the target schedule grows with iteration, the rule returns only
 *  the shortfall, and early iterations are floored at `n0`.
 */
class SimulationAllocationRuleTest {

    private val pd = IscTestSupport.boxProblem(dim = 1)
    private val rule = FixedScheduleSAR(initialReplications = 5)

    @Test
    fun earlyIterationsAreFlooredAtInitialReplications() {
        assertEquals(5, rule.targetReplications(1), "ln(1)=0 means iteration 1 targets the floor n0")
        assertTrue(rule.targetReplications(2) >= 5, "the target never drops below n0")
    }

    @Test
    fun targetScheduleIsNonDecreasing() {
        var previous = rule.targetReplications(1)
        for (k in 2..100) {
            val current = rule.targetReplications(k)
            assertTrue(current >= previous, "target must be non-decreasing (k=$k: $current < $previous)")
            previous = current
        }
    }

    @Test
    fun additionalReplicationsAreTheShortfall() {
        val k = 50
        val target = rule.targetReplications(k)
        val solWithFew = IscTestSupport.solutionWith(pd, doubleArrayOf(3.0), fx = 1.0, count = 2.0)
        assertEquals(target - 2, rule.additionalReplications(solWithFew, k),
            "additional replications equal target minus the count already accrued")
    }

    @Test
    fun noAdditionalReplicationsWhenAlreadyAtTarget() {
        val k = 10
        val target = rule.targetReplications(k)
        val sol = IscTestSupport.solutionWith(pd, doubleArrayOf(3.0), fx = 1.0, count = target.toDouble())
        assertEquals(0, rule.additionalReplications(sol, k), "no top-up needed when already at target")
        val over = IscTestSupport.solutionWith(pd, doubleArrayOf(3.0), fx = 1.0, count = (target + 100).toDouble())
        assertEquals(0, rule.additionalReplications(over, k), "never returns a negative allocation")
    }
}
