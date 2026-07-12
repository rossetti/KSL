package ksl.simopt.solvers.algorithms.isc

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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Wiring tests for the approved Industrial Strength COMPASS factory methods on [Solver]'s companion.
 *  They build a small M/M/c queueing model so the full factory path — evaluator construction, solver
 *  configuration, and a short optimization run — is exercised end to end. The global Niching-GA phase
 *  is skipped (the unimodal COMPASS-only shortcut) because the server-count domain has too few
 *  distinct feasible points to fill a default GA population.
 */
class ISCSolverFactoryTest {

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
    fun factoryBuildsAndRunsAnISCSolver() {
        val name = "ISCF_${System.nanoTime()}"
        val solver = Solver.createISCSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            skipGlobalPhase = true,
            maxIterations = 50,
            replicationsPerEvaluation = 2
        )
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the best solution from a factory-built ISC solver must be feasible")
        val servers = best.inputMap.inputValues[0]
        assertTrue(servers in 1.0..10.0, "the best solution must lie within the input bounds (was $servers)")
    }

    @Test
    fun randomRestartFactoryBuildsAndRuns() {
        val name = "ISCRR_${System.nanoTime()}"
        val solver = Solver.createRandomRestartISCSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            maxNumRestarts = 2,
            skipGlobalPhase = true,
            maxIterations = 50,
            replicationsPerEvaluation = 2
        )
        assertInstanceOf(RandomRestartSolver::class.java, solver, "the random-restart factory must return a RandomRestartSolver")
        solver.runAllIterations()
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the best solution from the random-restart wrapper must be feasible")
        val servers = best.inputMap.inputValues[0]
        assertTrue(servers in 1.0..10.0, "the best solution must lie within the input bounds (was $servers)")
    }

    @Test
    fun factoryForwardsStartingPointToTheSolver() {
        val name = "ISCSP_${System.nanoTime()}"
        val solver = Solver.createISCSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            skipGlobalPhase = true,
            startingPoint = mutableMapOf(SERVER_CONTROL to 7.0),
            maxIterations = 50,
            replicationsPerEvaluation = 2
        )
        val seeded = solver.startingPoint
        assertNotNull(seeded, "a startingPoint passed to the factory must be forwarded to the solver")
        assertEquals(7.0, seeded!!.inputValues[0], "the forwarded startingPoint must carry the supplied coordinate")

        // Control: omitting the startingPoint leaves it null so the solver auto-generates one at run time.
        val auto = Solver.createISCSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            skipGlobalPhase = true,
            maxIterations = 50,
            replicationsPerEvaluation = 2
        )
        assertNull(auto.startingPoint, "omitting the startingPoint must leave it null (auto-generated at run time)")
    }

    @Test
    fun randomRestartFactoryForwardsStartingPoint() {
        val name = "ISCRRSP_${System.nanoTime()}"
        val solver = Solver.createRandomRestartISCSolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            maxNumRestarts = 2,
            skipGlobalPhase = true,
            startingPoint = mutableMapOf(SERVER_CONTROL to 4.0),
            maxIterations = 50,
            replicationsPerEvaluation = 2
        )
        val seeded = solver.startingPoint
        assertNotNull(seeded, "a startingPoint passed to the random-restart factory must reach the wrapper")
        assertEquals(4.0, seeded!!.inputValues[0], "the forwarded startingPoint must carry the supplied coordinate")
    }
}
