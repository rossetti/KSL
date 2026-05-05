package ksl.app.orchestrator

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.appendixD.GIGcQueue
import ksl.examples.general.models.LKInventoryModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simulation.*
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
 */
class OrchestratorCancelTest {

    private companion object {
        const val MM1_ID = "MM1Cancel"
        const val CANCEL_REASON = "test-cancel"
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
                // Many reps so the run is still active when cancel fires
                model.numberOfReplications = 500
                model.lengthOfReplication = 100.0
                GIGcQueue(model, numServers = 1, name = "MM1")
                return model
            }
        }
    )

    private fun mm1Config(scenarios: List<ScenarioSpec> = emptyList()): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId(MM1_ID),
            experimentRunParameters = model.extractRunParameters(),
            scenarios = scenarios
        )
    }

    private fun twoScenarioConfig(): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        val rp = model.extractRunParameters()
        return mm1Config(listOf(ScenarioSpec("S1", rp), ScenarioSpec("S2", rp)))
    }

    private val lkBuilder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model("LKInventoryModel", autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.lengthOfReplication = 120.0
            model.numberOfReplications = 5
            model.lengthOfReplicationWarmUp = 20.0
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
        // Many iterations so it is still running when cancel fires
        return Solver.createStochasticHillClimbingSolver(
            problemDefinition = makeLKInventoryModelProblemDefinition(),
            modelBuilder = lkBuilder,
            maxIterations = 100,
            replicationsPerEvaluation = 3
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Collects all RunCancelled events replayed from [handle.events]. */
    private suspend fun collectCancelledEvents(handle: RunHandle): List<RunEvent.RunCancelled> {
        return handle.events.filterIsInstance<RunEvent.RunCancelled>().toList()
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `SingleRunOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val handle = SingleRunOrchestrator.submit(mm1Config(), mm1Provider, scope = this)
        handle.cancel(CANCEL_REASON)
        val result = handle.result.await()

        assertIs<RunResult.Cancelled>(result)
        assertEquals(CANCEL_REASON, result.reason)
    }

    @Test
    fun `SingleRunOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val handle = SingleRunOrchestrator.submit(mm1Config(), mm1Provider, scope = this)
        handle.cancel(CANCEL_REASON)
        handle.result.await()

        val events = collectCancelledEvents(handle)
        assertEquals(1, events.size, "Expected exactly one RunCancelled event")
        assertEquals(CANCEL_REASON, events.first().reason)
    }

    @Test
    fun `ScenarioOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val handle = ScenarioOrchestrator().submit(twoScenarioConfig(), mm1Provider, scope = this)
        handle.cancel(CANCEL_REASON)
        val result = handle.result.await()

        assertIs<RunResult.Cancelled>(result)
        assertEquals(CANCEL_REASON, result.reason)
    }

    @Test
    fun `ScenarioOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val handle = ScenarioOrchestrator().submit(twoScenarioConfig(), mm1Provider, scope = this)
        handle.cancel(CANCEL_REASON)
        handle.result.await()

        val events = collectCancelledEvents(handle)
        assertEquals(1, events.size, "Expected exactly one RunCancelled event")
        assertEquals(CANCEL_REASON, events.first().reason)
    }

    @Test
    fun `ExperimentOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val handle = ExperimentOrchestrator().submit(buildExperiment(), scope = this)
        handle.cancel(CANCEL_REASON)
        val result = handle.result.await()

        assertIs<RunResult.Cancelled>(result)
        assertEquals(CANCEL_REASON, result.reason)
    }

    @Test
    fun `ExperimentOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val handle = ExperimentOrchestrator().submit(buildExperiment(), scope = this)
        handle.cancel(CANCEL_REASON)
        handle.result.await()

        val events = collectCancelledEvents(handle)
        assertEquals(1, events.size, "Expected exactly one RunCancelled event")
        assertEquals(CANCEL_REASON, events.first().reason)
    }

    @Test
    fun `OptimizationOrchestrator cancel resolves as Cancelled`() = runBlocking {
        val handle = OptimizationOrchestrator().submit(buildSolver(), scope = this)
        handle.cancel(CANCEL_REASON)
        val result = handle.result.await()

        assertIs<RunResult.Cancelled>(result)
        assertEquals(CANCEL_REASON, result.reason)
    }

    @Test
    fun `OptimizationOrchestrator cancel emits RunCancelled event`() = runBlocking {
        val handle = OptimizationOrchestrator().submit(buildSolver(), scope = this)
        handle.cancel(CANCEL_REASON)
        handle.result.await()

        val events = collectCancelledEvents(handle)
        assertEquals(1, events.size, "Expected exactly one RunCancelled event")
        assertEquals(CANCEL_REASON, events.first().reason)
    }

    @Test
    fun `cancel is idempotent - second call does not throw`() = runBlocking {
        val handle = SingleRunOrchestrator.submit(mm1Config(), mm1Provider, scope = this)
        handle.cancel(CANCEL_REASON)
        handle.cancel("second-cancel")   // must not throw
        val result = handle.result.await()

        assertIs<RunResult.Cancelled>(result)
    }

    @Test
    fun `cancel after result resolves is a no-op`() = runBlocking {
        // Run to completion first
        val config = RunConfiguration(
            modelReference = ModelReference.ByProviderId(MM1_ID),
            experimentRunParameters = mm1Provider.provideModel(MM1_ID).extractRunParameters()
                .also { it.numberOfReplications = 2 }
        )
        val handle = SingleRunOrchestrator.submit(config, mm1Provider, scope = this)
        handle.result.await()
        handle.cancel("after-completion")  // must not throw or alter the already-resolved result

        assertIs<RunResult.Completed>(handle.result.await())
    }
}
