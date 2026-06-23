package ksl.app.config

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Serialization tests for [ModelRunTemplate], the app-layer model template used
 * by workflows that need reusable model-building data without a full
 * [RunConfiguration].
 */
class ModelRunTemplateTest {

    @Test
    fun `ModelRunTemplate round-trips through JSON with model configuration`() {
        val template = templateWithProviderReference()

        val decoded = ModelRunTemplateJson.decode(ModelRunTemplateJson.encode(template))

        assertEquals(template, decoded)
        assertEquals(template.modelConfiguration, decoded.modelConfiguration)
    }

    @Test
    fun `ModelRunTemplate round-trips through TOML with model configuration`() {
        val template = templateWithProviderReference()

        val decoded = ModelRunTemplateToml.decode(ModelRunTemplateToml.encode(template))

        assertEquals(template, decoded)
        assertEquals(template.modelConfiguration, decoded.modelConfiguration)
    }

    @Test
    fun `ModelRunTemplate defaults survive JSON and TOML round-trips`() {
        val template = ModelRunTemplate(
            modelReference = ModelReference.ByProviderId("MM1"),
            runParameters = runParameters()
        )

        val jsonDecoded = ModelRunTemplateJson.decode(ModelRunTemplateJson.encode(template))
        val tomlDecoded = ModelRunTemplateToml.decode(ModelRunTemplateToml.encode(template))

        assertEquals(template, jsonDecoded)
        assertEquals(template, tomlDecoded)
        assertNull(jsonDecoded.modelConfiguration)
        assertNull(tomlDecoded.modelConfiguration)
        assertEquals(0, jsonDecoded.controls.totalControls)
        assertEquals(0, tomlDecoded.controls.totalControls)
        assertEquals(emptyList<RVParameterOverride>(), jsonDecoded.rvOverrides)
        assertEquals(emptyList<RVParameterOverride>(), tomlDecoded.rvOverrides)
    }

    @Test
    fun `ModelRunTemplate supports jar model references in JSON and TOML`() {
        val template = ModelRunTemplate(
            modelReference = ModelReference.ByJar(
                jarPath = "/tmp/models/example-model.jar",
                builderClassName = "ksl.examples.ExampleModelBuilder"
            ),
            modelConfiguration = mapOf("profile" to "stress-test"),
            runParameters = runParameters()
        )

        val jsonDecoded = ModelRunTemplateJson.decode(ModelRunTemplateJson.encode(template))
        val tomlDecoded = ModelRunTemplateToml.decode(ModelRunTemplateToml.encode(template))

        assertEquals(template, jsonDecoded)
        assertEquals(template, tomlDecoded)
    }

    private fun templateWithProviderReference(): ModelRunTemplate =
        ModelRunTemplate(
            modelReference = ModelReference.ByProviderId("MM1"),
            modelConfiguration = mapOf(
                "input.schema" to "classpath:/models/mm1-schema.json",
                "input.payload" to """{"arrivalRate": 1.2, "serviceRate": 2.0}"""
            ),
            runParameters = runParameters(),
            rvOverrides = listOf(
                RVParameterOverride(
                    rvName = "MM1:ServiceTime",
                    paramName = "mean",
                    value = 2.0
                )
            )
        )

    private fun runParameters() =
        Model("MM1", autoCSVReports = false).also { model ->
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            model.lengthOfReplicationWarmUp = 10.0
        }.extractRunParameters()
}
