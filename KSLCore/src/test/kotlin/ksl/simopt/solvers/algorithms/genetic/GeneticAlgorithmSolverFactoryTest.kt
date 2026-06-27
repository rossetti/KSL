package ksl.simopt.solvers.algorithms.genetic

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Phase 4 wiring tests for the genetic algorithm factory methods on [Solver]'s companion. These
 *  build a real (small) M/M/c queueing model so that the full factory path — evaluator
 *  construction, solver configuration, and a short optimization run — is exercised end to end.
 */
class GeneticAlgorithmSolverFactoryTest {

    private companion object {
        const val LENGTH = 200.0
        const val WARMUP = 50.0
        const val RESPONSE = "System Time"
        const val QUEUE_NAME = "MM1Q"
        const val SERVER_CONTROL = "MM1Q.numServers"
    }

    private fun buildModel(modelName: String): Model {
        val model = Model(modelName, autoCSVReports = false)
        model.lengthOfReplication = LENGTH
        model.lengthOfReplicationWarmUp = WARMUP
        GIGcQueue(
            model, numServers = 1,
            ad = ExponentialRV(1.0, 1),
            sd = ExponentialRV(0.5, 2),
            name = QUEUE_NAME
        )
        return model
    }

    private fun modelBuilder(modelName: String): ModelBuilderIfc = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model = buildModel(modelName)
    }

    // A feasible set of 10 integer points (servers 1..10) so the GA can seed a distinct
    // initial population (which must contain at least populationSize unique feasible points).
    private fun problem(modelName: String): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "P_$modelName",
            modelIdentifier = modelName,
            objFnResponseName = RESPONSE,
            inputNames = listOf(SERVER_CONTROL)
        )
        pd.inputVariable(SERVER_CONTROL, lowerBound = 1.0, upperBound = 10.0, granularity = 1.0)
        return pd
    }

    @Test
    fun factoryBuildsAConfiguredGeneticAlgorithmSolver() {
        val name = "GAF_${System.nanoTime()}"
        val solver = Solver.createGeneticAlgorithmSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            populationSize = 6,
            selectionOperator = RankSelection(),
            crossoverOperator = UniformCrossover(),
            maxIterations = 3,
            replicationsPerEvaluation = 2
        )
        assertEquals(6, solver.populationSize, "the factory must wire the requested population size")
        assertEquals(3, solver.maximumNumberIterations, "the factory must wire the requested maximum iterations")
        assertInstanceOf(RankSelection::class.java, solver.selectionOperator, "the factory must wire the selection operator")
        assertInstanceOf(UniformCrossover::class.java, solver.crossoverOperator, "the factory must wire the crossover operator")
        // mutation operator defaults to GaussianMutation when none is supplied
        assertInstanceOf(GaussianMutation::class.java, solver.mutationOperator, "a null mutation operator must default to GaussianMutation")
    }

    @Test
    fun factoryBuiltSolverRunsAndReturnsAFeasibleBest() {
        val name = "GAFrun_${System.nanoTime()}"
        val solver = Solver.createGeneticAlgorithmSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            populationSize = 6,
            maxIterations = 2,
            replicationsPerEvaluation = 2
        )
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the best solution from a factory-built solver must be feasible")
        val servers = best.inputMap.inputValues[0]
        assertTrue(servers in 1.0..10.0, "the best solution must lie within the input bounds (was $servers)")
    }

    @Test
    fun randomRestartFactoryBuildsAndRuns() {
        val name = "GARR_${System.nanoTime()}"
        val solver = Solver.createRandomRestartGeneticAlgorithmSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            maxNumRestarts = 2,
            populationSize = 6,
            maxIterations = 2,
            replicationsPerEvaluation = 2
        )
        assertInstanceOf(RandomRestartSolver::class.java, solver, "the random-restart factory must return a RandomRestartSolver")
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the best solution from the random-restart wrapper must be feasible")
        val servers = best.inputMap.inputValues[0]
        assertTrue(servers in 1.0..10.0, "the best solution must lie within the input bounds (was $servers)")
    }
}
