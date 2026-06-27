package ksl.simopt.solvers.algorithms.pso

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  End-to-end tests for [ParticleSwarmSolver] using a deterministic in-memory objective. The tests
 *  pin convergence, feasibility, reproducibility, velocity clamping, the dynamic swarm-size hook,
 *  and the diameter-based stopping rule.
 */
class ParticleSwarmSolverTest {

    private val target = doubleArrayOf(3.0, 7.0)

    private fun newSolver(
        streamNum: Int = 1,
        swarmSize: Int = 20,
        maxIterations: Int = 100
    ): ParticleSwarmSolver = PsoTestSupport.makeSolver(
        problemDefinition = PsoTestSupport.boxProblem(dim = 2),
        objective = PsoTestSupport.sphere(target),
        streamNum = streamNum,
        swarmSize = swarmSize,
        maxIterations = maxIterations,
        replicationsPerEvaluation = 1
    )

    @Test
    fun convergesTowardTheKnownOptimum() {
        val solver = newSolver()
        solver.runAllIterations()
        assertTrue(
            solver.bestSolution.estimatedObjFncValue < 1.0,
            "PSO should drive the sphere objective below 1.0 (was ${solver.bestSolution.estimatedObjFncValue})"
        )
    }

    @Test
    fun bestSolutionIsFeasibleAndSwarmIsConsistent() {
        val solver = newSolver(swarmSize = 12, maxIterations = 30)
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the reported best solution must be input feasible")
        best.inputMap.inputValues.forEach { v ->
            assertTrue(v in -10.0..10.0, "the best solution must lie within bounds")
        }
        val swarm = solver.swarm
        assertEquals(12, swarm.size, "the swarm must retain the configured number of particles")
        swarm.forEach { p ->
            // every particle must have been evaluated and have a personal best
            assertTrue(p.currentSolution.isInputFeasible(), "each particle's evaluated position must be feasible")
            assertTrue(p.bestSolution.penalizedObjFncValue <= p.currentSolution.penalizedObjFncValue + 1e-9,
                "each particle's personal best must be no worse than its current solution")
        }
    }

    @Test
    fun runsAreReproducibleForAFixedStreamNumber() {
        val a = newSolver(streamNum = 1)
        val b = newSolver(streamNum = 1)
        a.runAllIterations()
        b.runAllIterations()
        assertEquals(a.bestSolution.inputMap, b.bestSolution.inputMap,
            "the same stream number must reproduce the same best solution inputs")
        assertEquals(a.bestSolution.estimatedObjFncValue, b.bestSolution.estimatedObjFncValue, 1e-12,
            "the same stream number must reproduce the same best objective value")
    }

    @Test
    fun velocitiesAreClampedToVMax() {
        val solver = newSolver(swarmSize = 16, maxIterations = 10)
        solver.runAllIterations()
        // vMax = vMaxFraction (0.2 default) * input range (20) = 4.0
        val vMax = 0.2 * 20.0
        solver.swarm.forEach { p ->
            p.velocity.forEach { v ->
                assertTrue(kotlin.math.abs(v) <= vMax + 1e-9, "each velocity component must be clamped to +/- vMax (was $v)")
            }
        }
    }

    @Test
    fun swarmSizeFunctionOverridesScalar() {
        val solver = newSolver()
        solver.swarmSize = 25
        assertEquals(25, solver.swarmSizeValue(), "without a function the scalar swarm size is used")
        solver.swarmSizeFn = SwarmSizeFnIfc { 6 }
        assertEquals(6, solver.swarmSizeValue(), "the swarm-size function must override the scalar")
        solver.runAllIterations()
        assertEquals(6, solver.swarm.size, "the running swarm must use the function-provided size")
    }

    @Test
    fun diameterBasedStoppingIsEnabledByDefaultAndCanStopEarly() {
        val solver = newSolver(maxIterations = 50)
        assertTrue(solver.diameterBasedStoppingEnabled, "diameter-based stopping should be enabled by default")
        // A threshold above the maximum possible normalized diameter (1.0) makes the criterion
        // trivially satisfied, so the solver should stop after its first iteration.
        solver.diameterThreshold = 5.0
        solver.solutionChecker.noImproveThreshold = 1000 // isolate diameter as the stopping cause
        solver.runAllIterations()
        assertTrue(solver.iterationCounter < 50, "a large diameter threshold should stop the search early (was ${solver.iterationCounter})")
    }

    @Test
    fun disablingDiameterStoppingRunsToTheIterationLimit() {
        val solver = newSolver(maxIterations = 12)
        solver.diameterBasedStoppingEnabled = false
        solver.solutionChecker.noImproveThreshold = 1000 // prevent no-improvement early stop
        solver.runAllIterations()
        assertEquals(12, solver.iterationCounter, "with diameter stopping off and no-improvement disabled, the run should use all iterations")
    }
}
