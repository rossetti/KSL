package ksl.app.config

import kotlinx.serialization.json.Json
import ksl.app.validation.RunConfigurationValidator
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the [ModelReference.Embedded] variant added in the Single-app
 * substrate-prep commit.  Covers construction invariants, codec round-trip,
 * and the validator's cross-app `MODEL_REFERENCE_EMBEDDED_NOT_RESOLVABLE`
 * signal.
 */
class EmbeddedModelReferenceTest {

    @Test
    fun `non-blank modelName is OK`() {
        val ref = ModelReference.Embedded(modelName = "MM1")
        assertEquals("MM1", ref.modelName)
    }

    @Test
    fun `blank modelName is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ModelReference.Embedded(modelName = "")
        }
    }

    @Test
    fun `whitespace-only modelName is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ModelReference.Embedded(modelName = "   ")
        }
    }

    @Test
    fun `JSON round-trip via sealed serializer carries the embedded discriminator`() {
        val original: ModelReference = ModelReference.Embedded(modelName = "MM1")
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(ModelReference.serializer(), original)
        assertTrue(
            "\"type\":\"embedded\"" in encoded || "\"type\": \"embedded\"" in encoded,
            "encoded should carry the embedded discriminator: $encoded"
        )
        val decoded = json.decodeFromString(ModelReference.serializer(), encoded)
        assertIs<ModelReference.Embedded>(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `existing variants still round-trip after Embedded was added`() {
        val json = Json { encodeDefaults = true }

        val providerRef: ModelReference = ModelReference.ByProviderId("MM1")
        val providerDecoded = json.decodeFromString(
            ModelReference.serializer(),
            json.encodeToString(ModelReference.serializer(), providerRef)
        )
        assertEquals(providerRef, providerDecoded)

        val jarRef: ModelReference = ModelReference.ByJar("/path/to/foo.jar", "com.example.Builder")
        val jarDecoded = json.decodeFromString(
            ModelReference.serializer(),
            json.encodeToString(ModelReference.serializer(), jarRef)
        )
        assertEquals(jarRef, jarDecoded)

        val bundleRef: ModelReference =
            ModelReference.ByBundleAndModelId("edu.example.queueing", "mm1")
        val bundleDecoded = json.decodeFromString(
            ModelReference.serializer(),
            json.encodeToString(ModelReference.serializer(), bundleRef)
        )
        assertEquals(bundleRef, bundleDecoded)
    }

    @Test
    fun `validateForRun reports NOT_RESOLVABLE when provider does not know the model name`() {
        val foreignProvider = MapModelProvider(
            "SomethingElse",
            object : ModelBuilderIfc {
                override fun build(
                    modelConfiguration: Map<String, String>?,
                    experimentRunParameters: ExperimentRunParametersIfc?
                ): Model = Model("SomethingElse")
            }
        )
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "FromSingle",
                    modelReference = ModelReference.Embedded(modelName = "MM1")
                )
            )
        )

        val result = RunConfigurationValidator.validateForRun(config, foreignProvider)

        assertFalse(result.isValid)
        assertTrue(
            result.errors.any {
                it.path == "scenarios[0].modelReference.modelName" &&
                        it.code == "MODEL_REFERENCE_EMBEDDED_NOT_RESOLVABLE"
            },
            "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    @Test
    fun `validateForRun reports NOT_RESOLVABLE when provider is null`() {
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "FromSingle",
                    modelReference = ModelReference.Embedded(modelName = "MM1")
                )
            )
        )

        val result = RunConfigurationValidator.validateForRun(config, provider = null)

        assertFalse(result.isValid)
        assertTrue(
            result.errors.any {
                it.path == "scenarios[0].modelReference.modelName" &&
                        it.code == "MODEL_REFERENCE_EMBEDDED_NOT_RESOLVABLE"
            },
            "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    @Test
    fun `validateForRun resolves Embedded against a provider that knows the model name`() {
        val provider = MapModelProvider(
            "MM1",
            object : ModelBuilderIfc {
                override fun build(
                    modelConfiguration: Map<String, String>?,
                    experimentRunParameters: ExperimentRunParametersIfc?
                ): Model = Model("MM1")
            }
        )
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "FromSingle",
                    modelReference = ModelReference.Embedded(modelName = "MM1")
                )
            )
        )

        val result = RunConfigurationValidator.validateForRun(config, provider)

        assertTrue(
            result.isValid,
            "expected validation to pass with a matching provider; errors: " +
                    result.errors.map { it.path to it.code }
        )
    }
}
