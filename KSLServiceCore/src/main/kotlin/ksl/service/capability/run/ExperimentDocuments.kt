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

package ksl.service.capability.run

import kotlinx.serialization.json.Json
import ksl.app.config.ModelReference
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ReplicationSpec
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import ksl.simulation.ModelDescriptor
import ksl.service.store.ResultStore
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * The experiment capability's document surface (Phase 8 Tier B): JSON codec, a
 * ready-to-edit scaffold, content key, and model-aware validation for an
 * [ExperimentConfiguration] — the experiment counterpart to `RunConfigurationJson`
 * + `RunTemplates` + `ResultKeys` + the run validator. Kept in the service layer
 * (the substrate's `ExperimentConfiguration` is `@Serializable`), so no KSLCore
 * codec is required.
 *
 * Structural validation (≥1 factor, unique names, design/level consistency) is
 * enforced by `ExperimentConfiguration.init`, so [decode] throws on a malformed
 * document; [validate] adds the *model-aware* check (every factor binds to a real
 * control / RV parameter) that the substrate otherwise defers to submit time.
 */
object ExperimentDocuments {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    /** Serializes an [ExperimentConfiguration] to JSON (the authored document). */
    fun encode(config: ExperimentConfiguration): String =
        json.encodeToString(ExperimentConfiguration.serializer(), config)

    /** Parses an [ExperimentConfiguration] from JSON; throws on structural errors (`init`). */
    fun decode(text: String): ExperimentConfiguration =
        json.decodeFromString(ExperimentConfiguration.serializer(), text)

    /** The content key the batch result is cached under (the document is the request).
     *  [versionSalt] folds in the model's code version so a rebuilt model invalidates it. */
    fun key(config: ExperimentConfiguration, versionSalt: String = ""): String =
        ResultStore.sha256("$versionSalt|experiment-doc:" + encode(config))

    /**
     * A ready-to-edit two-level factorial scaffold over the model's first two
     * numeric controls (low = current value, high = +1), 10 reps per point. The
     * author edits the levels/factors and submits. Requires ≥2 numeric controls
     * (a factorial needs at least two factors).
     */
    fun template(descriptor: ModelDescriptor, modelId: String): ExperimentConfiguration {
        val controls = descriptor.controls.numericControls.take(2)
        require(controls.size >= 2) {
            "a factorial experiment needs at least two numeric controls; '$modelId' has ${descriptor.controls.numericControls.size}"
        }
        val factors = controls.map { control ->
            FactorSpec(
                name = control.keyName,
                levels = listOf(control.value, control.value + 1.0),
                binding = ControlBinding.Control(control.keyName),
            )
        }
        return ExperimentConfiguration(
            modelReference = ModelReference.ByProviderId(modelId),
            factors = factors,
            designSpec = DesignSpec.TwoLevelFactorial(),
            replications = ReplicationSpec.Uniform(10),
        )
    }

    /**
     * Model-aware validation: every factor must bind to a control key or RV
     * parameter the model actually exposes ([ModelDescriptor.inputNames]).
     * Structural validity is already guaranteed by [decode].
     */
    fun validate(config: ExperimentConfiguration, descriptor: ModelDescriptor): ValidationResult {
        val errors = mutableListOf<FieldError>()
        config.factors.forEachIndexed { i, factor ->
            val key = when (val binding = factor.binding) {
                is ControlBinding.Control -> binding.controlKey
                is ControlBinding.RVParameter -> "${binding.rvName}${RVParameterSetter.rvParamConCatChar}${binding.paramName}"
            }
            if (key !in descriptor.inputNames) {
                errors.add(
                    FieldError(
                        path = "factors[$i].binding",
                        message = "input key '$key' is not a control or RV parameter of model '${descriptor.modelIdentifier}'",
                        severity = ValidationSeverity.ERROR,
                        code = "unknownInput",
                    ),
                )
            }
        }
        return ValidationResult(errors = errors)
    }
}
