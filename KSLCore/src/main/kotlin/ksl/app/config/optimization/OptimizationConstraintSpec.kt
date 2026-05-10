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
 * of `ksl.simopt.problem`.  The optimization solver factory (Step 6) will
 * translate between this enum and the engine enum.
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
 * Validation (input-name resolution, finite coefficients, finite RHS) is the
 * responsibility of `OptimizationConfigurationValidator` in Step 4.
 *
 * @property coefficients linear coefficients keyed by decision-variable name
 * @property rhsValue right-hand-side value; defaults to 0.0
 * @property inequalityType direction of the inequality
 */
@Serializable
data class LinearConstraintSpec(
    val coefficients: Map<String, Double>,
    val rhsValue: Double = 0.0,
    val inequalityType: InequalityType = InequalityType.LESS_THAN
)

/**
 * Serializable counterpart to
 * [ksl.simopt.problem.ProblemDefinition.responseConstraint].
 *
 * A constraint on a response: the expected value of the named response is
 * compared, via [inequalityType], to [rhsValue], augmented with optional
 * [target] and [tolerance] parameters used by some solvers as soft cut-offs.
 *
 * Validation (response-name resolution against the built model, finite RHS,
 * `tolerance >= 0`) is the responsibility of
 * `OptimizationConfigurationValidator` in Step 4.
 *
 * @property name name of the response being constrained; must appear in
 *           [OptimizationProblemSpec.responseNames] and on the built model
 * @property rhsValue right-hand-side value
 * @property inequalityType direction of the inequality; defaults to
 *           [InequalityType.LESS_THAN]
 * @property target solver-specific cut-off parameter; defaults to 0.0
 * @property tolerance solver-specific tolerance around [target]; defaults to
 *           0.0; must be non-negative when validated
 */
@Serializable
data class ResponseConstraintSpec(
    val name: String,
    val rhsValue: Double,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    val target: Double = 0.0,
    val tolerance: Double = 0.0
)
