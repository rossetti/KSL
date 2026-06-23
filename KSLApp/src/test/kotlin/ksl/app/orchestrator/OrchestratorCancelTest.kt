package ksl.app.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.toOverrides
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.appendixD.GIGcQueue
import ksl.examples.general.models.LKInventoryModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simulation.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Acceptance tests for RunHandle.cancel() across all four orchestrators.
 *
 * Each test cancels immediately after submit (before the run has had a chance
 * to complete) and asserts that:
 *   - result resolves to RunResult.Cancelled with the expected reason
 *   - RunEvent.RunCancelled is present in the replayed event stream
 *
 * Each test uses a dedicated CoroutineScope so the runBlocking block is not
 * waiting for the orchestrator job itself — only for handle.result.await().
 * withTimeout(30_000) guards against genuine infinite hangs.
 */
//@Disabled
class OrchestratorCancelTest {

    private companion object {
        const val MM1_ID = "MM1Cancel"
        const val CANCEL_REASON = "test-cancel"
        const val TIMEOUT_MS = 30_000L
    }

    // ── model / solver builders ───────────────────────────────────────────────

    private val mm1Provider: ModelProviderIfc = MapModelProvider(
        MM1_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = Model(MM1_ID, autoCSVReports = false)
                // Enough reps that the run is still active when cancel fires, but
                // small enough that the test completes quickly if cancel is missed.
                model.numberOfReplications = 30
                model.lengthOfReplication = 100.0
                GIGcQueue(model, numServers = 1, name = "MM1")
                return model
            }
        }
    )

    private fun mm1SingleScenario(name: String = "single"): ScenarioSpec {
        val model = mm1Provider.provideModel(MM1_ID)
        return ScenarioSpec(
            name = name,
            modelReference = ModelReference.ByProviderId(MM1_ID),
            runOverrides = model.extractRunParameters().toOverrides()
        )
    }

    private fun mm1Config(scenarios: List<ScenarioSpec> = listOf(mm1SingleScenario())): RunConfiguration =
        RunConfiguration(scenarios = scenarios)

    private fun twoScenarioConfig(): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        val rp = model.extractRunParameters()
        return mm1Config(
            listOf(
                ScenarioSpec(
                    name = "S1",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = rp.toOverrides()
                ),
                ScenarioSpec(
                    name = "S2",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = rp.toOverrides()
                )
            )
        )
    }

    private val lkBuilder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model("LKInventoryModel", autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.lengthOfReplication = 30.0
            model.numberOfReplications = 2
            model.lengthOfReplicationWarmUp = 5.0
            return model
        }
    }

    private fun buildExperiment(): ParallelDesignedExperiment {
        val oq = TwoLevelFactor("OrderQuantity", low = 5.0, high = 20.0)
        val rp = TwoLevelFactor("ReorderPoint", low = 1.0, high = 5.0)
        val design = TwoLevelFactorialDesign(setOf(oq, rp))
        val settings = mapOf(oq to "Inventory.orderQuantity", rp to "Inventory.reorderPoint")
        return ParallelDesignedExperiment("LKCancelTest", lkBuilder, settings, design)
    }

    private fun buildSolver(): Solver {
        return Solver.createStochasticHillClimbingSolver(
            problemDefinition = makeLKInventoryModelProblemDefinition(),
            modelBuilder = lkBuilder,
            maxIterations = 10,
            replicationsPerEvaluation = 3
        )
    }

    /** Creates a dedicated scope so [runBlocking] only waits for [RunHandle.result], not the orchestrator job. */
    private fun dedicatedScope() = CoroutineScope(SimulationDispatcher.default + SupervisorJob())

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `SingleRunOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = SingleRunOrchestrator.submit(mm1Config(), mm1Provider, scope = testScope)
            handle.cancel(CANCEL_REASON)
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }

            assertIs<RunResult.Cancelled>(result)
            assertEquals(CANCEL_REASON, result.reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `SingleRunOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = SingleRunOrchestrator.submit(mm1Config(), mm1Provider, scope = testScope)
            handle.cancel(CANCEL_REASON)
            withTimeout(TIMEOUT_MS) { handle.result.await() }

            // After result resolves, the RunCancelled event is already in the replay cache.
            val events = handle.events.replayCache.filterIsInstance<RunEvent.RunCancelled>()
            assertEquals(1, events.size, "Expected exactly one RunCancelled event")
            assertEquals(CANCEL_REASON, events.first().reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `ScenarioOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = ScenarioOrchestrator().submit(twoScenarioConfig(), mm1Provider, scope = testScope)
            handle.cancel(CANCEL_REASON)
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }

            assertIs<RunResult.Cancelled>(result)
            assertEquals(CANCEL_REASON, result.reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `ScenarioOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = ScenarioOrchestrator().submit(twoScenarioConfig(), mm1Provider, scope = testScope)
            handle.cancel(CANCEL_REASON)
            withTimeout(TIMEOUT_MS) { handle.result.await() }

            val events = handle.events.replayCache.filterIsInstance<RunEvent.RunCancelled>()
            assertEquals(1, events.size, "Expected exactly one RunCancelled event")
            assertEquals(CANCEL_REASON, events.first().reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `ExperimentOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = ExperimentOrchestrator().submit(buildExperiment(), scope = testScope)
            handle.cancel(CANCEL_REASON)
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }

            assertIs<RunResult.Cancelled>(result)
            assertEquals(CANCEL_REASON, result.reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `ExperimentOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = ExperimentOrchestrator().submit(buildExperiment(), scope = testScope)
            handle.cancel(CANCEL_REASON)
            withTimeout(TIMEOUT_MS) { handle.result.await() }

            val events = handle.events.replayCache.filterIsInstance<RunEvent.RunCancelled>()
            assertEquals(1, events.size, "Expected exactly one RunCancelled event")
            assertEquals(CANCEL_REASON, events.first().reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `OptimizationOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = OptimizationOrchestrator().submit(buildSolver(), scope = testScope)
            handle.cancel(CANCEL_REASON)
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }

            assertIs<RunResult.Cancelled>(result)
            assertEquals(CANCEL_REASON, result.reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `OptimizationOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = OptimizationOrchestrator().submit(buildSolver(), scope = testScope)
            handle.cancel(CANCEL_REASON)
            withTimeout(TIMEOUT_MS) { handle.result.await() }

            val events = handle.events.replayCache.filterIsInstance<RunEvent.RunCancelled>()
            assertEquals(1, events.size, "Expected exactly one RunCancelled event")
            assertEquals(CANCEL_REASON, events.first().reason)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `cancel is idempotent - second call does not throw`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            val handle = SingleRunOrchestrator.submit(mm1Config(), mm1Provider, scope = testScope)
            handle.cancel(CANCEL_REASON)
            handle.cancel("second-cancel")   // must not throw
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }

            assertIs<RunResult.Cancelled>(result)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `cancel after result resolves is a no-op`() = runBlocking {
        val testScope = dedicatedScope()
        try {
            // Run to completion with a minimal model
            val baseParams = mm1Provider.provideModel(MM1_ID).extractRunParameters()
                .also { it.numberOfReplications = 2 }
            val config = RunConfiguration(
                scenarios = listOf(
                    ScenarioSpec(
                        name = "single",
                        modelReference = ModelReference.ByProviderId(MM1_ID),
                        runOverrides = baseParams.toOverrides()
                    )
                )
            )
            val handle = SingleRunOrchestrator.submit(config, mm1Provider, scope = testScope)
            withTimeout(TIMEOUT_MS) { handle.result.await() }
            handle.cancel("after-completion")  // must not throw or alter the already-resolved result

            assertIs<RunResult.Completed>(handle.result.await())
        } finally {
            testScope.cancel()
        }
    }
}
