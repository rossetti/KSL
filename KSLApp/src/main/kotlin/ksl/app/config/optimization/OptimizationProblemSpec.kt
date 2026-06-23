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

package ksl.app.config.optimization

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 * App-layer mirror of [ksl.simopt.problem.OptimizationType].
 *
 * Mirrored (rather than reused) so that this configuration package is
 * independent of `ksl.simopt.problem`.  `OptimizationSolverFactory`
 * translates between this enum and the engine enum at solver-build time.
 */
@Serializable
enum class OptimizationType { MINIMIZE, MAXIMIZE }

/**
 * Serializable counterpart to a [ksl.simopt.problem.ProblemDefinition].
 *
 * Holds only persisted, plain-data fields needed to construct a problem
 * definition during run preparation.  Single-field and local cross-field
 * domain invariants are enforced in `init`.  Cross-reference checks
 * (e.g. response-constraint names must resolve against the built model;
 * decision-variable names must not collide with fixed baseline controls)
 * require additional context and remain the responsibility of
 * [ksl.app.validation.OptimizationConfigurationValidator].
 *
 * @property problemName optional human-readable name; non-blank when non-null
 * @property modelIdentifier optional model identifier; non-blank when non-null
 * @property objectiveResponseName name of the model response that is
 *           optimized; must be non-blank and must match a response on the
 *           built model
 * @property inputs decision variables controlled by the solver; must be
 *           non-empty and have unique [OptimizationInputSpec.name] values
 * @property responseNames additional response names that may be referenced
 *           by response constraints; every entry must be non-blank and the
 *           list must contain no duplicates; the objective response is
 *           implied and need not be repeated here
 * @property optimizationType minimize or maximize
 * @property indifferenceZoneParameter smallest objective-function
 *           difference considered practically meaningful; must be `>= 0`
 *           and finite; defaults to 0.0
 * @property objectiveGranularity granularity applied to the objective
 *           function value; must be `>= 0` and finite; 0.0 means full
 *           precision
 * @property linearConstraints optional linear constraints over decision
 *           variables
 * @property responseConstraints optional response constraints
 * @property defaultLinearPenalty problem-level default penalty function
 *           applied to any [LinearConstraintSpec] whose own
 *           [LinearConstraintSpec.penaltyFunction] is `null`; mirrors
 *           [ksl.simopt.problem.ProblemDefinition.defaultLinearPenalty]
 * @property defaultResponsePenalty problem-level default penalty function
 *           applied to any [ResponseConstraintSpec] whose own
 *           [ResponseConstraintSpec.penaltyFunction] is `null`; mirrors
 *           [ksl.simopt.problem.ProblemDefinition.defaultResponsePenalty]
 */
@Serializable
data class OptimizationProblemSpec(
    @TomlComment(
        "String or omitted. Optional human-readable problem name (e.g.\n" +
        "'InventoryOpt').  Must be non-blank when present.  Default: omitted."
    )
    val problemName: String? = null,

    @TomlComment(
        "String or omitted. Optional model identifier carried into the\n" +
        "engine's ProblemDefinition; usually left unset so the model's\n" +
        "own identifier is used.  Must be non-blank when present."
    )
    val modelIdentifier: String? = null,

    @TomlComment(
        "String. Name of the model response being optimized.  Must match\n" +
        "a Response on the built model.  Required (non-blank)."
    )
    val objectiveResponseName: String,

    @TomlComment(
        "Array of tables. Decision variables controlled by the solver.\n" +
        "Each entry is an [[problem.inputs]] table with name, lowerBound,\n" +
        "upperBound, and optional granularity.  Names must be unique;\n" +
        "the list must be non-empty."
    )
    val inputs: List<OptimizationInputSpec>,

    @TomlComment(
        "Array of strings. Additional response names referenced by\n" +
        "response constraints (the objective response is implied and\n" +
        "need not be repeated here).  Entries must be non-blank and\n" +
        "unique.  Default: empty."
    )
    val responseNames: List<String> = emptyList(),

    @TomlComment(
        "String. 'MINIMIZE' or 'MAXIMIZE'.  Default: 'MINIMIZE'."
    )
    val optimizationType: OptimizationType = OptimizationType.MINIMIZE,

    @TomlComment(
        "Number. Smallest objective-function difference considered\n" +
        "practically meaningful (Δ).  Must be >= 0 and finite.\n" +
        "Default: 0.0."
    )
    val indifferenceZoneParameter: Double = 0.0,

    @TomlComment(
        "Number. Granularity applied to the objective function value.\n" +
        "0.0 means full precision.  Must be >= 0 and finite.\n" +
        "Default: 0.0."
    )
    val objectiveGranularity: Double = 0.0,

    @TomlComment(
        "Array of tables. Linear constraints over the decision\n" +
        "variables.  Each entry is a [[problem.linearConstraints]] table\n" +
        "with coefficients, inequalityType, rhsValue, and optional per-\n" +
        "constraint penalty override.  Default: empty."
    )
    val linearConstraints: List<LinearConstraintSpec> = emptyList(),

    @TomlComment(
        "Array of tables. Constraints on simulation responses.  Each\n" +
        "entry is a [[problem.responseConstraints]] table with name,\n" +
        "inequalityType, rhsValue, target, tolerance, and optional per-\n" +
        "constraint penalty override.  Default: empty."
    )
    val responseConstraints: List<ResponseConstraintSpec> = emptyList(),

    @TomlComment(
        "Table. Problem-level default penalty function applied to every\n" +
        "linear constraint that doesn't carry its own penaltyFunction\n" +
        "override.  type = 'withMemory' or 'dynamicPolynomial'.\n" +
        "Default: { type = 'dynamicPolynomial', basePenalty = 100.0,\n" +
        "iterationExponent = 1.0, violationExponent = 2.0 }."
    )
    val defaultLinearPenalty: PenaltyFunctionSpec = PenaltyFunctionSpec.DynamicPolynomial(),

    @TomlComment(
        "Table. Problem-level default penalty function applied to every\n" +
        "response constraint that doesn't carry its own penaltyFunction\n" +
        "override.  type = 'withMemory' or 'dynamicPolynomial'.\n" +
        "Default: { type = 'withMemory', basePenalty = 100.0,\n" +
        "iterationExponent = 1.0, violationExponent = 2.0 }."
    )
    val defaultResponsePenalty: PenaltyFunctionSpec = PenaltyFunctionSpec.WithMemory()
) {
    init {
        require(problemName == null || problemName.isNotBlank()) {
            "problemName must be non-blank when non-null"
        }
        require(modelIdentifier == null || modelIdentifier.isNotBlank()) {
            "modelIdentifier must be non-blank when non-null"
        }
        require(objectiveResponseName.isNotBlank()) {
            "objectiveResponseName must be non-blank"
        }
        require(inputs.isNotEmpty()) {
            "inputs must not be empty; an optimization problem requires at least one decision variable"
        }
        require(inputs.map { it.name }.toSet().size == inputs.size) {
            "inputs must have unique names"
        }
        require(responseNames.all { it.isNotBlank() }) {
            "every entry in responseNames must be non-blank"
        }
        require(responseNames.toSet().size == responseNames.size) {
            "responseNames must contain no duplicates"
        }
        require(indifferenceZoneParameter >= 0.0 && indifferenceZoneParameter.isFinite()) {
            "indifferenceZoneParameter must be >= 0 and finite; was $indifferenceZoneParameter"
        }
        require(objectiveGranularity >= 0.0 && objectiveGranularity.isFinite()) {
            "objectiveGranularity must be >= 0 and finite; was $objectiveGranularity"
        }
    }
}
