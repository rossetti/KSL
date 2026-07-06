package ksl.app.orchestrator

import kotlinx.coroutines.runBlocking
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunResult
import ksl.modeling.variable.Response
import ksl.simulation.*
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Regression: `ScenarioSpec.modelConfiguration` must reach the bundle author's
 * `ModelBuilderIfc.build(modelConfiguration, ...)` — as its KDoc promises — rather
 * than being routed to the (never-installed) `Model.modelConfigurationManager` and
 * silently discarded.
 *
 * The [ConfigEcho] builder reads a numeric value out of the config map and echoes
 * it, once per replication, as the `ConfigEcho` response.  Because the echo is
 * deterministic, the across-replication average equals that value exactly, so the
 * snapshot proves whether the map reached `build()`.  Before the fix both
 * orchestrators discarded the map, so the model always saw [ECHO_DEFAULT] regardless
 * of what the spec supplied — the `= 42.0` assertions below would read `-1.0`.
 */
class ModelConfigurationForwardingTest {

    private companion object {
        const val CFG_ID = "ConfigEchoModel"
        const val ECHO_KEY = "echoValue"
        const val ECHO_DEFAULT = -1.0
        const val ECHO_SUPPLIED = 42.0
    }

    /** Echoes a build-config-derived value once per replication. */
    private class ConfigEcho(
        parent: ModelElement,
        name: String,
        private val echoValue: Double
    ) : ModelElement(parent, name) {
        private val myEcho = Response(this, "ConfigEcho")
        override fun replicationEnded() {
            myEcho.value = echoValue
        }
    }

    private val configEchoProvider: ModelProviderIfc = MapModelProvider(
        CFG_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val echoValue = modelConfiguration?.get(ECHO_KEY)?.toDouble() ?: ECHO_DEFAULT
                val model = Model(CFG_ID, autoCSVReports = false)
                model.numberOfReplications = 2
                model.lengthOfReplication = 1.0
                ConfigEcho(model, "echo", echoValue)
                return model
            }
        }
    )

    private fun echoAverage(stats: List<AcrossRepStatTableData>): Double {
        val row = stats.firstOrNull { it.stat_name == "ConfigEcho" }
        assertNotNull(row, "Expected a 'ConfigEcho' across-rep stat in the snapshot")
        return assertNotNull(row.average, "ConfigEcho across-rep average was null")
    }

    private fun singleRunConfig(config: Map<String, String>?) = RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "cfg-run",
                modelReference = ModelReference.ByProviderId(CFG_ID),
                modelConfiguration = config
            )
        )
    )

    @Test
    fun `SingleRunOrchestrator forwards modelConfiguration to build`() = runBlocking {
        val config = singleRunConfig(mapOf(ECHO_KEY to ECHO_SUPPLIED.toString()))
        val result = SingleRunOrchestrator.submit(config, configEchoProvider).result.await()

        assertIs<RunResult.Completed>(result)
        assertEquals(
            ECHO_SUPPLIED,
            echoAverage((result as RunResult.Completed).snapshot.acrossRepStats),
            1e-9,
            "modelConfiguration did not reach ModelBuilderIfc.build() — the map was discarded"
        )
    }

    @Test
    fun `SingleRunOrchestrator with no modelConfiguration falls through to builder default`() = runBlocking {
        val result = SingleRunOrchestrator.submit(singleRunConfig(null), configEchoProvider).result.await()

        assertIs<RunResult.Completed>(result)
        assertEquals(
            ECHO_DEFAULT,
            echoAverage((result as RunResult.Completed).snapshot.acrossRepStats),
            1e-9,
            "No map supplied, so build() should have used its own default"
        )
    }

    @Test
    fun `ScenarioOrchestrator forwards modelConfiguration to build`() = runBlocking {
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "cfg-scenario",
                    modelReference = ModelReference.ByProviderId(CFG_ID),
                    modelConfiguration = mapOf(ECHO_KEY to ECHO_SUPPLIED.toString())
                )
            )
        )
        val handle = ScenarioOrchestrator().submit(config, configEchoProvider, scope = this)
        val result = handle.result.await()

        assertIs<RunResult.BatchCompleted>(result)
        val snapshots = (result as RunResult.BatchCompleted).snapshots
        assertEquals(1, snapshots.size, "Expected one snapshot for the single scenario")
        assertEquals(
            ECHO_SUPPLIED,
            echoAverage(snapshots.first().acrossRepStats),
            1e-9,
            "modelConfiguration did not reach ModelBuilderIfc.build() in the scenario path"
        )
    }
}
