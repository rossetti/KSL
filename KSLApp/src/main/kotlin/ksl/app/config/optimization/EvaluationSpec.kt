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
 * Cross-cutting evaluator/solver settings that are not specific to one
 * algorithm.
 *
 * `OptimizationSolverFactory` applies these values after solver
 * construction:
 *
 * - cache settings ([useSolutionCache], [useSimulationRunCache]) are wired
 *   into the evaluator;
 * - [snapshotFrequency], [ensureProblemFeasibleRequests],
 *   [maxFeasibleSamplingIterations], and [solutionPrecision] are set
 *   directly on the [ksl.simopt.solvers.Solver] base class;
 * - [parallelEvaluation] and [numEvaluationWorkers] select and size the
 *   evaluator's parallel oracle.
 *
 * Domain invariants are enforced in `init`.
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
 * @property parallelEvaluation when `true`, evaluate the points of a multi-point
 *           request concurrently; defaults to `false` (sequential)
 * @property numEvaluationWorkers maximum concurrent model evaluations when
 *           [parallelEvaluation] is `true`; `null` uses the available processors
 */
@Serializable
data class EvaluationSpec(
    @TomlComment(
        "Boolean. When true, the evaluator caches solutions keyed by\n" +
        "the input map so repeated requests for the same point reuse the\n" +
        "prior estimate.  Default: true."
    )
    val useSolutionCache: Boolean = true,

    @TomlComment(
        "Boolean. When true, the evaluator additionally caches the\n" +
        "underlying simulation runs (per-replication data) so distinct\n" +
        "solution requests can share replication results.  Default: false."
    )
    val useSimulationRunCache: Boolean = false,

    @TomlComment(
        "Integer. Number of iterations between solver state snapshots.\n" +
        "1 = emit a snapshot every iteration (the default; matches the\n" +
        "live-progress and tracker workflows).  Must be > 0."
    )
    val snapshotFrequency: Int = 1,

    @TomlComment(
        "Boolean. When true, the solver only sends problem-feasible\n" +
        "inputs to the evaluator (re-sampling locally until feasibility\n" +
        "is achieved).  Default: false."
    )
    val ensureProblemFeasibleRequests: Boolean = false,

    @TomlComment(
        "Integer or omitted. Cap on the inner-loop sampling iterations\n" +
        "used when searching for an input-feasible point.  Omit to keep\n" +
        "the solver default.  Must be > 0 when present."
    )
    val maxFeasibleSamplingIterations: Int? = null,

    @TomlComment(
        "Number or omitted. Precision used by some convergence tests.\n" +
        "Omit to keep the solver default.  Must be > 0 and finite when\n" +
        "present."
    )
    val solutionPrecision: Double? = null,

    @TomlComment(
        "Boolean. When true, the evaluator runs the points of a multi-point\n" +
        "evaluation request concurrently, each on its own freshly built model.\n" +
        "Single-point requests still run on one reused model.  Default: false."
    )
    val parallelEvaluation: Boolean = false,

    @TomlComment(
        "Integer or omitted. Maximum number of concurrent model evaluations\n" +
        "when parallelEvaluation is true.  Omit to use the number of available\n" +
        "processors.  Must be > 0 when present."
    )
    val numEvaluationWorkers: Int? = null
) {
    init {
        require(snapshotFrequency > 0) {
            "snapshotFrequency must be > 0; was $snapshotFrequency"
        }
        require(maxFeasibleSamplingIterations == null || maxFeasibleSamplingIterations > 0) {
            "maxFeasibleSamplingIterations must be > 0 when non-null; was $maxFeasibleSamplingIterations"
        }
        require(solutionPrecision == null || (solutionPrecision > 0.0 && solutionPrecision.isFinite())) {
            "solutionPrecision must be > 0 and finite when non-null; was $solutionPrecision"
        }
        require(numEvaluationWorkers == null || numEvaluationWorkers > 0) {
            "numEvaluationWorkers must be > 0 when non-null; was $numEvaluationWorkers"
        }
    }
}
