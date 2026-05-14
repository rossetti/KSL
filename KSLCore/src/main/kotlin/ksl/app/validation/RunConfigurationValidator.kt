/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.validation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.TracingConfig
import ksl.controls.ControlImportResult
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunParameters
import ksl.simulation.Model
import ksl.simulation.ModelProviderIfc
import ksl.utilities.io.JARModelBuilder
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration

/**
 * Pre-run validator for [RunConfiguration].
 *
 * [validate] performs document-only checks and never constructs a model. This
 * is appropriate for early GUI validation while the user edits a file or form.
 *
 * [validateForRun] performs [validate] first, then uses the supplied
 * [ModelProviderIfc] or JAR reference to build short-lived probe models for
 * checks that need live model structure: provider resolution, control key
 * existence, control assignment validation, and RV parameter existence.
 */
object RunConfigurationValidator {

    /**
     * Validates fields that can be checked from the serialised document alone.
     */
    fun validate(config: RunConfiguration): ValidationResult {
        val builder = ValidationResultBuilder()
        validateModelReference(config.modelReference, "modelReference", builder)
        validateRunParameters(config.experimentRunParameters, "experimentRunParameters", builder)
        validateControls(config.controls, "controls", builder)
        validateRvOverrides(config.rvOverrides, "rvOverrides", builder)
        validateTracingConfig(config.tracingConfig, "tracingConfig", builder)
        validateScenarioSpecs(config.scenarios, builder)
        return builder.build()
    }

    /**
     * Validates fields that require runtime context in addition to document checks.
     *
     * If document-level errors exist, runtime checks are skipped. This avoids
     * building models from a configuration that is already known to be invalid
     * and prevents duplicate control errors.
     */
    fun validateForRun(
        config: RunConfiguration,
        provider: ModelProviderIfc? = null
    ): ValidationResult {
        val documentResult = validate(config)
        if (!documentResult.isValid) return documentResult

        val builder = ValidationResultBuilder(documentResult)
        val model = resolveProbeModel(config.modelReference, provider, "modelReference", builder)
            ?: return builder.build()

        validateControlsAgainstModel(config.controls, "controls", model, builder)
        validateRvOverridesAgainstModel(config.rvOverrides, "rvOverrides", model, builder)

        for ((index, scenario) in config.scenarios.withIndex()) {
            val scenarioPrefix = "scenarios[$index]"
            val scenarioModel = resolveProbeModel(config.modelReference, provider, "modelReference", builder)
                ?: break
            validateControlsAgainstModel(
                scenario.controls,
                "$scenarioPrefix.controls",
                scenarioModel,
                builder
            )
            validateRvOverridesAgainstModel(
                scenario.rvOverrides,
                "$scenarioPrefix.rvOverrides",
                scenarioModel,
                builder
            )
        }

        return builder.build()
    }

    private fun validateModelReference(
        reference: ModelReference,
        path: String,
        builder: ValidationResultBuilder
    ) {
        when (reference) {
            is ModelReference.ByProviderId -> {
                if (reference.providerId.isBlank()) {
                    builder.error(
                        path = "$path.providerId",
                        code = "MODEL_PROVIDER_ID_BLANK",
                        message = "Model provider id must not be blank."
                    )
                }
            }

            is ModelReference.ByJar -> {
                if (reference.jarPath.isBlank()) {
                    builder.error(
                        path = "$path.jarPath",
                        code = "JAR_PATH_BLANK",
                        message = "JAR path must not be blank."
                    )
                }
                if (reference.builderClassName != null && reference.builderClassName.isBlank()) {
                    builder.error(
                        path = "$path.builderClassName",
                        code = "JAR_BUILDER_CLASS_BLANK",
                        message = "JAR builder class name must be null or nonblank."
                    )
                }
            }

            is ModelReference.ByBundleAndModelId -> {
                if (reference.bundleId.isBlank()) {
                    builder.error(
                        path = "$path.bundleId",
                        code = "MODEL_BUNDLE_ID_BLANK",
                        message = "Bundle id must not be blank."
                    )
                }
                if (reference.modelId.isBlank()) {
                    builder.error(
                        path = "$path.modelId",
                        code = "MODEL_ID_BLANK",
                        message = "Model id must not be blank."
                    )
                }
            }
        }
    }

    private fun validateRunParameters(
        params: ExperimentRunParameters,
        path: String,
        builder: ValidationResultBuilder
    ) {
        if (params.experimentName.isBlank()) {
            builder.warning(
                path = "$path.experimentName",
                code = "EXPERIMENT_NAME_BLANK",
                message = "Experiment name is blank."
            )
        }
        if (params.runName.isBlank()) {
            builder.warning(
                path = "$path.runName",
                code = "RUN_NAME_BLANK",
                message = "Run name is blank."
            )
        }
        if (params.numberOfReplications < 1) {
            builder.error(
                path = "$path.numberOfReplications",
                code = "NUMBER_OF_REPLICATIONS_INVALID",
                message = "Number of replications must be at least 1."
            )
        }
        if (params.startingRepId < 1) {
            builder.error(
                path = "$path.startingRepId",
                code = "STARTING_REP_ID_INVALID",
                message = "Starting replication id must be at least 1."
            )
        }
        if (params.lengthOfReplication <= 0.0) {
            builder.error(
                path = "$path.lengthOfReplication",
                code = "LENGTH_OF_REPLICATION_INVALID",
                message = "Length of replication must be greater than 0.0."
            )
        }
        if (params.lengthOfReplicationWarmUp < 0.0) {
            builder.error(
                path = "$path.lengthOfReplicationWarmUp",
                code = "WARM_UP_LENGTH_INVALID",
                message = "Warm-up length must be greater than or equal to 0.0."
            )
        }
        if (params.lengthOfReplication <= params.lengthOfReplicationWarmUp) {
            builder.error(
                path = "$path.lengthOfReplicationWarmUp",
                code = "WARM_UP_NOT_LESS_THAN_REPLICATION_LENGTH",
                message = "Warm-up length must be less than the replication length."
            )
        }
        if (params.numberOfStreamAdvancesPriorToRunning < 0) {
            builder.error(
                path = "$path.numberOfStreamAdvancesPriorToRunning",
                code = "STREAM_ADVANCES_INVALID",
                message = "Number of stream advances prior to running must be nonnegative."
            )
        }
        if (params.maximumAllowedExecutionTimePerReplication < Duration.ZERO) {
            builder.error(
                path = "$path.maximumAllowedExecutionTimePerReplication",
                code = "MAX_EXECUTION_TIME_INVALID",
                message = "Maximum allowed execution time per replication must be nonnegative."
            )
        }
    }

    private fun validateControls(
        controls: ModelControlsExport,
        path: String,
        builder: ValidationResultBuilder
    ) {
        validateDuplicateKeys(
            controls.numericControls.mapIndexed { index, control -> IndexedKey(index, control.keyName) },
            "$path.numeric",
            builder
        )
        validateDuplicateKeys(
            controls.stringControls.mapIndexed { index, control -> IndexedKey(index, control.keyName) },
            "$path.string",
            builder
        )
        validateDuplicateKeys(
            controls.jsonControls.mapIndexed { index, control -> IndexedKey(index, control.keyName) },
            "$path.json",
            builder
        )

        for (control in controls.numericControls) {
            val controlPath = "$path.numeric.${control.keyName}.value"
            if (control.lowerBound > control.upperBound) {
                builder.error(
                    path = "$path.numeric.${control.keyName}",
                    code = "NUMERIC_CONTROL_BOUNDS_INVALID",
                    message = "Lower bound ${control.lowerBound} exceeds upper bound ${control.upperBound}."
                )
            } else if (control.value < control.lowerBound || control.value > control.upperBound) {
                builder.warning(
                    path = controlPath,
                    code = "NUMERIC_CONTROL_VALUE_OUT_OF_BOUNDS",
                    message = "Value ${control.value} is outside [${control.lowerBound}, ${control.upperBound}] and will be clamped by the control setter."
                )
            }
        }

        for (control in controls.stringControls) {
            if (control.allowedValues.isNotEmpty() && control.value !in control.allowedValues) {
                builder.error(
                    path = "$path.string.${control.keyName}.value",
                    code = "STRING_CONTROL_VALUE_NOT_ALLOWED",
                    message = "Value '${control.value}' is not in allowed values ${control.allowedValues}."
                )
            }
        }

        for (control in controls.jsonControls) {
            try {
                Json.parseToJsonElement(control.jsonValue)
            } catch (e: SerializationException) {
                builder.error(
                    path = "$path.json.${control.keyName}.jsonValue",
                    code = "JSON_CONTROL_VALUE_INVALID",
                    message = "JSON control value is not valid JSON: ${e.message}"
                )
            }
        }
    }

    private fun validateRvOverrides(
        overrides: List<RVParameterOverride>,
        path: String,
        builder: ValidationResultBuilder
    ) {
        val seen = mutableMapOf<Pair<String, String>, Int>()
        for ((index, override) in overrides.withIndex()) {
            val itemPath = "$path[$index]"
            if (override.rvName.isBlank()) {
                builder.error(
                    path = "$itemPath.rvName",
                    code = "RV_NAME_BLANK",
                    message = "Random variable name must not be blank."
                )
            }
            if (override.paramName.isBlank()) {
                builder.error(
                    path = "$itemPath.paramName",
                    code = "RV_PARAMETER_NAME_BLANK",
                    message = "Random variable parameter name must not be blank."
                )
            }
            val key = override.rvName to override.paramName
            val firstIndex = seen.putIfAbsent(key, index)
            if (firstIndex != null) {
                builder.warning(
                    path = itemPath,
                    code = "RV_PARAMETER_OVERRIDE_DUPLICATE",
                    message = "Duplicate RV override for '${override.rvName}.${override.paramName}'; the later value will win when applied."
                )
            }
        }
    }

    private fun validateTracingConfig(
        tracingConfig: TracingConfig,
        path: String,
        builder: ValidationResultBuilder
    ) {
        if (tracingConfig.animationTraceFile != null && tracingConfig.animationTraceFile.isBlank()) {
            builder.error(
                path = "$path.animationTraceFile",
                code = "TRACE_FILE_BLANK",
                message = "Animation trace file must be null or nonblank."
            )
        }
        if (tracingConfig.flushEveryNEvents <= 0) {
            builder.error(
                path = "$path.flushEveryNEvents",
                code = "TRACE_FLUSH_INTERVAL_INVALID",
                message = "Trace flush interval must be greater than 0."
            )
        }
    }

    private fun validateScenarioSpecs(
        scenarios: List<ScenarioSpec>,
        builder: ValidationResultBuilder
    ) {
        val names = mutableMapOf<String, Int>()
        for ((index, scenario) in scenarios.withIndex()) {
            val path = "scenarios[$index]"
            if (scenario.name.isBlank()) {
                builder.error(
                    path = "$path.name",
                    code = "SCENARIO_NAME_BLANK",
                    message = "Scenario name must not be blank."
                )
            }
            val firstIndex = names.putIfAbsent(scenario.name, index)
            if (firstIndex != null) {
                builder.error(
                    path = "$path.name",
                    code = "SCENARIO_NAME_DUPLICATE",
                    message = "Scenario name '${scenario.name}' duplicates scenarios[$firstIndex].name."
                )
            }
            validateRunParameters(scenario.runParameters, "$path.runParameters", builder)
            validateControls(scenario.controls, "$path.controls", builder)
            validateRvOverrides(scenario.rvOverrides, "$path.rvOverrides", builder)
        }
    }


    internal fun resolveProbeModel(
        reference: ModelReference,
        provider: ModelProviderIfc?,
        path: String,
        builder: ValidationResultBuilder
    ): Model? {
        return when (reference) {
            is ModelReference.ByProviderId -> resolveProviderModel(reference, provider, path, builder)
            is ModelReference.ByJar -> resolveJarModel(reference, path, builder)
            is ModelReference.ByBundleAndModelId -> {
                // The follow-up commit that reshapes ScenarioSpec adds proper resolution
                // here via BundleModelProvider.provideModel(bundleId, modelId).  Until
                // then, surface the unsupported usage as a validation error rather than
                // a runtime exception so the document health banner can show it.
                builder.error(
                    path = path,
                    code = "MODEL_REFERENCE_BY_BUNDLE_NOT_YET_SUPPORTED",
                    message = "ModelReference.ByBundleAndModelId(${reference.bundleId}, " +
                            "${reference.modelId}) is declared but not yet supported by " +
                            "the validator. Pending the substrate-prep commit that reshapes " +
                            "ScenarioSpec for per-scenario model selection."
                )
                null
            }
        }
    }

    private fun resolveProviderModel(
        reference: ModelReference.ByProviderId,
        provider: ModelProviderIfc?,
        path: String,
        builder: ValidationResultBuilder
    ): Model? {
        if (provider == null) {
            builder.error(
                path = "$path.providerId",
                code = "MODEL_PROVIDER_REQUIRED",
                message = "A ModelProviderIfc is required to validate provider id '${reference.providerId}'."
            )
            return null
        }
        val isProvided = try {
            provider.isModelProvided(reference.providerId)
        } catch (e: Exception) {
            builder.error(
                path = "$path.providerId",
                code = "MODEL_PROVIDER_LOOKUP_FAILED",
                message = "Model provider lookup failed for '${reference.providerId}': ${e.message}"
            )
            return null
        }
        if (!isProvided) {
            builder.error(
                path = "$path.providerId",
                code = "MODEL_PROVIDER_ID_UNKNOWN",
                message = "No model is registered for provider id '${reference.providerId}'."
            )
            return null
        }
        return try {
            provider.provideModel(reference.providerId)
        } catch (e: Exception) {
            builder.error(
                path = "$path.providerId",
                code = "MODEL_BUILD_FAILED",
                message = "Model provider failed to build '${reference.providerId}': ${e.message}"
            )
            null
        }
    }

    private fun resolveJarModel(
        reference: ModelReference.ByJar,
        path: String,
        builder: ValidationResultBuilder
    ): Model? {
        val jarPath = Paths.get(reference.jarPath)
        if (!Files.exists(jarPath)) {
            builder.error(
                path = "$path.jarPath",
                code = "JAR_PATH_NOT_FOUND",
                message = "JAR file not found: ${reference.jarPath}"
            )
            return null
        }
        return try {
            JARModelBuilder(jarPath, reference.builderClassName).use { it.build() }
        } catch (e: Exception) {
            builder.error(
                path = "$path.jarPath",
                code = "JAR_MODEL_BUILD_FAILED",
                message = "JAR model build failed for '${reference.jarPath}': ${e.message}"
            )
            null
        }
    }

    internal fun validateControlsAgainstModel(
        controls: ModelControlsExport,
        path: String,
        model: Model,
        builder: ValidationResultBuilder
    ) {
        if (controls.modelName.isNotBlank() && controls.modelName != model.name) {
            builder.warning(
                path = "$path.modelName",
                code = "CONTROL_MODEL_NAME_MISMATCH",
                message = "Control export modelName '${controls.modelName}' does not match model '${model.name}'."
            )
        }
        if (controls.totalControls == 0) return
        val importResult = model.controls().importAll(controls)
        adaptControlImportResult(controls, path, importResult, builder)
    }

    internal fun validateRvOverridesAgainstModel(
        overrides: List<RVParameterOverride>,
        path: String,
        model: Model,
        builder: ValidationResultBuilder
    ) {
        if (overrides.isEmpty()) return
        val setter = RVParameterSetter(model)
        for ((index, override) in overrides.withIndex()) {
            val itemPath = "$path[$index]"
            if (override.rvName !in setter.randomVariableNames) {
                builder.error(
                    path = "$itemPath.rvName",
                    code = "RV_NAME_UNKNOWN",
                    message = "Random variable '${override.rvName}' is not parameterized in model '${model.name}'."
                )
            } else if (!setter.containsParameter(override.rvName, override.paramName)) {
                builder.error(
                    path = "$itemPath.paramName",
                    code = "RV_PARAMETER_NAME_UNKNOWN",
                    message = "Random variable '${override.rvName}' has no parameter named '${override.paramName}'."
                )
            }
        }
    }

    private fun adaptControlImportResult(
        controls: ModelControlsExport,
        path: String,
        result: ControlImportResult,
        builder: ValidationResultBuilder
    ) {
        for (key in result.missingKeys) {
            builder.error(
                path = controlPath(controls, path, key),
                code = "CONTROL_KEY_UNKNOWN",
                message = "Control key '$key' is not present in the target model."
            )
        }
        for (failure in result.failures) {
            builder.error(
                path = controlPath(controls, path, failure.controlKey),
                code = "CONTROL_VALUE_REJECTED",
                message = failure.message ?: "Control '${failure.controlKey}' rejected value '${failure.attemptedValue}'."
            )
        }
    }

    private fun controlPath(
        controls: ModelControlsExport,
        prefix: String,
        key: String
    ): String {
        return when {
            controls.numericControls.any { it.keyName == key } -> "$prefix.numeric.$key.value"
            controls.stringControls.any { it.keyName == key } -> "$prefix.string.$key.value"
            controls.jsonControls.any { it.keyName == key } -> "$prefix.json.$key.jsonValue"
            else -> "$prefix.$key"
        }
    }

    private fun validateDuplicateKeys(
        keys: List<IndexedKey>,
        path: String,
        builder: ValidationResultBuilder
    ) {
        val seen = mutableMapOf<String, Int>()
        for ((index, key) in keys) {
            val firstIndex = seen.putIfAbsent(key, index)
            if (firstIndex != null) {
                builder.error(
                    path = "$path[$index].keyName",
                    code = "CONTROL_KEY_DUPLICATE",
                    message = "Control key '$key' duplicates $path[$firstIndex].keyName."
                )
            }
        }
    }

    private data class IndexedKey(val index: Int, val key: String)

    internal class ValidationResultBuilder(
        initialResult: ValidationResult = ValidationResult()
    ) {
        private val myErrors = initialResult.errors.toMutableList()
        private val myWarnings = initialResult.warnings.toMutableList()

        fun error(path: String, code: String, message: String) {
            add(path, code, message, ValidationSeverity.ERROR)
        }

        fun warning(path: String, code: String, message: String) {
            add(path, code, message, ValidationSeverity.WARNING)
        }

        fun build(): ValidationResult =
            ValidationResult(errors = myErrors.toList(), warnings = myWarnings.toList())

        private fun add(
            path: String,
            code: String,
            message: String,
            severity: ValidationSeverity
        ) {
            val issue = FieldError(path, message, severity, code)
            when (severity) {
                ValidationSeverity.ERROR -> myErrors.add(issue)
                ValidationSeverity.WARNING -> myWarnings.add(issue)
            }
        }
    }
}
