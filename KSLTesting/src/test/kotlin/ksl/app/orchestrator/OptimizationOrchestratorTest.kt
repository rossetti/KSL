package ksl.app.orchestrator

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.examples.general.models.LKInventoryModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simulation.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Acceptance tests for Phase 5: OptimizationOrchestrator.
 *
 * Model: LK Inventory with stochastic hill climbing, 3 iterations max.
 * Reps kept very small to ensure the test completes quickly.
 */
class OptimizationOrchestratorTest {

    private val fastBuilder = object : ModelBuilderIfc {
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

    private fun buildSolver(): Solver {
        val problemDef = makeLKInventoryModelProblemDefinition()
        return Solver.createStochasticHillClimbingSolver(
            problemDefinition = problemDef,
            modelBuilder = fastBuilder,
            maxIterations = 3,
            replicationsPerEvaluation = 3
        )
    }

    @Test
    fun `optimization resolves as OrchestratorCompleted with empty snapshots`() = runBlocking {
        val solver = buildSolver()
        val handle = OptimizationOrchestrator().submit(solver, scope = this)
        val result = handle.result.await()

        assertIs<RunResult.OrchestratorCompleted>(result)
        val orchResult = result as RunResult.OrchestratorCompleted
        assertTrue(orchResult.snapshots.isEmpty(), "OptimizationOrchestrator surfaces no per-run snapshots")
        assertEquals(0, orchResult.summary.failedItems)
        assertEquals(solver.iterationCounter, orchResult.summary.completedItems)
    }

    @Test
    fun `IterationCompleted events emitted for each solver iteration`() = runBlocking {
        val solver = buildSolver()
        val handle = OptimizationOrchestrator().submit(solver, scope = this)

        val iterEvents = mutableListOf<RunEvent.IterationCompleted>()
        val collectJob = launch {
            handle.events
                .takeWhile { it !is RunEvent.RunCompleted && it !is RunEvent.RunFailed }
                .filterIsInstance<RunEvent.IterationCompleted>()
                .collect { iterEvents.add(it) }
        }
        handle.result.await()
        collectJob.join()

        assertTrue(iterEvents.isNotEmpty(), "Expected at least one IterationCompleted event")
        assertTrue(
            iterEvents.all { it.bestInputs.isNotEmpty() },
            "Each IterationCompleted should carry non-empty bestInputs"
        )
    }
}
