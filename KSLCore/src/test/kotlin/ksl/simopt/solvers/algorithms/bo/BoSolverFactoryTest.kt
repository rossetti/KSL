package ksl.simopt.solvers.algorithms.bo

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
 *  Wiring tests for the Bayesian optimization factory methods on [Solver]'s companion. These build a
 *  real (small) M/M/c queueing model so the full factory path — evaluator construction, solver
 *  configuration, and a short optimization run — is exercised end to end.
 */
class BoSolverFactoryTest {

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
    fun factoryBuildsAConfiguredBayesianOptimizationSolver() {
        val name = "BOF_${System.nanoTime()}"
        val solver = Solver.createBayesianOptimizationSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            initialDesignSize = 5,
            acquisition = LowerConfidenceBound(beta = 2.0),
            maxIterations = 4,
            replicationsPerEvaluation = 2
        )
        assertEquals(5, solver.initialDesignSize, "the factory must wire the requested initial design size")
        assertEquals(4, solver.maximumNumberIterations, "the factory must wire the requested maximum iterations")
        assertInstanceOf(LowerConfidenceBound::class.java, solver.acquisition, "the factory must wire the acquisition function")
        assertInstanceOf(GaussianProcessModel::class.java, solver.surrogate, "the default surrogate should be a GaussianProcessModel")
    }

    @Test
    fun factoryBuiltSolverRunsAndReturnsAFeasibleBest() {
        val name = "BOFrun_${System.nanoTime()}"
        val solver = Solver.createBayesianOptimizationSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            initialDesignSize = 4,
            maxIterations = 3,
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
        val name = "BORR_${System.nanoTime()}"
        val solver = Solver.createRandomRestartBayesianOptimizationSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            maxNumRestarts = 2,
            initialDesignSize = 4,
            maxIterations = 3,
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
