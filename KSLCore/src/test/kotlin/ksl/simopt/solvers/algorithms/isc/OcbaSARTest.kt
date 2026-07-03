package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [OcbaSAR]: the batch OCBA allocation favors close, noisy competitors over distant
 *  ones, honors degenerate cases, and the per-solution floor delegates to the fixed schedule.
 */
class OcbaSARTest {

    private val pd: ProblemDefinition = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 200.0)

    private fun sol(x: Double, mean: Double, variance: Double, count: Double): Solution {
        val inputMap = pd.toInputMap(doubleArrayOf(x))
        return Solution(inputMap, EstimatedResponse(pd.objFnResponseName, mean, variance, count), emptyList(), 1)
    }

    @Test
    fun allocatesMoreToTheCloseCompetitorThanTheFarOne() {
        val best = sol(1.0, mean = 1.0, variance = 1.0, count = 10.0)
        val close = sol(2.0, mean = 2.0, variance = 4.0, count = 10.0)   // small gap, high variance
        val far = sol(100.0, mean = 100.0, variance = 4.0, count = 10.0) // large gap
        val alloc = OcbaSAR().allocate(listOf(best, close, far), additionalBudget = 100)
        val closeN = alloc[close.inputMap] ?: 0
        val farN = alloc[far.inputMap] ?: 0
        val bestN = alloc[best.inputMap] ?: 0
        assertTrue(closeN > farN, "OCBA must give more to the close competitor ($closeN) than the far one ($farN)")
        assertTrue(bestN > farN, "OCBA must sample the best ($bestN) more than the clearly-dominated far system ($farN)")
    }

    @Test
    fun allocationsAreNonNegative() {
        val a = sol(1.0, 1.0, 2.0, 5.0)
        val b = sol(2.0, 3.0, 2.0, 5.0)
        val c = sol(3.0, 5.0, 2.0, 5.0)
        val alloc = OcbaSAR().allocate(listOf(a, b, c), additionalBudget = 50)
        alloc.values.forEach { assertTrue(it >= 0, "every OCBA allocation must be non-negative") }
    }

    @Test
    fun singleSolutionReceivesTheWholeBudget() {
        val only = sol(1.0, 1.0, 2.0, 5.0)
        val alloc = OcbaSAR().allocate(listOf(only), additionalBudget = 30)
        assertEquals(30, alloc[only.inputMap], "a lone system receives the entire budget")
    }

    @Test
    fun zeroBudgetAllocatesNothing() {
        val a = sol(1.0, 1.0, 2.0, 5.0)
        val b = sol(2.0, 3.0, 2.0, 5.0)
        assertTrue(OcbaSAR().allocate(listOf(a, b), additionalBudget = 0).isEmpty(),
            "a zero budget must allocate nothing")
    }

    @Test
    fun perSolutionFloorMatchesTheFixedSchedule() {
        val s = sol(1.0, 1.0, 2.0, 2.0)
        val ocba = OcbaSAR(initialReplications = 5)
        val fixed = FixedScheduleSAR(initialReplications = 5)
        assertEquals(fixed.additionalReplications(s, 50), ocba.additionalReplications(s, 50),
            "the OCBA per-solution floor must match the fixed schedule it delegates to")
    }
}
