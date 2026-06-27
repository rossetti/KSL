package ksl.simopt.solvers.algorithms.bo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  End-to-end tests for [BayesianOptimizationSolver] using a deterministic in-memory objective. The
 *  tests pin convergence, feasibility, reproducibility, archive management, acquisition/fitter
 *  interchange, and the incumbent accessor.
 */
class BayesianOptimizationSolverTest {

    private val target = doubleArrayOf(3.0, 7.0)

    private fun newSolver(
        streamNum: Int = 1,
        initialDesignSize: Int = 8,
        maxIterations: Int = 25,
        acquisition: AcquisitionFunctionIfc = ExpectedImprovement(),
        hyperparameterFitter: HyperparameterFitterIfc = FixedHyperparameters()
    ): BayesianOptimizationSolver = BoTestSupport.makeSolver(
        problemDefinition = BoTestSupport.boxProblem(dim = 2),
        objective = BoTestSupport.sphere(target),
        streamNum = streamNum,
        initialDesignSize = initialDesignSize,
        maxIterations = maxIterations,
        replicationsPerEvaluation = 1,
        acquisition = acquisition,
        hyperparameterFitter = hyperparameterFitter
    )

    @Test
    fun convergesTowardTheKnownOptimum() {
        val solver = newSolver()
        solver.runAllIterations()
        assertTrue(
            solver.bestSolution.estimatedObjFncValue < 2.0,
            "BO should drive the sphere objective below 2.0 (was ${solver.bestSolution.estimatedObjFncValue})"
        )
    }

    @Test
    fun bestSolutionIsFeasibleAndWithinBounds() {
        val solver = newSolver(maxIterations = 12)
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the reported best solution must be input feasible")
        best.inputMap.inputValues.forEach { v ->
            assertTrue(v in -10.0..10.0, "every coordinate of the best solution must be within bounds")
        }
    }

    @Test
    fun runsAreReproducibleForAFixedStreamNumber() {
        val a = newSolver(streamNum = 1, maxIterations = 12)
        val b = newSolver(streamNum = 1, maxIterations = 12)
        a.runAllIterations()
        b.runAllIterations()
        assertEquals(a.bestSolution.inputMap, b.bestSolution.inputMap,
            "the same stream number must reproduce the same best solution inputs")
        assertEquals(a.bestSolution.estimatedObjFncValue, b.bestSolution.estimatedObjFncValue, 1e-12,
            "the same stream number must reproduce the same best objective value")
    }

    @Test
    fun archiveGrowsWithEachIteration() {
        val solver = newSolver(initialDesignSize = 6, maxIterations = 10)
        solver.runAllIterations()
        val size = solver.observedSolutions.size
        assertTrue(size in 6..(6 + 10),
            "the archive should contain the initial design plus up to one point per iteration (was $size)")
    }

    @Test
    fun maxArchiveSizeCapsTheTrainingSet() {
        val solver = newSolver(initialDesignSize = 8, maxIterations = 15)
        solver.maxArchiveSize = 10
        solver.runAllIterations()
        assertTrue(solver.observedSolutions.size <= 10,
            "the archive must not exceed maxArchiveSize (was ${solver.observedSolutions.size})")
    }

    @Test
    fun lowerConfidenceBoundAcquisitionAlsoConverges() {
        val solver = newSolver(acquisition = LowerConfidenceBound(beta = 2.0))
        solver.runAllIterations()
        assertTrue(solver.bestSolution.estimatedObjFncValue < 2.0,
            "LCB acquisition should also converge (was ${solver.bestSolution.estimatedObjFncValue})")
    }

    @Test
    fun mleHyperparameterFitterRunsAndConverges() {
        val solver = newSolver(hyperparameterFitter = MleHyperparameterFitter(numStarts = 5))
        solver.runAllIterations()
        assertTrue(solver.bestSolution.estimatedObjFncValue < 2.0,
            "BO with MLE hyperparameter fitting should converge (was ${solver.bestSolution.estimatedObjFncValue})")
    }

    @Test
    fun incumbentValueIsAvailableAfterRun() {
        val solver = newSolver(maxIterations = 10)
        solver.runAllIterations()
        assertTrue(solver.currentIncumbentValue.isFinite(), "the incumbent value should be finite after a run")
    }
}
