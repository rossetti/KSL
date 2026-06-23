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

package ksl.app

import ksl.app.config.RunConfiguration
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.controls.experiments.DesignedExperimentIfc

/**
 * Public run request shape consumed by [KSLAppSession].
 *
 * Each [RunSpec] variant carries the configuration shape natural to its
 * execution mode: [RunConfiguration] for single-model, scenario, and
 * designed-experiment runs; [OptimizationRunConfiguration] for
 * simulation-optimization runs.
 *
 * `KSLAppSession.submit` dispatches by spec variant to the matching
 * validator and execution path (see [KSLAppSession] for the routing
 * details).
 */
sealed class RunSpec {

    /**
     * Run one configured model.
     *
     * Attachments supplied to [KSLAppSession.submit] are currently supported
     * only for this spec.
     */
    data class Single(
        val config: RunConfiguration
    ) : RunSpec()

    /**
     * Run the scenario sweep encoded in [RunConfiguration.scenarios].
     */
    data class Scenarios(
        val config: RunConfiguration
    ) : RunSpec() {
        init {
            require(config.scenarios.isNotEmpty()) {
                "RunSpec.Scenarios requires config.scenarios to be non-empty."
            }
        }
    }

    /**
     * Run a programmatically constructed designed experiment.
     *
     * [config] is retained as the baseline validation/configuration
     * document; [experiment] carries the non-serializable experiment
     * structure as a [DesignedExperimentIfc].  Either a
     * [ksl.controls.experiments.ParallelDesignedExperiment]
     * (concurrent) or a
     * [ksl.controls.experiments.DesignedExperiment] (sequential) may
     * be supplied; the orchestrator branches on the concrete type to
     * pick the right `simulateAll` entry point.
     */
    data class Experiment(
        val config: RunConfiguration,
        val experiment: DesignedExperimentIfc,
        val numRepsPerDesignPoint: Int? = null
    ) : RunSpec() {
        init {
            require(numRepsPerDesignPoint == null || numRepsPerDesignPoint > 0) {
                "numRepsPerDesignPoint must be null or greater than zero."
            }
        }
    }

    /**
     * Run a simulation-optimization problem described by an
     * [OptimizationRunConfiguration].
     *
     * [KSLAppSession.submit] validates the configuration via
     * [ksl.app.validation.OptimizationConfigurationValidator], builds a
     * [ksl.simopt.solvers.Solver] via
     * [ksl.app.config.optimization.OptimizationSolverFactory], and then
     * delegates to the existing
     * [ksl.app.orchestrator.OptimizationOrchestrator] for asynchronous
     * execution.
     *
     * Programmatic users who already hold a built `Solver` should use the
     * orchestrator directly: `OptimizationOrchestrator().submit(solver, ...)`.
     */
    data class Optimization(
        val config: OptimizationRunConfiguration
    ) : RunSpec()
}
