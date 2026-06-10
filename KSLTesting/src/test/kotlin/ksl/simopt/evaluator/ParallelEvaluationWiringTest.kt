package ksl.simopt.evaluator

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.CENormalSampler
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 1b wiring tests: the parallel evaluation option threads correctly through
 * Evaluator.createProblemEvaluator and the Solver.create* factories.
 */
class ParallelEvaluationWiringTest {

    private companion object {
        const val LENGTH = 200.0
        const val WARMUP = 50.0
        const val REPS = 3
        const val RESPONSE = "System Time"
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
            name = "MM1Q"
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
        pd.inputVariable(SERVER_CONTROL, lowerBound = 1.0, upperBound = 3.0, granularity = 1.0)
        return pd
    }

    private fun point(modelName: String, numServers: Double): ModelInputs =
        ModelInputs(
            modelIdentifier = modelName,
            numReplications = REPS,
            inputs = mapOf(SERVER_CONTROL to numServers)
        )

    @Test
    fun createProblemEvaluatorParallelMatchesSequential() {
        val name = "PEW_${System.nanoTime()}"
        val pd = problem(name)
        val request = EvaluationRequest(
            name, listOf(point(name, 1.0), point(name, 2.0)), crnOption = false, cachingAllowed = false
        )

        val sequential = Evaluator.createProblemEvaluator(pd, modelBuilder(name))
        val parallel = Evaluator.createProblemEvaluator(
            pd, modelBuilder(name), parallelOptions = ParallelEvaluationOptions(enabled = true)
        )

        val seq = sequential.evaluate(request)
        val par = parallel.evaluate(request)

        assertEquals(seq.keys, par.keys)
        for (mi in seq.keys) {
            assertEquals(
                seq.getValue(mi).estimatedObjFncValue,
                par.getValue(mi).estimatedObjFncValue,
                1e-10,
                "objective for $mi"
            )
        }
    }

    @Test
    fun crossEntropyFactoryRunsWithParallelEvaluationEnabled() {
        val name = "PEW_CE_${System.nanoTime()}"
        val pd = problem(name)
        val solver = Solver.createCrossEntropySolver(
            problemDefinition = pd,
            modelBuilder = modelBuilder(name),
            maxIterations = 2,
            replicationsPerEvaluation = 2,
            parallelOptions = ParallelEvaluationOptions(enabled = true)
        )

        solver.runAllIterations()

        assertTrue(
            solver.bestSolution.estimatedObjFncValue.isFinite(),
            "Cross-Entropy via parallel evaluation should yield a finite best objective"
        )
    }

    @Test
    fun identicalConfigCrossEntropyRunsAreReproducible() {
        val name = "REPRO_${System.nanoTime()}"
        fun runBestObjective(): Double {
            val solver = Solver.createCrossEntropySolver(
                problemDefinition = problem(name),
                modelBuilder = modelBuilder(name),
                maxIterations = 3,
                replicationsPerEvaluation = 2
            )
            solver.runAllIterations()
            return solver.bestSolution.estimatedObjFncValue
        }
        // Each solver/sampler now defaults to its own fresh RNStreamProvider (seeded identically),
        // so two runs of the same configuration draw the same streams and must match. Before the
        // per-solver-provider change this would diverge, because both runs drew successive streams
        // from the shared global provider.
        assertEquals(
            runBestObjective(), runBestObjective(), 1e-10,
            "identical-configuration Cross-Entropy runs should be reproducible"
        )
    }

    /**
     * Single stream number: the solver's base stream uses streamNum (default 0 -> stream 1) and the
     * solver attaches the sampler onto the SAME provider on the next available stream (stream 2), so
     * the two are distinct by construction.
     */
    @Test
    fun crossEntropySolverOwnsBaseStreamAndAttachesSamplerToNext() {
        val name = "STREAMS_${System.nanoTime()}"
        val provider = RNStreamProvider()
        val solver = Solver.createCrossEntropySolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            maxIterations = 2,
            replicationsPerEvaluation = 2,
            streamProvider = provider
        )
        assertSame(provider, solver.streamProvider, "solver should own the supplied provider")
        assertSame(provider, solver.ceSampler.streamProvider, "attached sampler should use the solver's provider")
        assertEquals(1, solver.streamNumber, "solver base should take the first stream of the provider")
        assertEquals(2, solver.ceSampler.streamNumber, "attached sampler should take the next stream")
        assertNotEquals(
            solver.streamNumber, solver.ceSampler.streamNumber,
            "solver and sampler must use distinct streams"
        )
    }

    /**
     * An explicit solver streamNum selects the solver's base stream; the attached sampler follows on
     * the next available stream — for any streamNum, so they never alias.
     */
    @Test
    fun crossEntropyStreamNumSelectsBaseStreamAndSamplerFollows() {
        val name = "STREAMSEL_${System.nanoTime()}"
        val provider = RNStreamProvider()
        val solver = Solver.createCrossEntropySolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            maxIterations = 2,
            replicationsPerEvaluation = 2,
            streamNum = 5,
            streamProvider = provider
        )
        assertEquals(5, solver.streamNumber, "explicit streamNum should select the solver's base stream")
        assertEquals(6, solver.ceSampler.streamNumber, "attached sampler should take the next stream after the base")
    }

    /**
     * Assigning a new sampler re-attaches it onto the solver's provider (a distinct stream), so a
     * standalone-built sampler can be handed to the solver and is correctly re-homed.
     */
    @Test
    fun assigningANewSamplerReattachesItToTheSolverProvider() {
        val name = "REATTACH_${System.nanoTime()}"
        val provider = RNStreamProvider()
        val solver = Solver.createCrossEntropySolver(
            problemDefinition = problem(name),
            modelBuilder = modelBuilder(name),
            maxIterations = 2,
            replicationsPerEvaluation = 2,
            streamProvider = provider
        )
        // A sampler built standalone with its own provider.
        val replacement = CENormalSampler(problem(name))
        solver.ceSampler = replacement
        assertSame(replacement, solver.ceSampler, "the solver should hold the assigned sampler")
        assertSame(provider, solver.ceSampler.streamProvider, "the assigned sampler should be re-homed onto the solver's provider")
        assertNotEquals(
            solver.streamNumber, solver.ceSampler.streamNumber,
            "the re-attached sampler must use a stream distinct from the solver base"
        )
    }
}
