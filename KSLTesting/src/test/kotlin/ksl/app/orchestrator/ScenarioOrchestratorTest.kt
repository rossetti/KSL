package ksl.app.orchestrator

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.session.OrchestratorSummary
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Acceptance tests for Phase 5: ScenarioOrchestrator.
 *
 * Model: GIGcQueue M/M/1 with two scenarios run concurrently.
 */
class ScenarioOrchestratorTest {

    private companion object {
        const val MM1_ID = "MM1Scenario"
    }

    private val mm1Provider: ModelProviderIfc = MapModelProvider(
        MM1_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = Model(MM1_ID, autoCSVReports = false)
                model.numberOfReplications = 3
                model.lengthOfReplication = 100.0
                GIGcQueue(model, numServers = 1, name = "MM1")
                return model
            }
        }
    )

    private fun twoScenarioConfig(): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        val runParams = model.extractRunParameters()
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId(MM1_ID),
            experimentRunParameters = runParams,
            scenarios = listOf(
                ScenarioSpec("LowLoad", runParams),
                ScenarioSpec("HighLoad", runParams)
            )
        )
    }

    @Test
    fun `two scenarios resolve as BatchCompleted with two snapshots`() = runBlocking {
        val config = twoScenarioConfig()
        val orchestrator = ScenarioOrchestrator()
        val handle = orchestrator.submit(config, mm1Provider, scope = this)
        val result = handle.result.await()

        assertIs<RunResult.BatchCompleted>(result)
        val orchestratorResult = result as RunResult.BatchCompleted
        assertEquals(2, orchestratorResult.snapshots.size,
            "Expected one snapshot per scenario")
        assertEquals(0, orchestratorResult.summary.failedItems,
            "Expected no failed scenarios")
        assertEquals(2, orchestratorResult.summary.completedItems)
    }

    @Test
    fun `ScenarioCompleted events emitted for each scenario in order`() = runBlocking {
        val config = twoScenarioConfig()
        val orchestrator = ScenarioOrchestrator()
        val handle = orchestrator.submit(config, mm1Provider, scope = this)

        val scenarioEvents = mutableListOf<RunEvent.ScenarioCompleted>()
        val collectJob = launch {
            handle.events
                .takeWhile { it !is RunEvent.RunCompleted && it !is RunEvent.RunFailed }
                .filterIsInstance<RunEvent.ScenarioCompleted>()
                .collect { scenarioEvents.add(it) }
        }
        handle.result.await()
        collectJob.join()

        assertEquals(2, scenarioEvents.size, "Expected exactly 2 ScenarioCompleted events")
        assertEquals(1, scenarioEvents[0].index)
        assertEquals(2, scenarioEvents[1].index)
        assertEquals(2, scenarioEvents[0].totalScenarios)
    }

    @Test
    fun `empty scenarios list throws IllegalArgumentException before submitting`() {
        val model = mm1Provider.provideModel(MM1_ID)
        val config = RunConfiguration(
            modelReference = ModelReference.ByProviderId(MM1_ID),
            experimentRunParameters = model.extractRunParameters()
            // no scenarios
        )
        var threw = false
        try {
            ScenarioOrchestrator().submit(config, mm1Provider)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "Expected IllegalArgumentException for empty scenarios list")
    }
}
