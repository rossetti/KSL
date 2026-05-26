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
import net.peanuuutz.tomlkt.TomlComment

/**
 * Top-level persistable directive for a simulation-optimization run.
 *
 * `OptimizationRunConfiguration` is the optimization counterpart to
 * [ksl.app.config.RunConfiguration].  It deliberately uses [ModelRunTemplate]
 * for the model-construction portion so that fixed/baseline model controls and
 * RV overrides are kept separate from the optimizer-controlled decision
 * variables declared in [problem].
 *
 * This document is the JSON/TOML round-trip target for app/UI workflows.  It
 * carries no live `Solver` or `ProblemDefinition` objects — those are built
 * by `OptimizationSolverFactory` from the spec when the run is submitted.
 *
 * ## Typical workflow
 *
 * ```kotlin
 * // Load from TOML file:
 * val config  = OptimizationRunConfigurationToml.decode(File("opt.toml").readText())
 *
 * // Submit through the application-facing session:
 * val provider: ModelProviderIfc = MapModelProvider("Inventory", InventoryBuilder)
 * val session  = KSLAppSession(provider)
 * val handle   = session.submit(RunSpec.Optimization(config))
 * val result   = handle.result.await()
 * ```
 *
 * The session validates the configuration via
 * [ksl.app.validation.OptimizationConfigurationValidator], builds a
 * [ksl.simopt.solvers.Solver] via `OptimizationSolverFactory`, and dispatches
 * to [ksl.app.orchestrator.OptimizationOrchestrator].  Programmatic users who
 * already hold a built solver can call
 * `OptimizationOrchestrator().submit(solver, …)` directly and bypass the
 * session entirely.
 *
 * @property output document-wide output settings (analysis name and the
 *                  host-resolved output directory).  Defaults to
 *                  [OptimizationOutputConfig] defaults — `analysisName =
 *                  "Untitled"` and `outputDirectory = null`.
 * @property model baseline model-construction template
 * @property problem optimization problem (objective, decision variables,
 *                   constraints)
 * @property solver solver selection and algorithm-specific parameters
 * @property evaluation cross-cutting evaluator/solver settings independent of
 *                      the chosen algorithm
 * @property tracking optional CSV / console trace settings; defaults to
 *                   disabled
 */
@Serializable
data class OptimizationRunConfiguration(
    @TomlComment(
        "Document-wide output settings.  analysisName names the per-run\n" +
        "subdirectory under <workspace>/output/; outputDirectory is set\n" +
        "by the hosting app at submit time and should NOT be edited by\n" +
        "hand.  See [output] section below."
    )
    val output: OptimizationOutputConfig = OptimizationOutputConfig(),

    @TomlComment(
        "Baseline model-construction template.  modelReference picks the\n" +
        "model; runParameters carry length / warm-up / replication-count\n" +
        "settings; controls and rvOverrides are values held CONSTANT\n" +
        "during the optimization (decision variables go in [[problem.inputs]]\n" +
        "below)."
    )
    val model: ModelRunTemplate,

    @TomlComment(
        "Optimization problem definition.  Objective, decision variables,\n" +
        "constraints, and penalty function defaults.  See [problem]\n" +
        "section below."
    )
    val problem: OptimizationProblemSpec,

    @TomlComment(
        "Solver algorithm and its parameters.  type discriminates between\n" +
        "'stochasticHillClimbing', 'simulatedAnnealing', 'crossEntropy',\n" +
        "and 'rSpline'.  Set [solver.randomRestart] to wrap the chosen\n" +
        "algorithm in random-restart."
    )
    val solver: SolverSpec,

    @TomlComment(
        "Cross-cutting evaluator/solver settings independent of the\n" +
        "chosen algorithm.  Solution / simulation-run cache toggles,\n" +
        "iteration snapshot frequency, problem-feasibility settings."
    )
    val evaluation: EvaluationSpec = EvaluationSpec(),

    @TomlComment(
        "Optional CSV / console trace settings.  When enableCsvTrace =\n" +
        "true the trace is written to\n" +
        "<workspace>/output/<analysisName>/optimization/<csvFileName>.csv.\n" +
        "Defaults to disabled."
    )
    val tracking: SolverTrackingSpec = SolverTrackingSpec()
)
