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
 * Serializable counterpart to
 * [ksl.simopt.problem.ProblemDefinition.inputVariable].
 *
 * Declares one decision variable controlled by the optimizer.  The optimizer
 * is permitted to vary [name] inclusively between [lowerBound] and
 * [upperBound], respecting [granularity] (a granularity of 1.0 implies an
 * integer-ordered input).
 *
 * Bound finiteness, `lowerBound < upperBound`, and `granularity >= 0` are
 * enforced in `init` on construction.  Name resolution against the built
 * model is performed by
 * [ksl.app.validation.OptimizationConfigurationValidator].
 *
 * @property name decision-variable name; must match a model input key
 * @property lowerBound lower bound (inclusive); must be finite and strictly
 *           less than [upperBound]
 * @property upperBound upper bound (inclusive); must be finite and strictly
 *           greater than [lowerBound]
 * @property granularity step granularity; 0.0 means full precision, 1.0
 *           implies integer-ordered, in general the value will be rounded to
 *           the nearest multiple of `granularity`
 */
@Serializable
data class OptimizationInputSpec(
    val name: String,
    val lowerBound: Double,
    val upperBound: Double,
    val granularity: Double = 0.0
) {
    init {
        require(name.isNotBlank()) { "name must be non-blank" }
        require(lowerBound.isFinite()) { "lowerBound must be finite; was $lowerBound" }
        require(upperBound.isFinite()) { "upperBound must be finite; was $upperBound" }
        require(lowerBound < upperBound) {
            "lowerBound ($lowerBound) must be strictly less than upperBound ($upperBound)"
        }
        require(granularity >= 0.0 && granularity.isFinite()) {
            "granularity must be >= 0 and finite; was $granularity"
        }
    }
}
