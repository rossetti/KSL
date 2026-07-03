package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Integration test: [CompassSolver] driven by the batch [OcbaSAR] allocation rule still converges to
 *  the known integer optimum, exercising the solver's batch-allocation path.
 */
class CompassWithOcbaTest {

    private val target = doubleArrayOf(3.0, 4.0)

    @Test
    fun compassWithOcbaConvergesToTheKnownOptimum() {
        val pd: ProblemDefinition = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 1.0)
        val evaluator = IscTestSupport.FunctionEvaluator(pd, IscTestSupport.sphere(target))
        val solver = CompassSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            sampleSize = 4,
            sar = OcbaSAR(),
            maxIterations = 100,
            replicationsPerEvaluation = 3
        )
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the best solution must be feasible")
        assertEquals(target.toList(), best.inputMap.inputValues.toList(),
            "COMPASS driven by OCBA allocation should still reach the integer optimum")
    }
}
