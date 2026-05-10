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
import ksl.app.config.ModelRunTemplate

/**
 * Top-level persistable directive for a simulation-optimization run.
 *
 * `OptimizationRunConfiguration` is the optimization counterpart to
 * [ksl.app.config.RunConfiguration].  It deliberately uses [ModelRunTemplate]
 * for the model-construction portion so that fixed/baseline model controls and
 * RV overrides are kept separate from the optimizer-controlled decision
 * variables declared in `problem`.
 *
 * This document is the JSON/TOML round-trip target for app/UI workflows.  It
 * carries no live `Solver` or `ProblemDefinition` objects — those are built
 * later by an `OptimizationSolverFactory` (Step 6).  This step (Phase 5.85
 * Step 3) provides the persistable shape only.
 *
 * @property model baseline model-construction template
 * @property problem optimization problem (objective, decision variables,
 *                   constraints)
 * @property solver solver selection and algorithm-specific parameters
 * @property evaluation cross-cutting evaluator/solver settings independent of
 *                      the chosen algorithm
 */
@Serializable
data class OptimizationRunConfiguration(
    val model: ModelRunTemplate,
    val problem: OptimizationProblemSpec,
    val solver: SolverSpec,
    val evaluation: EvaluationSpec = EvaluationSpec()
)
