package ksl.simopt.evaluator

import ksl.examples.general.simopt.BuildLKModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the evaluator's evaluation clock: the per-call counter stamped into
 * solutions as their evaluation number (which drives dynamic penalty ramps). The clock
 * must be resettable between independent runs without disturbing the cumulative
 * statistics counters that feed post-run evaluator metrics.
 */
class EvaluationClockTest {

    private fun evaluateOnePoint(evaluator: Evaluator, q: Double): Solution {
        val pd = evaluator.problemDefinition
        val inputs = ModelInputs(
            modelIdentifier = pd.modelIdentifier,
            numReplications = 1,
            inputs = pd.toInputMap(mutableMapOf(
                "Inventory.orderQuantity" to q, "Inventory.reorderPoint" to 10.0)),
            responseNames = pd.allResponseNames.toSet()
        )
        val request = EvaluationRequest(pd.modelIdentifier, listOf(inputs), cachingAllowed = false)
        return evaluator.evaluate(request).values.single()
    }

    @Test
    @DisplayName("Resetting the clock restarts solution evaluation numbers but keeps statistics")
    fun clockResetsIndependentlyOfStatistics() {
        val pd = makeLKInventoryModelProblemDefinition()
        val evaluator = Evaluator.createProblemEvaluator(pd, BuildLKModel)

        val first = evaluateOnePoint(evaluator, 10.0)
        val second = evaluateOnePoint(evaluator, 11.0)
        assertEquals(1, first.evaluationNumber)
        assertEquals(2, second.evaluationNumber)

        // A restart-style consumer resets the clock: the next solution's evaluation
        // number begins at 1 again...
        evaluator.resetEvaluationClock()
        val third = evaluateOnePoint(evaluator, 12.0)
        assertEquals(1, third.evaluationNumber,
            "the clock must restart after resetEvaluationClock()")

        // ...while the cumulative statistics counters are untouched.
        assertEquals(3, evaluator.totalEvaluatorCalls,
            "statistics counters must not be affected by a clock reset")
        assertEquals(3, evaluator.totalDesignPointsEvaluated)
    }

    @Test
    @DisplayName("Every solver run begins its penalty ramp fresh on a reused evaluator")
    fun solverRunsResetTheClock() {
        val pd = makeLKInventoryModelProblemDefinition()
        val evaluator = Evaluator.createProblemEvaluator(pd, BuildLKModel)

        // First run consumes the clock...
        val first = ksl.simopt.solvers.algorithms.StochasticHillClimber(
            problemDefinition = pd, evaluator = evaluator,
            maxIterations = 3, replicationsPerEvaluation = 1, streamNum = 1
        )
        first.runAllIterations()
        assertEquals(1, first.initialSolution!!.evaluationNumber,
            "a fresh evaluator's first run starts at clock 1")

        // ...and a second, independent run on the SAME evaluator starts fresh too.
        val second = ksl.simopt.solvers.algorithms.StochasticHillClimber(
            problemDefinition = pd, evaluator = evaluator,
            maxIterations = 3, replicationsPerEvaluation = 1, streamNum = 1
        )
        second.runAllIterations()
        assertEquals(1, second.initialSolution!!.evaluationNumber,
            "a subsequent run on a reused evaluator must begin its penalty ramp fresh")
    }

    @Test
    @DisplayName("A preliminary starting-point search does not inflate the main search's ramp")
    fun startingPointSearchDoesNotInflateMainRamp() {
        val pd = makeLKInventoryModelProblemDefinition()
        val evaluator = Evaluator.createProblemEvaluator(pd, BuildLKModel)
        // The outer search uses MORE replications than the preliminary search so its
        // initial-point evaluation is a partial cache hit (merge), which stamps the
        // current clock. (A full cache hit would return the cached solution unchanged,
        // still carrying the stamp from when it was first evaluated — cached solutions
        // are immutable records of their original evaluation.)
        val solver = ksl.simopt.solvers.algorithms.StochasticHillClimber(
            problemDefinition = pd, evaluator = evaluator,
            maxIterations = 3, replicationsPerEvaluation = 2, streamNum = 1
        )
        // The inner best-of-random-starts search runs on the same evaluator before the
        // main search begins.
        solver.useRandomlyBestStartingPoint(
            maxRandomStartingPoints = 4,
            replicationsPerRandomStartingPoint = 1
        )
        solver.runAllIterations()
        assertEquals(1, solver.initialSolution!!.evaluationNumber,
            "the main search's ramp must begin fresh after the preliminary search")
    }
}
