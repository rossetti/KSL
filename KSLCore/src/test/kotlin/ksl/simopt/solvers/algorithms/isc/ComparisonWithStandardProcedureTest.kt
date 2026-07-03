package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [ComparisonWithStandardProcedure] (Kim 2005): the closed-form constant, the
 *  positive-delta requirement, and the two decision outcomes (standard best vs. a better alternative).
 */
class ComparisonWithStandardProcedureTest {

    private val pd: ProblemDefinition = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 100.0)

    /** A deterministic single-replication draw for the point at the supplied fixed objective value. */
    private fun deterministicSampler(values: Map<InputMap, Double>): (InputMap) -> Solution =
        { input -> IscTestSupport.solutionWith(pd, input.inputValues, values.getValue(input), count = 2.0) }

    private fun sol(x: Double, fx: Double, count: Double = 10.0): Solution =
        IscTestSupport.solutionWith(pd, doubleArrayOf(x), fx, count)

    @Test
    fun etaIsPositiveForSmallBeta() {
        val proc = ComparisonWithStandardProcedure(alpha = 0.05, delta = 1.0, n0 = 10)
        assertTrue(proc.eta(0.025) > 0.0, "eta must be positive for a small per-alternative error")
    }

    @Test
    fun zeroDeltaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ComparisonWithStandardProcedure(alpha = 0.05, delta = 0.0, n0 = 10)
        }
    }

    @Test
    fun standardIsSelectedWhenAllAlternativesAreClearlyWorse() {
        val proc = ComparisonWithStandardProcedure(alpha = 0.05, delta = 1.0, n0 = 10)
        val standard = sol(5.0, fx = 1.0)
        val alternatives = listOf(sol(4.0, fx = 10.0), sol(6.0, fx = 12.0))
        val values = mapOf(
            standard.inputMap to 1.0,
            alternatives[0].inputMap to 10.0,
            alternatives[1].inputMap to 12.0
        )
        val result = proc.run(standard, alternatives, deterministicSampler(values), ::mergeSolutions)
        assertTrue(result.standardIsBest, "the standard must be selected when every alternative is worse")
        assertEquals(standard.inputMap, result.winner.inputMap)
    }

    @Test
    fun betterAlternativeIsSelectedWhenItDominatesTheStandard() {
        val proc = ComparisonWithStandardProcedure(alpha = 0.05, delta = 1.0, n0 = 10)
        val standard = sol(5.0, fx = 10.0)
        val better = sol(4.0, fx = 1.0)
        val worse = sol(6.0, fx = 12.0)
        val values = mapOf(
            standard.inputMap to 10.0,
            better.inputMap to 1.0,
            worse.inputMap to 12.0
        )
        val result = proc.run(standard, listOf(better, worse), deterministicSampler(values), ::mergeSolutions)
        assertFalse(result.standardIsBest, "a clearly better alternative must defeat the standard")
        assertEquals(better.inputMap, result.winner.inputMap, "the better alternative must be the winner")
    }

    @Test
    fun emptyAlternativesTriviallySelectsTheStandard() {
        val proc = ComparisonWithStandardProcedure(alpha = 0.05, delta = 1.0, n0 = 10)
        val standard = sol(5.0, fx = 1.0)
        val result = proc.run(standard, emptyList(), { standard }, ::mergeSolutions)
        assertTrue(result.standardIsBest, "with no alternatives the standard is best by default")
    }
}
