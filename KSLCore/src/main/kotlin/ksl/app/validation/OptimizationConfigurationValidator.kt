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

import ksl.app.config.optimization.CoolingScheduleSpec
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.ResponseConstraintSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.TemperatureSpec
import ksl.simulation.Model
import ksl.simulation.ModelProviderIfc

/**
 * Pre-run validator for [OptimizationRunConfiguration].
 *
 * Companion to [RunConfigurationValidator].  Where the type system already
 * enforces single-field and same-class invariants in the spec data classes
 * (via `init` blocks on the `@Serializable` types), this validator focuses
 * exclusively on **cross-reference and model-dependent** checks that the
 * type system cannot express:
 *
 * - decision-variable references in starting points and constraints,
 * - response-name references in response constraints,
 * - simulated-annealing temperature relationships across nested specs,
 * - model-resolution and key-existence checks against a built probe model,
 * - control-vs-decision-variable name conflicts on the model.
 *
 * [validate] performs document-only checks and never builds a model.
 *
 * [validateForRun] runs [validate] first; if those pass, it builds a
 * short-lived probe model from [OptimizationRunConfiguration.model] (via
 * [RunConfigurationValidator.resolveProbeModel]) and runs the model-aware
 * checks.  If document-level errors exist, runtime checks are skipped to
 * avoid building models from a configuration that is already known to be
 * invalid and to prevent duplicate errors.
 *
 * The validator does **not** call
 * [ksl.simopt.problem.ProblemDefinition.validateProblemDefinition].  Its
 * own checks cover the same ground (objective response, decision-variable
 * names, response-constraint names) without depending on engine internals;
 * `OptimizationSolverFactory` invokes the engine validator at
 * solver-build time.
 */
object OptimizationConfigurationValidator {

    /**
     * Validates fields that can be checked from the spec alone.
     */
    fun validate(config: OptimizationRunConfiguration): ValidationResult {
        val builder = RunConfigurationValidator.ValidationResultBuilder()
        val inputNames: Set<String> = config.problem.inputs.map { it.name }.toSet()
        val declaredResponseNames: Set<String> = config.problem.responseNames.toSet()

        validateStartingPoint(config.solver, config.problem.inputs, inputNames, builder)
        validateLinearConstraints(config.problem.linearConstraints, inputNames, builder)
        validateResponseConstraintsDocument(
            config.problem.responseConstraints,
            declaredResponseNames,
            builder
        )
        validateSimulatedAnnealing(config.solver, builder)
        return builder.build()
    }

    /**
     * Validates fields that require runtime context in addition to document
     * checks.  If document-level errors exist, runtime checks are skipped.
     */
    fun validateForRun(
        config: OptimizationRunConfiguration,
        provider: ModelProviderIfc? = null
    ): ValidationResult {
        val documentResult = validate(config)
        if (!documentResult.isValid) return documentResult

        val builder = RunConfigurationValidator.ValidationResultBuilder(documentResult)
        val model = RunConfigurationValidator.resolveProbeModel(
            config.model.modelReference,
            provider,
            "model.modelReference",
            builder
        ) ?: return builder.build()

        RunConfigurationValidator.validateControlsAgainstModel(
            config.model.controls,
            "model.controls",
            model,
            builder
        )
        RunConfigurationValidator.validateRvOverridesAgainstModel(
            config.model.rvOverrides,
            "model.rvOverrides",
            model,
            builder
        )
        validateInputsAgainstModel(config.problem.inputs, model, builder)
        validateObjectiveResponseAgainstModel(config.problem.objectiveResponseName, model, builder)
        validateResponseConstraintsAgainstModel(config.problem.responseConstraints, model, builder)
        validateInputControlConflict(config, model, builder)

        return builder.build()
    }

    // ── Document-only checks ─────────────────────────────────────────────────

    private fun validateStartingPoint(
        solver: SolverSpec,
        declaredInputs: List<OptimizationInputSpec>,
        inputNames: Set<String>,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        val startingPoint = solver.startingPoint ?: return
        val inputByName = declaredInputs.associateBy { it.name }

        for ((key, value) in startingPoint) {
            val keyPath = "solver.startingPoint.$key"
            if (key !in inputNames) {
                builder.error(
                    path = keyPath,
                    code = "STARTING_POINT_UNKNOWN_INPUT",
                    message = "Starting-point key '$key' is not a declared decision variable."
                )
                continue
            }
            val input = inputByName.getValue(key)
            if (value < input.lowerBound || value > input.upperBound) {
                builder.error(
                    path = keyPath,
                    code = "STARTING_POINT_OUT_OF_BOUNDS",
                    message = "Starting-point value $value for '$key' is outside [${input.lowerBound}, ${input.upperBound}]."
                )
            }
        }
    }

    private fun validateLinearConstraints(
        constraints: List<LinearConstraintSpec>,
        inputNames: Set<String>,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        for ((index, constraint) in constraints.withIndex()) {
            for (key in constraint.coefficients.keys) {
                if (key !in inputNames) {
                    builder.error(
                        path = "problem.linearConstraints[$index].coefficients.$key",
                        code = "LINEAR_CONSTRAINT_UNKNOWN_INPUT",
                        message = "Linear-constraint coefficient key '$key' is not a declared decision variable."
                    )
                }
            }
        }
    }

    private fun validateResponseConstraintsDocument(
        constraints: List<ResponseConstraintSpec>,
        declaredResponseNames: Set<String>,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        for ((index, constraint) in constraints.withIndex()) {
            if (constraint.name !in declaredResponseNames) {
                builder.error(
                    path = "problem.responseConstraints[$index].name",
                    code = "RESPONSE_CONSTRAINT_NAME_UNDECLARED",
                    message = "Response-constraint name '${constraint.name}' is not declared in problem.responseNames."
                )
            }
        }
    }

    private fun validateSimulatedAnnealing(
        solver: SolverSpec,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        if (solver !is SolverSpec.SimulatedAnnealing) return
        val fixed = solver.temperature as? TemperatureSpec.Fixed ?: return

        if (solver.stoppingTemperature >= fixed.temperature) {
            builder.error(
                path = "solver.stoppingTemperature",
                code = "SA_STOPPING_NOT_BELOW_INITIAL",
                message = "stoppingTemperature (${solver.stoppingTemperature}) must be strictly less than the fixed initial temperature (${fixed.temperature})."
            )
        }

        val coolingInitialTemperature: Double? = when (val schedule = solver.coolingSchedule) {
            is CoolingScheduleSpec.Linear -> schedule.initialTemperature
            is CoolingScheduleSpec.Exponential -> schedule.initialTemperature
            is CoolingScheduleSpec.Logarithmic -> schedule.initialTemperature
        }
        if (coolingInitialTemperature != null && coolingInitialTemperature != fixed.temperature) {
            builder.warning(
                path = "solver.coolingSchedule.initialTemperature",
                code = "SA_COOLING_INITIAL_TEMP_MISMATCH",
                message = "Cooling-schedule initialTemperature ($coolingInitialTemperature) does not match the fixed initial temperature (${fixed.temperature}); the engine will overwrite the schedule's value at runtime."
            )
        }
    }

    // ── Runtime checks ───────────────────────────────────────────────────────

    private fun validateInputsAgainstModel(
        inputs: List<OptimizationInputSpec>,
        model: Model,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        val modelInputKeys: Set<String> = model.inputKeys().toSet()
        for ((index, input) in inputs.withIndex()) {
            if (input.name !in modelInputKeys) {
                builder.error(
                    path = "problem.inputs[$index].name",
                    code = "INPUT_NOT_ON_MODEL",
                    message = "Decision-variable name '${input.name}' is not a recognized input on model '${model.name}'."
                )
            }
        }
    }

    private fun validateObjectiveResponseAgainstModel(
        objectiveResponseName: String,
        model: Model,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        if (objectiveResponseName !in model.responseNames) {
            builder.error(
                path = "problem.objectiveResponseName",
                code = "OBJECTIVE_RESPONSE_UNKNOWN",
                message = "Objective response name '$objectiveResponseName' is not a response on model '${model.name}'."
            )
        }
    }

    private fun validateResponseConstraintsAgainstModel(
        constraints: List<ResponseConstraintSpec>,
        model: Model,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        val modelResponseNames: Set<String> = model.responseNames.toSet()
        for ((index, constraint) in constraints.withIndex()) {
            if (constraint.name !in modelResponseNames) {
                builder.error(
                    path = "problem.responseConstraints[$index].name",
                    code = "RESPONSE_CONSTRAINT_RESPONSE_UNKNOWN",
                    message = "Response-constraint name '${constraint.name}' is not a response on model '${model.name}'."
                )
            }
        }
    }

    private fun validateInputControlConflict(
        config: OptimizationRunConfiguration,
        model: Model,
        builder: RunConfigurationValidator.ValidationResultBuilder
    ) {
        // A baseline control whose key matches a decision-variable name is
        // ambiguous: the optimizer would overwrite the fixed value on every
        // evaluation.
        val controlKeys: Set<String> = collectControlKeys(config)
        if (controlKeys.isEmpty()) return

        val problemInputs = config.problem.inputs
        for ((index, input) in problemInputs.withIndex()) {
            if (input.name in controlKeys) {
                builder.error(
                    path = "problem.inputs[$index].name",
                    code = "INPUT_CONFLICTS_WITH_CONTROL",
                    message = "Decision-variable name '${input.name}' also appears as a fixed baseline control in model.controls; a name cannot be both a fixed control and an optimizer-controlled decision variable."
                )
            }
        }
    }

    /**
     * Collects every key declared in [OptimizationRunConfiguration.model]'s
     * [ksl.controls.ModelControlsExport], across all three control families.
     */
    private fun collectControlKeys(config: OptimizationRunConfiguration): Set<String> {
        val controls = config.model.controls
        val result = mutableSetOf<String>()
        controls.numericControls.forEach { result.add(it.keyName) }
        controls.stringControls.forEach { result.add(it.keyName) }
        controls.jsonControls.forEach { result.add(it.keyName) }
        return result
    }
}
