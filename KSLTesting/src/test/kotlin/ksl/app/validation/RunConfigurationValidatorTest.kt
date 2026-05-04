package ksl.app.validation

import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.examples.general.controls.Van
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Acceptance tests for Phase 3: field-addressable pre-run validation.
 */
class RunConfigurationValidatorTest {

    private val vanProvider: ModelProviderIfc = MapModelProvider(
        VAN_MODEL_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = vanModel()
        }
    )

    private fun vanModel(): Model {
        val model = Model(VAN_MODEL_ID, autoCSVReports = false)
        model.numberOfReplications = 3
        model.lengthOfReplication = 100.0
        Van(model, VAN_ELEMENT_NAME)
        return model
    }

    private fun vanConfig(): RunConfiguration {
        val model = vanModel()
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId(VAN_MODEL_ID),
            experimentRunParameters = model.extractRunParameters(),
            controls = model.controls().exportAll()
        )
    }

    @Test
    fun `invalid string control value is reported without provider or model build`() {
        val config = vanConfig().withFuelType("STEAM")

        val result = RunConfigurationValidator.validate(config)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("controls.string.$FUEL_TYPE_KEY.value", "STRING_CONTROL_VALUE_NOT_ALLOWED"),
            "Expected invalid fuelType to be reported as a document-level string control error"
        )
    }

    @Test
    fun `warm-up length must be less than replication length`() {
        val base = vanConfig()
        val config = base.copy(
            experimentRunParameters = base.experimentRunParameters.copy(
                lengthOfReplication = 10.0,
                lengthOfReplicationWarmUp = 10.0
            )
        )

        val result = RunConfigurationValidator.validate(config)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "experimentRunParameters.lengthOfReplicationWarmUp",
                "WARM_UP_NOT_LESS_THAN_REPLICATION_LENGTH"
            )
        )
    }

    @Test
    fun `validateForRun reports missing provider for provider-id reference`() {
        val result = RunConfigurationValidator.validateForRun(vanConfig(), provider = null)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("modelReference.providerId", "MODEL_PROVIDER_REQUIRED")
        )
    }

    @Test
    fun `validateForRun reports unknown provider id`() {
        val config = vanConfig().copy(modelReference = ModelReference.ByProviderId("MissingModel"))

        val result = RunConfigurationValidator.validateForRun(config, vanProvider)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("modelReference.providerId", "MODEL_PROVIDER_ID_UNKNOWN")
        )
    }

    @Test
    fun `numeric control value outside bounds is a warning because KSL clamps it`() {
        val config = vanConfig().withNumSeats(100.0)

        val result = RunConfigurationValidator.validate(config)

        assertTrue(result.isValid)
        assertTrue(
            result.hasWarning("controls.numeric.$NUM_SEATS_KEY.value", "NUMERIC_CONTROL_VALUE_OUT_OF_BOUNDS")
        )
    }

    @Test
    fun `validateForRun adapts missing live control key to field error`() {
        val controls = vanConfig().controls
        val renamedStringControls = controls.stringControls.map { control ->
            if (control.keyName == FUEL_TYPE_KEY) control.copy(keyName = "Van_1.missingFuelType")
            else control
        }
        val config = vanConfig().copy(
            controls = controls.copy(stringControls = renamedStringControls)
        )

        val result = RunConfigurationValidator.validateForRun(config, vanProvider)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("controls.string.Van_1.missingFuelType.value", "CONTROL_KEY_UNKNOWN")
        )
    }

    @Test
    fun `validateForRun reports unknown RV override name`() {
        val config = vanConfig().copy(
            rvOverrides = listOf(RVParameterOverride("MissingRV", "mean", 1.0))
        )

        val result = RunConfigurationValidator.validateForRun(config, vanProvider)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("rvOverrides[0].rvName", "RV_NAME_UNKNOWN")
        )
    }

    @Test
    fun `scenario validation reports field paths under scenario index`() {
        val config = vanConfig().copy(
            scenarios = listOf(
                ScenarioSpec(
                    name = "BadScenario",
                    runParameters = vanConfig().experimentRunParameters,
                    controls = vanConfig().withFuelType("STEAM").controls
                )
            )
        )

        val result = RunConfigurationValidator.validate(config)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "scenarios[0].controls.string.$FUEL_TYPE_KEY.value",
                "STRING_CONTROL_VALUE_NOT_ALLOWED"
            )
        )
    }

    @Test
    fun `valid van run configuration passes document and runtime validation`() {
        val documentResult = RunConfigurationValidator.validate(vanConfig())
        val runtimeResult = RunConfigurationValidator.validateForRun(vanConfig(), vanProvider)

        assertTrue(documentResult.isValid)
        assertTrue(runtimeResult.isValid)
    }

    private fun RunConfiguration.withFuelType(value: String): RunConfiguration {
        val updated = controls.stringControls.map { control ->
            if (control.keyName == FUEL_TYPE_KEY) control.copy(value = value)
            else control
        }
        return copy(controls = controls.copy(stringControls = updated))
    }

    private fun RunConfiguration.withNumSeats(value: Double): RunConfiguration {
        val updated = controls.numericControls.map { control ->
            if (control.keyName == NUM_SEATS_KEY) control.copy(value = value)
            else control
        }
        return copy(controls = controls.copy(numericControls = updated))
    }

    private fun ValidationResult.hasError(path: String, code: String): Boolean =
        errors.any { it.path == path && it.code == code }

    private fun ValidationResult.hasWarning(path: String, code: String): Boolean =
        warnings.any { it.path == path && it.code == code }

    private companion object {
        const val VAN_MODEL_ID = "VanModel"
        const val VAN_ELEMENT_NAME = "Van_1"
        const val FUEL_TYPE_KEY = "Van_1.fuelType"
        const val NUM_SEATS_KEY = "Van_1.numSeats"
    }
}
