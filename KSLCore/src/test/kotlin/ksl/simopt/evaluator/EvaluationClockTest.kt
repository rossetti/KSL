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
}
