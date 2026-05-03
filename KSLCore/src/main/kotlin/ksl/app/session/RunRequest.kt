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

package ksl.app.session

import ksl.simulation.Model

/**
 * Describes a simulation run to be submitted to [Runner].
 *
 * The sealed hierarchy is intentionally minimal in Phase 1: only [SingleRun]
 * is implemented.  Variants for scenario sweeps, designed experiments, and
 * simulation optimisation are added in Phase 5 once the underlying
 * orchestrators are in place.
 */
sealed class RunRequest {

    /**
     * Optional list of [RunAttachmentIfc] instances that [Runner] will attach
     * to the model before the experiment begins and detach after it ends,
     * regardless of outcome.
     *
     * Defaults to an empty list — zero overhead when no attachments are needed.
     */
    abstract val attachments: List<RunAttachmentIfc>

    /**
     * A request to execute a single pre-built [Model].
     *
     * The caller is responsible for configuring the model (number of
     * replications, replication length, controls, etc.) before passing it to
     * [Runner.submit].  [Runner] does not retain the model after the run
     * completes; reconfiguring and re-running typically requires rebuilding
     * the model, as `ConcurrentScenarioRunner` already does.
     *
     * Phase 2 will add `RunConfiguration`-based construction so that a model
     * can be fully specified as a serialisable input bundle.  At that point
     * `SingleRun` may optionally accept a `RunConfiguration` as an alternative
     * to a live [Model] instance.
     *
     * @property model the simulation model to execute; must be fully configured
     *           before being passed here
     * @property attachments optional observers that receive lifecycle callbacks
     *           and guaranteed cleanup; see [RunAttachmentIfc]
     */
    data class SingleRun(
        val model: Model,
        override val attachments: List<RunAttachmentIfc> = emptyList()
    ) : RunRequest()
}
