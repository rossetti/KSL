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

package ksl.service.capability.run

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelProviderIfc
import kotlin.time.Duration

/**
 * A [ModelProviderIfc] decorator that stamps the server's job wall-clock deadline
 * onto every model it builds as the per-replication execution cap
 * (`maximumAllowedExecutionTimePerReplication`). It is the single,
 * builder-independent place the cap is applied (Phase 9 A5): because it sets the
 * value on the *built* model — after the model builder has run — it does not
 * depend on the builder honoring the `experimentRunParameters` argument, and it
 * applies uniformly to the run, experiment, and optimization paths (all of which
 * build their models through this provider).
 *
 * ## Why a per-replication cap is needed
 *
 * The deadline is a property of the *job* (an experiment = a set of
 * replications): a watchdog in [RunService] cancels the whole job when it
 * outlives the deadline, and the run loop / solver turns that into a
 * `RunResult.Cancelled` at its next replication (or iteration) boundary. The
 * catch is that those loops can only observe a cancellation *between*
 * replications, so a single replication that never returns would block them. The
 * per-replication cap (enforced by the KSL executive) is the backstop that
 * guarantees each replication hands control back within the budget, so the
 * job-level cancellation is actually honored — a coroutine cancel alone cannot
 * stop a CPU-bound or blocking model.
 *
 * In a normal multi-replication job the cap never fires: the job is cancelled
 * cleanly at a replication boundary and every replication that ran is whole. A
 * replication is only ever cut off when it alone exceeds the job budget, in which
 * case the job is over deadline regardless and is reported `Cancelled` (its
 * partial statistics discarded, never cached).
 *
 * ## Precedence
 *
 * The cap is stamped only onto models that declare none — an explicit author
 * value (set inside the builder) always wins. It is intentionally not a
 * result-determining input: a run that completes within the deadline produces an
 * identical result with or without it, so it is not part of any result cache key.
 *
 * Wrapping is only meaningful when a positive deadline is configured;
 * [RunService] applies this decorator only then.
 */
internal class DeadlineModelProvider(
    private val delegate: ModelProviderIfc,
    private val deadline: Duration,
) : ModelProviderIfc by delegate {

    init {
        require(deadline > Duration.ZERO) { "the per-replication deadline must be positive; got $deadline" }
    }

    override fun provideModel(
        modelIdentifier: String,
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?,
    ): Model {
        val model = delegate.provideModel(modelIdentifier, modelConfiguration, experimentRunParameters)
        if (model.maximumAllowedExecutionTimePerReplication <= Duration.ZERO) {
            model.maximumAllowedExecutionTimePerReplication = deadline
        }
        return model
    }
}
