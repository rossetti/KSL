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

import ksl.utilities.io.dbutil.SimulationSnapshot

/**
 * Terminal outcome of a simulation run, returned by `RunHandle.result.await()`.
 *
 * All variants are *normal* completions of the underlying [kotlinx.coroutines.Deferred]
 * — `await()` never throws.  Consumers use a `when` expression to branch on the
 * outcome rather than try/catch:
 *
 * ```kotlin
 * when (val r = handle.result.await()) {
 *     is RunResult.Completed           -> showResults(r.snapshot)
 *     is RunResult.OrchestratorCompleted -> showOrchestratorResults(r.snapshots)
 *     is RunResult.Cancelled           -> showCancelled(r.reason)
 *     is RunResult.Failed              -> showError(r.error)
 * }
 * ```
 *
 * The last event on [RunHandle.events] mirrors this outcome:
 * [RunEvent.RunCompleted], [RunEvent.RunCancelled], or [RunEvent.RunFailed].
 */
sealed class RunResult {

    /**
     * The run finished normally (all requested replications ran, or the model
     * ended itself via `endSimulation()`).
     *
     * @property summary lightweight post-run metadata
     * @property snapshot comprehensive across-replication statistics captured by
     *           `InMemorySnapshotCollector` after `endSimulation()`. Covers all
     *           [ksl.modeling.variable.Response], [ksl.modeling.variable.Counter],
     *           histogram, frequency, and time-series elements. For per-replication
     *           observation arrays, attach a [ReplicationDataAttachment] before submitting.
     */
    data class Completed(
        val summary: RunSummary,
        val snapshot: SimulationSnapshot.ExperimentCompleted
    ) : RunResult()

    /**
     * An orchestrator run (scenario sweep, designed experiment, or optimization)
     * finished. Carries the aggregate summary and per-item snapshots.
     *
     * @property summary orchestrator-level metadata (total items, completed, failed)
     * @property snapshots one [SimulationSnapshot.ExperimentCompleted] per successfully
     *           completed scenario or design point; empty for optimization runs
     */
    data class OrchestratorCompleted(
        val summary: OrchestratorSummary,
        val snapshots: List<SimulationSnapshot.ExperimentCompleted>
    ) : RunResult()

    /**
     * The run was terminated by an unexpected exception during replication
     * execution.
     *
     * @property error typed description of the failure, suitable for display
     *           in a GUI error dialog or log entry
     */
    data class Failed(val error: KSLRuntimeError) : RunResult()

    /**
     * The run was stopped by an explicit call to [RunHandle.cancel].
     *
     * @property reason the message passed to [RunHandle.cancel], or a default
     *           if none was provided
     */
    data class Cancelled(val reason: String) : RunResult()
}
