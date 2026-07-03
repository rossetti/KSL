package ksl.simopt.solvers.algorithms

import ksl.examples.general.simopt.BuildLKModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ParallelEvaluationOptions
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.concurrent.ConcurrentRunOptions
import ksl.simopt.solvers.concurrent.ConfirmationOptions
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc
import ksl.simopt.solvers.concurrent.PooledMemberEvaluatorFactory
import ksl.simopt.solvers.concurrent.SolverFactoryIfc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for RandomRestartSolver's two execution modes: the preserved sequential path and
 * the new concurrent-restarts mode (determinism, counters, starting-point contract,
 * cache guard, stop propagation, confirmation stage, and factory wiring).
 */
class RandomRestartConcurrentTest {

    private companion object {
        const val OBJ_FN = "TotalCost"
        const val INNER_ITERATIONS = 3
        const val REPS = 2
        const val RESTARTS = 4
    }

    private fun makeEvaluator(pd: ProblemDefinition): Evaluator =
        Evaluator.createProblemEvaluator(problemDefinition = pd, modelBuilder = BuildLKModel)

    private fun shcFactory(pd: ProblemDefinition, iterations: Int = INNER_ITERATIONS) =
        SolverFactoryIfc { evaluator, _, name ->
            StochasticHillClimber(
                problemDefinition = pd,
                evaluator = evaluator,
                maxIterations = iterations,
                replicationsPerEvaluation = REPS,
                name = name
            )
        }

    // ── Sequential regression: factory mode (concurrentRestarts = 1) == legacy instance mode ──

    @Test
    @DisplayName("Factory mode with concurrentRestarts = 1 matches the legacy instance mode")
    fun sequentialFactoryModeMatchesLegacyInstanceMode() {
        // Legacy: single reused inner instance handed in directly.
        val pdLegacy = makeLKInventoryModelProblemDefinition()
        val legacyEvaluator = makeEvaluator(pdLegacy)
        val legacyInner = StochasticHillClimber(
            problemDefinition = pdLegacy,
            evaluator = legacyEvaluator,
            maxIterations = INNER_ITERATIONS,
            replicationsPerEvaluation = REPS,
            name = "legacy_prototype"
        )
        val legacy = RandomRestartSolver(
            restartingSolver = legacyInner,
            maxNumRestarts = RESTARTS,
            streamNum = 1
        )
        legacy.runAllIterations()

        // Factory mode, sequential: the prototype instance is the run instance.
        val pdFactory = makeLKInventoryModelProblemDefinition()
        val factoryEvaluator = makeEvaluator(pdFactory)
        val factoryMode = RandomRestartSolver(
            problemDefinition = pdFactory,
            evaluator = factoryEvaluator,
            solverFactory = SolverFactoryIfc { e, _, _ ->
                StochasticHillClimber(
                    problemDefinition = pdFactory,
                    evaluator = e,
                    maxIterations = INNER_ITERATIONS,
                    replicationsPerEvaluation = REPS,
                    name = "legacy_prototype"
                )
            },
            maxNumRestarts = RESTARTS,
            concurrentRestarts = 1,
            // must match what the legacy constructor inherits from its inner solver, or the
            // outer initial-point evaluation advances the stream tape differently
            replicationsPerEvaluation = ksl.simopt.solvers.FixedReplicationsPerEvaluation(REPS),
            streamNum = 1
        )
        factoryMode.runAllIterations()

        assertEquals(legacy.bestSolution.inputMap, factoryMode.bestSolution.inputMap,
            "sequential factory mode must visit the same best point as legacy mode")
        assertEquals(legacy.bestSolution.penalizedObjFncValue,
            factoryMode.bestSolution.penalizedObjFncValue, 0.0)
        assertEquals(legacy.numOracleCalls, factoryMode.numOracleCalls)
        assertEquals(legacy.numReplicationsRequested, factoryMode.numReplicationsRequested)
    }

    // ── Concurrent determinism across worker counts ───────────────────────────

    private fun runConcurrent(concurrentRestarts: Int, options: ConcurrentRunOptions = ConcurrentRunOptions()): RandomRestartSolver {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = RandomRestartSolver(
            problemDefinition = pd,
            evaluator = makeEvaluator(pd),
            solverFactory = shcFactory(pd),
            memberEvaluatorFactory = PooledMemberEvaluatorFactory(pd, BuildLKModel),
            maxNumRestarts = RESTARTS,
            concurrentRestarts = concurrentRestarts,
            concurrentOptions = options,
            streamNum = 1
        )
        solver.runAllIterations()
        return solver
    }

    @Test
    @DisplayName("Concurrent results are identical across worker counts")
    fun concurrentDeterministicAcrossWorkerCounts() {
        val two = runConcurrent(concurrentRestarts = 2)
        val four = runConcurrent(concurrentRestarts = 4)
        assertTrue(two.bestSolution.penalizedObjFncValue.isFinite())
        assertEquals(two.bestSolution.inputMap, four.bestSolution.inputMap,
            "best input point must not depend on the worker count")
        assertEquals(two.bestSolution.penalizedObjFncValue,
            four.bestSolution.penalizedObjFncValue, 0.0)
        assertEquals(two.numOracleCalls, four.numOracleCalls)
        assertEquals(two.numReplicationsRequested, four.numReplicationsRequested)
        assertEquals(RESTARTS, two.iterationCounter)
    }

    // ── Starting-point contract ───────────────────────────────────────────────

    @Test
    @DisplayName("A user-supplied starting point seeds restart 0 only")
    fun userStartingPointSeedsRestartZero() {
        val pd = makeLKInventoryModelProblemDefinition()
        val userPoint = pd.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 42.0, "Inventory.reorderPoint" to 17.0))
        val observed = ConcurrentHashMap<Int, InputMap?>()
        val solver = RandomRestartSolver(
            problemDefinition = pd,
            evaluator = makeEvaluator(pd),
            solverFactory = shcFactory(pd),
            memberEvaluatorFactory = PooledMemberEvaluatorFactory(pd, BuildLKModel),
            maxNumRestarts = 3,
            concurrentRestarts = 3,
            streamNum = 1
        )
        solver.startingPoint = userPoint
        // the decorator runs after the runner applies the task's starting point
        solver.innerSolverDecorator = { inner, index -> observed[index] = inner.startingPoint }
        solver.runAllIterations()
        assertEquals(userPoint, observed[0], "restart 0 must begin at the user's point")
        assertNotEquals(userPoint, observed[1], "later restarts must begin at random points")
        assertNotEquals(userPoint, observed[2], "later restarts must begin at random points")
    }

    @Test
    @DisplayName("Sequential mode: the first restart honors a user-supplied starting point")
    fun sequentialFirstRestartHonorsUserStartingPoint() {
        val pd = makeLKInventoryModelProblemDefinition()
        val userPoint = pd.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 42.0, "Inventory.reorderPoint" to 17.0))
        val inner = StochasticHillClimber(
            problemDefinition = pd,
            evaluator = makeEvaluator(pd),
            maxIterations = INNER_ITERATIONS,
            replicationsPerEvaluation = REPS
        )
        // With a single restart, the inner solver's last-assigned starting point is
        // observable after the run: it must be the user's point, not a random draw.
        val solver = RandomRestartSolver(restartingSolver = inner, maxNumRestarts = 1, streamNum = 1)
        solver.startingPoint = userPoint
        solver.runAllIterations()
        assertEquals(userPoint, inner.startingPoint,
            "the first sequential restart must begin at the user-supplied point")

        // And with two restarts, the second draws a random point (the last-assigned
        // starting point is no longer the user's).
        val pd2 = makeLKInventoryModelProblemDefinition()
        val userPoint2 = pd2.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 42.0, "Inventory.reorderPoint" to 17.0))
        val inner2 = StochasticHillClimber(
            problemDefinition = pd2,
            evaluator = makeEvaluator(pd2),
            maxIterations = INNER_ITERATIONS,
            replicationsPerEvaluation = REPS
        )
        val solver2 = RandomRestartSolver(restartingSolver = inner2, maxNumRestarts = 2, streamNum = 1)
        solver2.startingPoint = userPoint2
        solver2.runAllIterations()
        assertNotEquals(userPoint2, inner2.startingPoint,
            "later sequential restarts must begin at random points")
    }

    // ── Cache guard ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("clearCacheBetweenRuns = false is rejected under concurrent restarts")
    fun sharedCacheRejectedWhenConcurrent() {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = RandomRestartSolver(
            problemDefinition = pd,
            evaluator = makeEvaluator(pd),
            solverFactory = shcFactory(pd),
            memberEvaluatorFactory = PooledMemberEvaluatorFactory(pd, BuildLKModel),
            maxNumRestarts = 2,
            concurrentRestarts = 2
        )
        solver.clearCacheBetweenRuns = false
        assertThrows<IllegalArgumentException> { solver.runAllIterations() }
    }

    // ── Stop propagation through the outer solver ─────────────────────────────

    /** Simulation-free evaluator with strictly improving values (never self-stops). */
    private class ImprovingStubEvaluator(
        private val pd: ProblemDefinition,
        private val perEvaluationDelayMillis: Long
    ) : EvaluatorIfc {
        override var totalEvaluatorCalls: Int = 0
        override var totalDesignPointsEvaluated: Int = 0
        override var totalOracleReplications: Int = 0
        override var totalCachedReplications: Int = 0
        override val cache: SolutionCacheIfc? = null
        override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
            if (perEvaluationDelayMillis > 0) Thread.sleep(perEvaluationDelayMillis)
            totalEvaluatorCalls++
            return evaluationRequest.modelInputs.associateWith { mi ->
                Solution(
                    pd.toInputMap(mi.inputs.toMutableMap()),
                    EstimatedResponse(OBJ_FN, -totalEvaluatorCalls.toDouble(), 1e-12, mi.numReplications.toDouble()),
                    emptyList(),
                    totalEvaluatorCalls
                )
            }
        }
    }

    private class StubMemberFactory(
        private val pd: ProblemDefinition,
        private val perEvaluationDelayMillis: Long
    ) : MemberEvaluatorFactoryIfc {
        override fun createEvaluator(memberIndex: Int): EvaluatorIfc =
            ImprovingStubEvaluator(pd, perEvaluationDelayMillis)
    }

    @Test
    @Timeout(30)
    @DisplayName("stopIterations on the outer solver ends in-flight restarts promptly")
    fun outerStopEndsConcurrentRestartsPromptly() {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = RandomRestartSolver(
            problemDefinition = pd,
            evaluator = ImprovingStubEvaluator(pd, 0),
            solverFactory = shcFactory(pd, iterations = 1_000_000),
            memberEvaluatorFactory = StubMemberFactory(pd, perEvaluationDelayMillis = 2),
            maxNumRestarts = 6,
            concurrentRestarts = 2,
            streamNum = 1
        )
        val stopper = Thread {
            Thread.sleep(500)
            solver.stopIterations("test stop")
        }
        stopper.start()
        solver.runAllIterations()
        stopper.join()
        assertTrue(solver.iterationCounter < 6) {
            "expected the outer loop to stop before consuming all restarts; " +
                    "completed ${solver.iterationCounter}"
        }
    }

    // ── Confirmation stage integration ────────────────────────────────────────

    @Test
    @DisplayName("Confirmation stage runs after all restarts and reports its winner")
    fun confirmationStageReportsWinner() {
        val options = ConcurrentRunOptions(
            confirmation = ConfirmationOptions(topK = 3, replicationsPerCandidate = 3)
        )
        val solver = runConcurrent(concurrentRestarts = 2, options = options)
        val outcome = solver.confirmationOutcome
        assertNotNull(outcome, "a confirmation outcome must be recorded")
        assertEquals(outcome!!.winner.inputMap, solver.currentSolution.inputMap,
            "the confirmed winner must be the final current solution")
        assertTrue(outcome.numOracleCalls >= 0)
    }

    // ── Factory wiring ────────────────────────────────────────────────────────

    @Test
    @DisplayName("createRandomRestartStochasticHillClimbingSolver supports concurrent restarts")
    fun shcFactorySupportsConcurrentRestarts() {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = Solver.createRandomRestartStochasticHillClimbingSolver(
            problemDefinition = pd,
            modelBuilder = BuildLKModel,
            maxNumRestarts = 3,
            maxIterations = INNER_ITERATIONS,
            replicationsPerEvaluation = REPS,
            streamNum = 1,
            concurrentRestarts = 3
        )
        assertTrue(solver.isConcurrentMode)
        solver.runAllIterations()
        assertEquals(3, solver.iterationCounter)
        assertTrue(solver.bestSolution.penalizedObjFncValue.isFinite())
        // reporting surface carries the concurrency settings
        assertEquals("3", solver.configurationProperties["concurrentRestarts"])
        assertTrue(solver.configurationProperties.keys.any { it.startsWith("innerSolver.") })
    }

    @Test
    @DisplayName("Parallel evaluation and concurrent restarts are mutually exclusive")
    fun parallelEvaluationRejectedWithConcurrentRestarts() {
        val pd = makeLKInventoryModelProblemDefinition()
        assertThrows<IllegalArgumentException> {
            Solver.createRandomRestartStochasticHillClimbingSolver(
                problemDefinition = pd,
                modelBuilder = BuildLKModel,
                maxNumRestarts = 2,
                parallelOptions = ParallelEvaluationOptions(enabled = true),
                concurrentRestarts = 2
            )
        }
    }

    @Test
    @DisplayName("SA factory rejects a custom cooling schedule under concurrency, accepts library schedules")
    fun saFactoryCoolingScheduleGuard() {
        val pd = makeLKInventoryModelProblemDefinition()
        val customSchedule = object : CoolingScheduleIfc {
            override var initialTemperature: Double = 100.0
            override fun nextTemperature(iteration: Int): Double = initialTemperature * 0.9
        }
        assertThrows<IllegalArgumentException> {
            Solver.createRandomRestartSimulatedAnnealingSolver(
                problemDefinition = pd,
                modelBuilder = BuildLKModel,
                maxNumRestarts = 2,
                coolingSchedule = customSchedule,
                concurrentRestarts = 2
            )
        }
        // library schedule: constructs fine
        val solver = Solver.createRandomRestartSimulatedAnnealingSolver(
            problemDefinition = pd,
            modelBuilder = BuildLKModel,
            maxNumRestarts = 2,
            maxIterations = INNER_ITERATIONS,
            replicationsPerEvaluation = REPS,
            temperatureConfiguration = TemperatureConfiguration.Fixed(100.0),
            concurrentRestarts = 2
        )
        assertTrue(solver.isConcurrentMode)
    }
}
