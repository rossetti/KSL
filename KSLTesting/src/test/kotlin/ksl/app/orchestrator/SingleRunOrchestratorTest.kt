package ksl.app.orchestrator

import kotlinx.coroutines.runBlocking
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.session.RunResult
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.*
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for Phase 5: SingleRunOrchestrator.
 *
 * Model: GIGcQueue M/M/1 (arrivals ExponentialRV(1.0), service ExponentialRV(0.5)).
 */
class SingleRunOrchestratorTest {

    private companion object {
        const val MM1_ID = "MM1Single"
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

    private fun mm1Config(): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId(MM1_ID),
            experimentRunParameters = model.extractRunParameters()
        )
    }

    @Test
    fun `submit produces Completed result with non-empty snapshot`() = runBlocking {
        val config = mm1Config()
        val handle = SingleRunOrchestrator.submit(config, mm1Provider)
        val result = handle.result.await()

        assertIs<RunResult.Completed>(result)
        val snapshot = (result as RunResult.Completed).snapshot
        assertTrue(
            snapshot.acrossRepStats.isNotEmpty(),
            "Expected across-rep stats in snapshot from 3-rep MM1 run"
        )
    }

    @Test
    fun `missing provider for ByProviderId throws IllegalArgumentException synchronously`() {
        val config = mm1Config()
        var threw = false
        try {
            SingleRunOrchestrator.submit(config, provider = null)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "Expected IllegalArgumentException when provider is null for ByProviderId reference")
    }
}
