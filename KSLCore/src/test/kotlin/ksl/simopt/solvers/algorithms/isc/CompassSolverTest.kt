package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  End-to-end tests for [CompassSolver] on a deterministic integer sphere: convergence to the known
 *  optimum (degraded and δ_L > 0 modes), feasibility, and reproducibility for a fixed stream.
 */
class CompassSolverTest {

    private val target = doubleArrayOf(3.0, 4.0)

    private fun makeSolver(
        streamNum: Int = 1,
        deltaL: Double = 0.0,
        maxIterations: Int = 100
    ): CompassSolver {
        val pd: ProblemDefinition = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 1.0)
        val evaluator = IscTestSupport.FunctionEvaluator(pd, IscTestSupport.sphere(target))
        return CompassSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = streamNum,
            sampleSize = 4,
            deltaL = deltaL,
            maxIterations = maxIterations,
            replicationsPerEvaluation = 3
        )
    }

    @Test
    fun convergesToTheKnownIntegerOptimumInDegradedMode() {
        val solver = makeSolver()
        solver.runAllIterations()
        val best = solver.bestSolution
        assertEquals(target.toList(), best.inputMap.inputValues.toList(),
            "COMPASS should descend the separable convex sphere to its integer optimum")
        assertTrue(best.estimatedObjFncValue < 1e-9, "the objective at the optimum should be ~0")
    }

    @Test
    fun bestSolutionIsFeasibleAndWithinBounds() {
        val solver = makeSolver(maxIterations = 30)
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the reported best solution must be input feasible")
        best.inputMap.inputValues.forEach { v ->
            assertTrue(v in 0.0..10.0, "every coordinate of the best solution must be within bounds")
        }
    }

    @Test
    fun runsAreReproducibleForAFixedStreamNumber() {
        val a = makeSolver(streamNum = 5, maxIterations = 25)
        val b = makeSolver(streamNum = 5, maxIterations = 25)
        a.runAllIterations()
        b.runAllIterations()
        assertEquals(a.bestSolution.inputMap, b.bestSolution.inputMap,
            "the same stream number must reproduce the same best inputs")
    }

    @Test
    fun replicationCapStopsTheLocalSearchBeforeItsIterationCeiling() {
        // A cap of one replication forces COMPASS to stop after its first iteration; the replication
        // cap, not the iteration ceiling, is what stops it.
        val capped = makeSolver(maxIterations = 100).also { it.maxReplications = 1 }
        capped.runAllIterations()
        assertEquals(1, capped.iterationCounter,
            "a one-replication cap must stop COMPASS after its first iteration")

        // With the default (generous) cap the same search runs multiple iterations and consumes more.
        val generous = makeSolver(maxIterations = 100)
        generous.runAllIterations()
        assertTrue(generous.iterationCounter > 1,
            "with a generous cap COMPASS should run multiple iterations to descend the sphere")
        assertTrue(generous.numReplicationsRequested > capped.numReplicationsRequested,
            "the generously-capped run should consume more replications than the tightly-capped one")
    }

    @Test
    fun rejectsNonPositiveMaxReplications() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            makeSolver().also { it.maxReplications = 0 }
        }
    }

    @Test
    fun convergesWithPositiveDeltaLAndLocalOptimalityTest() {
        val solver = makeSolver(deltaL = 1.0, maxIterations = 100)
        solver.runAllIterations()
        val best = solver.bestSolution
        assertEquals(target.toList(), best.inputMap.inputValues.toList(),
            "COMPASS with the Kim (2005) local-optimality test should also reach the integer optimum")
    }

    @Test
    fun rejectsAContinuousProblem() {
        // COMPASS assumes an integer lattice (unit von Neumann neighborhood); a continuous
        // (granularity 0) problem must be rejected at construction, mirroring R-SPLINE.
        val pd = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 0.0)
        val evaluator = IscTestSupport.FunctionEvaluator(pd, IscTestSupport.sphere(target))
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            CompassSolver(
                problemDefinition = pd,
                evaluator = evaluator,
                streamNum = 1,
                sampleSize = 4,
                replicationsPerEvaluation = 3
            )
        }
    }

    @Test
    fun honorsInheritedStartingPoint() {
        // The inherited Solver.startingPoint var must be honored (no longer a silent no-op):
        // with no ISC niche seed set, COMPASS must begin its search at the supplied point.
        val pd = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 1.0)
        val evaluator = IscTestSupport.FunctionEvaluator(pd, IscTestSupport.sphere(target))
        val solver = CompassSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            sampleSize = 4,
            maxIterations = 5,
            replicationsPerEvaluation = 3
        )
        val start = pd.toInputMap(doubleArrayOf(7.0, 2.0))
        solver.startingPoint = start
        solver.runAllIterations()
        assertEquals(start, solver.initialSolution?.inputMap,
            "COMPASS must begin at the supplied inherited startingPoint")
    }

    @Test
    fun localOptimalityTestDoesNotDoubleCountReplications() {
        // Regression (A3): after the Kim local-optimality test, the winner was merged back into
        // `visited` even though it already carried its accumulated replications, doubling its
        // pre-test count (2*prior + additional). Invariant: no visited solution may carry more
        // replications than the evaluator actually served for that point. deltaL > 0 forces the
        // local-optimality test to run (and write a winner back). A wrapping evaluator records the
        // per-point served counts.
        val pd = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 1.0)
        val base = IscTestSupport.FunctionEvaluator(pd, IscTestSupport.sphere(target))
        val served = HashMap<InputMap, Double>()
        val evaluator = object : EvaluatorIfc by base {
            override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
                val result = base.evaluate(evaluationRequest)
                for ((_, sol) in result) served.merge(sol.inputMap, sol.count) { a, b -> a + b }
                return result
            }
        }
        val solver = CompassSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            sampleSize = 4,
            deltaL = 1.0,
            maxIterations = 100,
            replicationsPerEvaluation = 3
        )
        solver.runAllIterations()
        for ((inputMap, sol) in solver.visitedSolutions) {
            assertTrue(sol.count <= served.getOrDefault(inputMap, 0.0) + 1e-6,
                "visited replications (${sol.count}) must not exceed the served count " +
                    "(${served[inputMap]}) for $inputMap — a double-count corrupts clean-up's inputs")
        }
    }
}
