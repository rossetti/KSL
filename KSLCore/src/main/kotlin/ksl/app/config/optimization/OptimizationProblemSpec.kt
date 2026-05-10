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

/**
 * App-layer mirror of [ksl.simopt.problem.OptimizationType].
 *
 * Mirrored (rather than reused) so that this configuration package is
 * independent of `ksl.simopt.problem` and so that Step 3 can remain purely
 * additive in `ksl.app.config.optimization`.  The optimization solver factory
 * (Step 6) will translate between this enum and the engine enum.
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
 * `OptimizationConfigurationValidator` in Step 4.
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
    val problemName: String? = null,
    val modelIdentifier: String? = null,
    val objectiveResponseName: String,
    val inputs: List<OptimizationInputSpec>,
    val responseNames: List<String> = emptyList(),
    val optimizationType: OptimizationType = OptimizationType.MINIMIZE,
    val indifferenceZoneParameter: Double = 0.0,
    val objectiveGranularity: Double = 0.0,
    val linearConstraints: List<LinearConstraintSpec> = emptyList(),
    val responseConstraints: List<ResponseConstraintSpec> = emptyList(),
    val defaultLinearPenalty: PenaltyFunctionSpec = PenaltyFunctionSpec.DynamicPolynomial(),
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
