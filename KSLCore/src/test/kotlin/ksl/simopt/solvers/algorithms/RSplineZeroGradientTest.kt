package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Regression tests for the zero-pseudo-gradient failure in R-SPLINE's SPLI stage.
 *
 * PLI evaluates the lattice simplex around a perturbed point under common random
 * numbers and builds a pseudo-gradient from adjacent vertex differences. On
 * integer-lattice problems whose CRN-paired objective differences are discrete, an
 * exactly-zero gradient is a positive-probability event (and in one dimension the
 * single component is the whole vector) — the single-item newsvendor crashed 2 of 3
 * benchmark trajectories this way with "The Euclidean norm must be greater than zero".
 *
 * The fixtures make the zero gradient CERTAIN rather than seed-dependent: the true
 * objective is constant, and the noise is drawn from a stream acquired at
 * construction — under CRN, adjacent vertices receive identical noise, so their
 * estimates are exactly equal on every PLI call while the observation variance stays
 * nonzero. A zero interpolated gradient carries no direction information; the solver
 * must skip the line search (the existing no-gradient channel) and complete normally.
 */
@Timeout(60)
class RSplineZeroGradientTest {

    private companion object {
        const val MODEL_ID = "flatResponseFn"
        const val OBJ = "objFn"
    }

    private fun flatProblem(inputNames: List<String>): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "flatProblem",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = inputNames
        )
        for (name in inputNames) {
            pd.inputVariable(name, 0.0, 200.0, granularity = 1.0)
        }
        return pd
    }

    /** A constant true objective plus stream-driven noise: under CRN, adjacent lattice
     *  points see identical draws, so every PLI gradient is exactly zero. */
    private fun flatEvaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1)
                ResponseFunctionIfc { _ -> mapOf(OBJ to 5.0 + stream.randU01()) }
            }
        )
        return Evaluator(pd, oracle)
    }

    @Test
    @DisplayName("A one-dimensional problem with a flat CRN landscape completes instead of crashing")
    fun flatOneDimensionalProblemCompletes() {
        val pd = flatProblem(listOf("x"))
        val solver = RSplineSolver(
            pd, flatEvaluator(pd),
            maxIterations = 3,
            replicationsPerEvaluation = FixedGrowthRateReplicationSchedule(initialNumReps = 8),
            name = "rspline1d"
        )
        solver.startingPoint = pd.toInputMap(mutableMapOf("x" to 96.0))
        solver.runAllIterations()
        val best = solver.bestSolution
        assertNotNull(best)
        assertTrue(best.isInputFeasible())
    }

    @Test
    @DisplayName("A two-dimensional flat CRN landscape routes through the same zero-gradient guard")
    fun flatTwoDimensionalProblemCompletes() {
        val pd = flatProblem(listOf("x1", "x2"))
        val solver = RSplineSolver(
            pd, flatEvaluator(pd),
            maxIterations = 3,
            replicationsPerEvaluation = FixedGrowthRateReplicationSchedule(initialNumReps = 8),
            name = "rspline2d"
        )
        solver.startingPoint = pd.toInputMap(mutableMapOf("x1" to 96.0, "x2" to 71.0))
        solver.runAllIterations()
        val best = solver.bestSolution
        assertNotNull(best)
        assertTrue(best.isInputFeasible())
    }
}
