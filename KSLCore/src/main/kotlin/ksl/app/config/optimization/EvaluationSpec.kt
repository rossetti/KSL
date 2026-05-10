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
 * Cross-cutting evaluator/solver settings that are not specific to one
 * algorithm.
 *
 * The optimization solver factory (Step 6) applies these values after
 * solver construction:
 *
 * - cache settings ([useSolutionCache], [useSimulationRunCache]) are wired
 *   into the evaluator;
 * - [snapshotFrequency], [ensureProblemFeasibleRequests],
 *   [maxFeasibleSamplingIterations], and [solutionPrecision] are set
 *   directly on the [ksl.simopt.solvers.Solver] base class.
 *
 * Validation of these values (positivity of `snapshotFrequency`, etc.) is
 * performed in Step 4 by `OptimizationConfigurationValidator`; this type
 * does not validate in `init` so malformed documents can be decoded and
 * reported.
 *
 * Nullable fields use `null` to mean "leave the solver default in place"
 * so persisted documents do not bake in the current default values.
 *
 * @property useSolutionCache whether the evaluator should cache solutions
 *           by input map; defaults to `true`
 * @property useSimulationRunCache whether the evaluator should cache the
 *           underlying simulation runs; defaults to `false`
 * @property snapshotFrequency frequency, in iterations, at which the solver
 *           emits iteration snapshots; defaults to 1 (every iteration)
 * @property ensureProblemFeasibleRequests when `true` the solver only
 *           sends problem-feasible inputs to the evaluator
 * @property maxFeasibleSamplingIterations cap on the inner-loop sampling
 *           iterations used when searching for an input-feasible point;
 *           `null` keeps the solver default
 * @property solutionPrecision optional precision used by some convergence
 *           tests; `null` keeps the solver default
 */
@Serializable
data class EvaluationSpec(
    val useSolutionCache: Boolean = true,
    val useSimulationRunCache: Boolean = false,
    val snapshotFrequency: Int = 1,
    val ensureProblemFeasibleRequests: Boolean = false,
    val maxFeasibleSamplingIterations: Int? = null,
    val solutionPrecision: Double? = null
)
