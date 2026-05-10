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
 * definition during run preparation.  Validation of these values against the
 * model and against each other is the responsibility of
 * `OptimizationConfigurationValidator` in Step 4; this type intentionally
 * performs no `init`-block validation so malformed documents can be decoded
 * and reported rather than throwing during deserialization.
 *
 * @property problemName optional human-readable name
 * @property modelIdentifier optional model identifier; if null the validator
 *           may resolve it from the built model
 * @property objectiveResponseName name of the model response that is
 *           optimized; must match a response on the built model
 * @property inputs decision variables controlled by the solver
 * @property responseNames additional response names that may be referenced by
 *           response constraints; the objective response is implied and need
 *           not be repeated here
 * @property optimizationType minimize or maximize
 * @property indifferenceZoneParameter smallest objective-function difference
 *           considered practically meaningful; defaults to 0.0
 * @property objectiveGranularity granularity applied to the objective
 *           function value; 0.0 means full precision
 * @property linearConstraints optional linear constraints over decision
 *           variables
 * @property responseConstraints optional response constraints
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
    val responseConstraints: List<ResponseConstraintSpec> = emptyList()
)
