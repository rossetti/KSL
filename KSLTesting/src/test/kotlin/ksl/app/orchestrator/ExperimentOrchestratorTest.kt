package ksl.app.orchestrator

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Acceptance tests for Phase 5: ExperimentOrchestrator.
 *
 * Model: LK Inventory with 2 factors (orderQuantity, reorderPoint) → 4 design points.
 * Reps kept very small (5) to ensure the test completes quickly.
 */
class ExperimentOrchestratorTest {

    private val lkBuilder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model("LKInventoryTest", autoCSVReports = false)
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
        val settings = mapOf(
            oq to "Inventory.orderQuantity",
            rp to "Inventory.reorderPoint"
        )
        return ParallelDesignedExperiment("LKTest", lkBuilder, settings, design)
    }

    @Test
    fun `experiment resolves as BatchCompleted with correct snapshot count`() = runBlocking {
        val experiment = buildExperiment()
        val totalDesignPoints = experiment.design.designPoints().size // should be 4

        val handle = ExperimentOrchestrator().submit(experiment, numRepsPerDesignPoint = 5, scope = this)
        val result = handle.result.await()

        assertIs<RunResult.BatchCompleted>(result)
        val orchResult = result as RunResult.BatchCompleted
        assertEquals(totalDesignPoints, orchResult.snapshots.size,
            "Expected one snapshot per design point")
        assertEquals(0, orchResult.summary.failedItems)
    }

    @Test
    fun `DesignPointCompleted events emitted for each design point`() = runBlocking {
        val experiment = buildExperiment()
        val totalPoints = experiment.design.designPoints().size

        val handle = ExperimentOrchestrator().submit(experiment, numRepsPerDesignPoint = 5, scope = this)

        val dpEvents = mutableListOf<RunEvent.DesignPointCompleted>()
        val collectJob = launch {
            handle.events
                .takeWhile { it !is RunEvent.RunCompleted && it !is RunEvent.RunFailed }
                .filterIsInstance<RunEvent.DesignPointCompleted>()
                .collect { dpEvents.add(it) }
        }
        handle.result.await()
        collectJob.join()

        assertEquals(totalPoints, dpEvents.size,
            "Expected one DesignPointCompleted per design point")
        dpEvents.forEachIndexed { i, event ->
            assertEquals(i + 1, event.index)
            assertEquals(totalPoints, event.totalDesignPoints)
        }
    }
}
