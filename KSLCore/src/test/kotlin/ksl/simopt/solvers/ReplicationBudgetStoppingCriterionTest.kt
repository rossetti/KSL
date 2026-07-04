package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the replication-budget stopping criterion over the full solver stack
 * (solver → Evaluator → ResponseFunctionOracle), verifying the equal-effort semantics:
 * point solvers stop within one iteration of the budget, population solvers overshoot by
 * at most one generation (with the actual consumption readable for normalization), the
 * iteration ceiling still binds when it is smaller, and the budget binds when it is.
 *
 * Note on the interaction with solver-internal criteria: the standard KSL pattern is
 * `solutionQualityEvaluator ?: <internal convergence checks>`, so assigning the budget
 * criterion supersedes heuristic convergence stops (no-improvement, sampler convergence)
 * while the automatic iteration-limit check still applies. Under an equal-budget
 * benchmark this is the intended semantics: algorithms run to the budget or the
 * iteration ceiling, whichever binds first.
 */
@Timeout(120)
class ReplicationBudgetStoppingCriterionTest {

    private companion object {
        const val MODEL_ID = "noisySphereFn"
        const val OBJ = "objFn"
        const val LARGE_ITERATION_CEILING = 100_000
    }

    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "noisySphereProblem",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("x1", "x2")
        )
        pd.inputVariable("x1", -10.0, 10.0, 1.0)
        pd.inputVariable("x2", -10.0, 10.0, 1.0)
        return pd
    }

    private fun makeEvaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionIfc { inputs, stream ->
                val x1 = inputs.getValue("x1")
                val x2 = inputs.getValue("x2")
                mapOf(OBJ to x1 * x1 + x2 * x2 + stream.randU01())
            }
        )
        return Evaluator(pd, oracle)
    }

    @Test
    @DisplayName("A point solver stops within one iteration of the replication budget")
    fun pointSolverStopsAtReplicationBudget() {
        val budget = 200
        val repsPerEvaluation = 10
        val pd = makeProblem()
        val solver = StochasticHillClimber(
            pd, makeEvaluator(pd),
            maxIterations = LARGE_ITERATION_CEILING,
            replicationsPerEvaluation = repsPerEvaluation
        )
        solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(budget)
        solver.runAllIterations()
        assertTrue(solver.numReplicationsRequested >= budget) {
            "Expected at least $budget requested replications, got ${solver.numReplicationsRequested}"
        }
        // overshoot is bounded by one iteration's consumption (a few evaluations at most)
        assertTrue(solver.numReplicationsRequested <= budget + 10 * repsPerEvaluation) {
            "Overshoot too large: ${solver.numReplicationsRequested} for budget $budget"
        }
        assertTrue(solver.iterationCounter < LARGE_ITERATION_CEILING) {
            "The budget, not the iteration ceiling, should have stopped the solver"
        }
    }

    @Test
    @DisplayName("A population solver overshoots the budget by at most one generation; actual consumption is readable")
    fun populationSolverOvershootsAtMostOneGeneration() {
        // measure one generation's consumption: a budget-free CE run capped at one iteration
        val measurePd = makeProblem()
        val oneGeneration = CrossEntropySolver(
            measurePd, makeEvaluator(measurePd),
            maxIterations = 1,
            replicationsPerEvaluation = 5
        )
        oneGeneration.runAllIterations()
        val generationCost = oneGeneration.numReplicationsRequested
        assertTrue(generationCost > 0)

        val budget = 3 * generationCost
        val pd = makeProblem()
        val solver = CrossEntropySolver(
            pd, makeEvaluator(pd),
            maxIterations = LARGE_ITERATION_CEILING,
            replicationsPerEvaluation = 5
        )
        solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(budget)
        solver.runAllIterations()
        assertTrue(solver.numReplicationsRequested >= budget) {
            "Expected at least $budget requested replications, got ${solver.numReplicationsRequested}"
        }
        assertTrue(solver.numReplicationsRequested <= budget + generationCost) {
            "Overshoot exceeded one generation ($generationCost): ${solver.numReplicationsRequested} " +
                    "for budget $budget"
        }
        assertTrue(solver.iterationCounter < LARGE_ITERATION_CEILING)
    }

    @Test
    @DisplayName("The budget is a ceiling, not a floor: a smaller iteration limit still stops the solver first")
    fun iterationCeilingStillBindsWhenSmaller() {
        val pd = makeProblem()
        val solver = StochasticHillClimber(
            pd, makeEvaluator(pd),
            maxIterations = 3,
            replicationsPerEvaluation = 10
        )
        solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(1_000_000)
        solver.runAllIterations()
        assertEquals(3, solver.iterationCounter)
        assertTrue(solver.numReplicationsRequested < 1_000_000)
    }

    @Test
    @DisplayName("A tiny budget stops the solver after its first iteration")
    fun tinyBudgetStopsAfterFirstIteration() {
        val pd = makeProblem()
        val solver = StochasticHillClimber(
            pd, makeEvaluator(pd),
            maxIterations = LARGE_ITERATION_CEILING,
            replicationsPerEvaluation = 10
        )
        solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(1)
        solver.runAllIterations()
        assertEquals(1, solver.iterationCounter)
    }

    @Test
    @DisplayName("A stateless instance is shareable: the same criterion serves two solvers correctly")
    fun statelessInstanceIsShareableAcrossSolvers() {
        val criterion = ReplicationBudgetStoppingCriterion(150)
        for (i in 1..2) {
            val pd = makeProblem()
            val solver = StochasticHillClimber(
                pd, makeEvaluator(pd),
                maxIterations = LARGE_ITERATION_CEILING,
                replicationsPerEvaluation = 10
            )
            solver.solutionQualityEvaluator = criterion
            solver.runAllIterations()
            assertTrue(solver.numReplicationsRequested >= 150)
            assertTrue(solver.iterationCounter < LARGE_ITERATION_CEILING)
        }
    }

    @Test
    @DisplayName("A non-positive budget is rejected at construction")
    fun nonPositiveBudgetIsRejected() {
        assertThrows<IllegalArgumentException> {
            ReplicationBudgetStoppingCriterion(0)
        }
    }
}
