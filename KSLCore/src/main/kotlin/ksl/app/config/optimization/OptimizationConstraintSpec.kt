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
 * App-layer mirror of [ksl.simopt.problem.InequalityType].
 *
 * Mirrored (rather than reused) so this configuration package is independent
 * of `ksl.simopt.problem`.  `OptimizationSolverFactory` translates between
 * this enum and the engine enum at solver-build time.
 */
@Serializable
enum class InequalityType { LESS_THAN, GREATER_THAN }

/**
 * Serializable counterpart to
 * [ksl.simopt.problem.ProblemDefinition.linearConstraint].
 *
 * A linear constraint over the decision variables: the dot product of the
 * named coefficients with the corresponding decision-variable values is
 * compared, via [inequalityType], to [rhsValue].  Each key in [coefficients]
 * must resolve to a declared [OptimizationInputSpec] in the enclosing
 * problem; missing input names are treated as having coefficient 0
 * (matching engine semantics).
 *
 * Domain invariants are enforced in `init` so that malformed constraints
 * cannot be constructed.  Cross-reference checks (coefficient keys must
 * match declared decision-variable names) require the enclosing problem
 * and remain the responsibility of `OptimizationConfigurationValidator`.
 *
 * @property coefficients linear coefficients keyed by decision-variable
 *           name; must be non-empty, every key non-blank, every value
 *           finite
 * @property rhsValue right-hand-side value; must be finite; defaults to 0.0
 * @property inequalityType direction of the inequality
 * @property penaltyFunction optional per-constraint penalty function
 *           overriding the problem-level default; `null` (the default)
 *           inherits [OptimizationProblemSpec.defaultLinearPenalty]
 */
@Serializable
data class LinearConstraintSpec(
    val coefficients: Map<String, Double>,
    val rhsValue: Double = 0.0,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    val penaltyFunction: PenaltyFunctionSpec? = null
) {
    init {
        require(coefficients.isNotEmpty()) { "coefficients must not be empty" }
        require(coefficients.keys.all { it.isNotBlank() }) {
            "every key in coefficients must be non-blank"
        }
        require(coefficients.values.all { it.isFinite() }) {
            "every value in coefficients must be finite"
        }
        require(rhsValue.isFinite()) { "rhsValue must be finite; was $rhsValue" }
    }
}

/**
 * Serializable counterpart to
 * [ksl.simopt.problem.ProblemDefinition.responseConstraint].
 *
 * A constraint on a response: the expected value of the named response is
 * compared, via [inequalityType], to [rhsValue], augmented with optional
 * [target] and [tolerance] parameters used by some solvers as soft cut-offs.
 *
 * Domain invariants are enforced in `init`.  Cross-reference checks
 * (response-name resolution against the built model) remain the
 * responsibility of `OptimizationConfigurationValidator`.
 *
 * @property name name of the response being constrained; must be non-blank
 *           and must appear in [OptimizationProblemSpec.responseNames] and
 *           on the built model
 * @property rhsValue right-hand-side value; must be finite
 * @property inequalityType direction of the inequality; defaults to
 *           [InequalityType.LESS_THAN]
 * @property target solver-specific cut-off parameter; must be finite;
 *           defaults to 0.0
 * @property tolerance solver-specific tolerance around [target]; must be
 *           `>= 0` and finite; defaults to 0.0
 * @property penaltyFunction optional per-constraint penalty function
 *           overriding the problem-level default; `null` (the default)
 *           inherits [OptimizationProblemSpec.defaultResponsePenalty]
 */
@Serializable
data class ResponseConstraintSpec(
    val name: String,
    val rhsValue: Double,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    val target: Double = 0.0,
    val tolerance: Double = 0.0,
    val penaltyFunction: PenaltyFunctionSpec? = null
) {
    init {
        require(name.isNotBlank()) { "name must be non-blank" }
        require(rhsValue.isFinite()) { "rhsValue must be finite; was $rhsValue" }
        require(target.isFinite()) { "target must be finite; was $target" }
        require(tolerance >= 0.0 && tolerance.isFinite()) {
            "tolerance must be >= 0 and finite; was $tolerance"
        }
    }
}
