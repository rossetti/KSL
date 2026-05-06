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
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.simopt.solvers.Solver

/**
 * Public run request shape consumed by [KSLAppSession].
 *
 * [RunConfiguration] remains the serializable description of model source,
 * run parameters, controls, random-variable overrides, and optional scenario
 * metadata.  [RunSpec] selects how that configuration should be executed.
 */
sealed class RunSpec {

    /** Baseline configuration document for this run. */
    abstract val config: RunConfiguration

    /**
     * Run one configured model.
     *
     * Attachments supplied to [KSLAppSession.submit] are currently supported
     * only for this spec.
     */
    data class Single(
        override val config: RunConfiguration
    ) : RunSpec()

    /**
     * Run the scenario sweep encoded in [RunConfiguration.scenarios].
     */
    data class Scenarios(
        override val config: RunConfiguration
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
     * [config] is retained as the baseline validation/configuration document;
     * [experiment] carries the non-serializable experiment structure.
     */
    data class Experiment(
        override val config: RunConfiguration,
        val experiment: ParallelDesignedExperiment,
        val numRepsPerDesignPoint: Int? = null
    ) : RunSpec() {
        init {
            require(numRepsPerDesignPoint == null || numRepsPerDesignPoint > 0) {
                "numRepsPerDesignPoint must be null or greater than zero."
            }
        }
    }

    /**
     * Run a programmatically constructed simulation-optimization problem.
     *
     * [config] is retained as the baseline validation/configuration document;
     * [solver] carries the non-serializable optimization state.
     */
    data class Optimization(
        override val config: RunConfiguration,
        val solver: Solver
    ) : RunSpec()
}
