package ksl.app.validation

import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.controls.ModelControlsExport
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
 * Acceptance tests for Phase 3 validation, reshaped for the Phase 6B
 * substrate-prep: validation now runs per scenario.  The Van model
 * (an `@KSLControl`-rich example with string and numeric control
 * keys) is the test vehicle.
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

    /** Returns a fully-populated `ModelControlsExport` snapshot of Van's controls. */
    private fun vanControls(): ModelControlsExport = vanModel().controls().exportAll()

    /**
     * Builds a single-scenario `RunConfiguration` whose scenario has the given
     * `ModelControlsExport` as its `controlOverrides` (and no other overrides).
     */
    private fun vanConfigWithControls(
        controls: ModelControlsExport,
        modelReference: ModelReference = ModelReference.ByProviderId(VAN_MODEL_ID)
    ): RunConfiguration = RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "VanScenario",
                modelReference = modelReference,
                controlOverrides = controls
            )
        )
    )

    private fun vanConfig(): RunConfiguration = vanConfigWithControls(vanControls())

    @Test
    fun `invalid string control value is reported at the scenario path`() {
        val config = vanConfigWithControls(vanControls().withFuelType("STEAM"))

        val result = RunConfigurationValidator.validate(config)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "scenarios[0].controlOverrides.string.$FUEL_TYPE_KEY.value",
                "STRING_CONTROL_VALUE_NOT_ALLOWED"
            ),
            "Expected invalid fuelType to be reported under the scenario's controlOverrides"
        )
    }

    @Test
    fun `warm-up length must be less than replication length per scenario`() {
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "BadParams",
                    modelReference = ModelReference.ByProviderId(VAN_MODEL_ID),
                    runOverrides = ExperimentRunOverrides(
                        lengthOfReplication = 10.0,
                        lengthOfReplicationWarmUp = 10.0
                    )
                )
            )
        )

        val result = RunConfigurationValidator.validate(config)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "scenarios[0].runOverrides.lengthOfReplicationWarmUp",
                "WARM_UP_NOT_LESS_THAN_REPLICATION_LENGTH"
            )
        )
    }

    @Test
    fun `validateForRun reports missing provider for provider-id reference`() {
        val result = RunConfigurationValidator.validateForRun(vanConfig(), provider = null)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("scenarios[0].modelReference.providerId", "MODEL_PROVIDER_REQUIRED"),
            "Expected MODEL_PROVIDER_REQUIRED under the scenario's modelReference; got " +
                    "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    @Test
    fun `validateForRun reports unknown provider id`() {
        val config = vanConfigWithControls(
            controls = vanControls(),
            modelReference = ModelReference.ByProviderId("MissingModel")
        )

        val result = RunConfigurationValidator.validateForRun(config, vanProvider)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("scenarios[0].modelReference.providerId", "MODEL_PROVIDER_ID_UNKNOWN"),
            "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    @Test
    fun `numeric control value outside bounds is a warning because KSL clamps it`() {
        val config = vanConfigWithControls(vanControls().withNumSeats(100.0))

        val result = RunConfigurationValidator.validate(config)

        assertTrue(result.isValid)
        assertTrue(
            result.hasWarning(
                "scenarios[0].controlOverrides.numeric.$NUM_SEATS_KEY.value",
                "NUMERIC_CONTROL_VALUE_OUT_OF_BOUNDS"
            )
        )
    }

    @Test
    fun `validateForRun adapts missing live control key to field error`() {
        val renamed = vanControls().let { c ->
            c.copy(stringControls = c.stringControls.map { control ->
                if (control.keyName == FUEL_TYPE_KEY) control.copy(keyName = "Van_1.missingFuelType")
                else control
            })
        }
        val config = vanConfigWithControls(renamed)

        val result = RunConfigurationValidator.validateForRun(config, vanProvider)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "scenarios[0].controlOverrides.string.Van_1.missingFuelType.value",
                "CONTROL_KEY_UNKNOWN"
            ),
            "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    @Test
    fun `validateForRun reports unknown RV override name`() {
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "VanScenario",
                    modelReference = ModelReference.ByProviderId(VAN_MODEL_ID),
                    rvOverrides = listOf(RVParameterOverride("MissingRV", "mean", 1.0))
                )
            )
        )

        val result = RunConfigurationValidator.validateForRun(config, vanProvider)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError("scenarios[0].rvOverrides[0].rvName", "RV_NAME_UNKNOWN"),
            "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    @Test
    fun `multi-scenario validation reports paths under each scenario index`() {
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "GoodScenario",
                    modelReference = ModelReference.ByProviderId(VAN_MODEL_ID),
                    controlOverrides = vanControls()
                ),
                ScenarioSpec(
                    name = "BadScenario",
                    modelReference = ModelReference.ByProviderId(VAN_MODEL_ID),
                    controlOverrides = vanControls().withFuelType("STEAM")
                )
            )
        )

        val result = RunConfigurationValidator.validate(config)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "scenarios[1].controlOverrides.string.$FUEL_TYPE_KEY.value",
                "STRING_CONTROL_VALUE_NOT_ALLOWED"
            ),
            "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    @Test
    fun `valid van run configuration passes document and runtime validation`() {
        val documentResult = RunConfigurationValidator.validate(vanConfig())
        val runtimeResult = RunConfigurationValidator.validateForRun(vanConfig(), vanProvider)

        assertTrue(documentResult.isValid, "errors: ${documentResult.errors.map { it.path to it.code }}")
        assertTrue(runtimeResult.isValid, "errors: ${runtimeResult.errors.map { it.path to it.code }}")
    }

    @Test
    fun `scenario referencing a bundleId not declared in bundleRefs is reported`() {
        val config = RunConfiguration(
            bundleRefs = emptyList(),
            scenarios = listOf(
                ScenarioSpec(
                    name = "Orphan",
                    modelReference = ModelReference.ByBundleAndModelId(
                        bundleId = "edu.example.missing",
                        modelId = "M1"
                    )
                )
            )
        )

        val result = RunConfigurationValidator.validate(config)

        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "scenarios[0].modelReference.bundleId",
                "SCENARIO_BUNDLE_REF_MISSING"
            ),
            "errors: ${result.errors.map { it.path to it.code }}"
        )
    }

    private fun ModelControlsExport.withFuelType(value: String): ModelControlsExport {
        val updated = stringControls.map { control ->
            if (control.keyName == FUEL_TYPE_KEY) control.copy(value = value)
            else control
        }
        return copy(stringControls = updated)
    }

    private fun ModelControlsExport.withNumSeats(value: Double): ModelControlsExport {
        val updated = numericControls.map { control ->
            if (control.keyName == NUM_SEATS_KEY) control.copy(value = value)
            else control
        }
        return copy(numericControls = updated)
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
