package ksl.simopt.solvers.algorithms.genetic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  End-to-end tests for [GeneticAlgorithmSolver] using a deterministic in-memory objective. The
 *  tests pin convergence, elitism, reproducibility, operator interchangeability, and the dynamic
 *  parameter hooks.
 */
class GeneticAlgorithmSolverTest {

    private val target = doubleArrayOf(3.0, 7.0)

    private fun newSolver(
        streamNum: Int = 1,
        selection: SelectionOperatorIfc = TournamentSelection(),
        crossover: CrossoverOperatorIfc = BlendCrossover(),
        mutation: MutationOperatorIfc? = null
    ): GeneticAlgorithmSolver {
        val pd = GeneticTestSupport.boxProblem(dim = 2)
        return GeneticTestSupport.makeSolver(
            problemDefinition = pd,
            objective = GeneticTestSupport.sphere(target),
            streamNum = streamNum,
            populationSize = 40,
            maxIterations = 100,
            replicationsPerEvaluation = 1,
            selectionOperator = selection,
            crossoverOperator = crossover,
            mutationOperator = mutation ?: GaussianMutation(pd)
        )
    }

    @Test
    fun convergesTowardTheKnownOptimum() {
        val solver = newSolver()
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(
            best.estimatedObjFncValue < 1.0,
            "the GA should drive the sphere objective below 1.0 (was ${best.estimatedObjFncValue})"
        )
    }

    @Test
    fun bestSolutionIsInputFeasibleAndWithinBounds() {
        val solver = newSolver()
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the reported best solution must be input feasible")
        best.inputMap.inputValues.forEach { v ->
            assertTrue(v in -10.0..10.0, "every coordinate of the best solution must be within bounds")
        }
    }

    @Test
    fun elitismKeepsBestNoWorseThanTheInitialPopulation() {
        val solver = newSolver()
        solver.runAllIterations()
        val initialBest = solver.initialSolution!!.penalizedObjFncValue
        val finalBest = solver.bestSolution.penalizedObjFncValue
        assertTrue(
            finalBest <= initialBest + 1e-9,
            "with elitism the best penalized objective must never worsen (initial=$initialBest, final=$finalBest)"
        )
    }

    @Test
    fun runsAreReproducibleForAFixedStreamNumber() {
        val a = newSolver(streamNum = 1)
        val b = newSolver(streamNum = 1)
        a.runAllIterations()
        b.runAllIterations()
        assertEquals(
            a.bestSolution.inputMap, b.bestSolution.inputMap,
            "the same stream number must reproduce the same best solution inputs"
        )
        assertEquals(
            a.bestSolution.estimatedObjFncValue, b.bestSolution.estimatedObjFncValue, 1e-12,
            "the same stream number must reproduce the same best objective value"
        )
    }

    @Test
    fun swappingOperatorsChangesBehaviorButStillConverges() {
        val pdB = GeneticTestSupport.boxProblem(dim = 2)
        val defaultSolver = newSolver(streamNum = 1)
        val swappedSolver = GeneticTestSupport.makeSolver(
            problemDefinition = pdB,
            objective = GeneticTestSupport.sphere(target),
            streamNum = 1,
            populationSize = 40,
            maxIterations = 100,
            replicationsPerEvaluation = 1,
            selectionOperator = RankSelection(),
            crossoverOperator = UniformCrossover(),
            mutationOperator = UniformResetMutation(pdB)
        )
        defaultSolver.runAllIterations()
        swappedSolver.runAllIterations()
        // Both configurations should solve the easy problem...
        assertTrue(defaultSolver.bestSolution.estimatedObjFncValue < 1.0, "default operators should converge")
        assertTrue(swappedSolver.bestSolution.estimatedObjFncValue < 1.0, "swapped operators should converge")
        // ...but along different trajectories, so the exact best inputs should differ.
        assertNotEquals(
            defaultSolver.bestSolution.inputMap, swappedSolver.bestSolution.inputMap,
            "different operators should yield a different search trajectory"
        )
    }

    @Test
    fun dynamicHooksOverrideScalarParameters() {
        val solver = newSolver()
        solver.populationSize = 25
        solver.mutationRate = 0.1
        assertEquals(25, solver.populationSizeValue(), "without a function the scalar population size is used")
        assertEquals(0.1, solver.mutationRateValue(), 1e-12, "without a function the scalar mutation rate is used")

        solver.populationSizeFn = PopulationSizeFnIfc { 7 }
        solver.mutationRateFn = MutationRateFnIfc { 0.42 }
        assertEquals(7, solver.populationSizeValue(), "the population-size function must override the scalar")
        assertEquals(0.42, solver.mutationRateValue(), 1e-12, "the mutation-rate function must override the scalar")
    }

    @Test
    fun respectsTheMaximumNumberOfIterations() {
        val solver = newSolver()
        solver.runAllIterations()
        assertTrue(
            solver.iterationCounter in 1..solver.maximumNumberIterations,
            "the iteration counter must not exceed the configured maximum"
        )
    }
}
