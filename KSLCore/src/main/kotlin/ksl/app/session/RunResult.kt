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

import ksl.simopt.solvers.SolverStateSnapshot
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
 *     is RunResult.Completed            -> showResults(r.snapshot)
 *     is RunResult.BatchCompleted       -> showBatchResults(r.snapshots)
 *     is RunResult.OptimizationCompleted -> showOptResults(r.bestSolution, r.iterationHistory)
 *     is RunResult.Cancelled            -> showCancelled(r.reason)
 *     is RunResult.Failed               -> showError(r.error)
 * }
 * ```
 *
 * The last event on [RunHandle.events] mirrors this outcome:
 * [RunEvent.RunCompleted], [RunEvent.RunCancelled], or [RunEvent.RunFailed].
 */
sealed class RunResult {

    /**
     * A single-model run finished normally (all requested replications ran, or
     * the model ended itself via `endSimulation()`).
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
     * A scenario sweep or designed experiment finished. Every successfully completed
     * scenario or design point contributes one [SimulationSnapshot.ExperimentCompleted]
     * to [snapshots]; the list is non-empty on a clean run.
     *
     * @property summary orchestrator-level metadata (total items, completed, failed)
     * @property snapshots one snapshot per successfully completed scenario or design point,
     *           in commit order. Non-empty when [OrchestratorSummary.failedItems] is zero.
     */
    data class BatchCompleted(
        val summary: OrchestratorSummary,
        val snapshots: List<SimulationSnapshot.ExperimentCompleted>,
        /**
         *  Per-item replication-level snapshots in completion order,
         *  keyed by the item name (matching
         *  `SimulationRunTableData.run_name` on the corresponding
         *  entry in [snapshots]).  Each value is the list of
         *  [SimulationSnapshot.ReplicationCompleted] snapshots
         *  collected during that item's run, in replication order.
         *
         *  Defaults to an empty map.  `ScenarioOrchestrator` populates
         *  it (so multi-scenario consumers — the Scenario app's
         *  reporting layer in particular — can reconstruct
         *  per-replication observations for box plots, multiple-
         *  comparison analyses, and replication traces from
         *  [ksl.utilities.io.dbutil.WithinRepStatTableData]).
         *  `ExperimentOrchestrator` leaves it empty for now; the
         *  field shape is forward-compatible if that orchestrator
         *  later surfaces analogous data.
         */
        val replicationsByItem: Map<String, List<SimulationSnapshot.ReplicationCompleted>> = emptyMap()
    ) : RunResult()

    /**
     * A simulation-optimization run finished. Carries the solver's best solution and
     * the full per-iteration history for convergence analysis.
     *
     * [iterationHistory] has one entry per solver iteration (in execution order).
     * [bestSolution] is the last snapshot, which carries the globally best solution
     * found across all iterations via [SolverStateSnapshot.bestSolutionSoFar].
     *
     * @property summary orchestrator-level metadata (total and completed iterations,
     *           always `failedItems == 0` on a clean run)
     * @property bestSolution the [SolverStateSnapshot] from the final iteration,
     *           whose [SolverStateSnapshot.bestSolutionSoFar] is the optimal solution found
     * @property iterationHistory all [SolverStateSnapshot]s in execution order; use for
     *           convergence plots and iteration-by-iteration inspection
     */
    data class OptimizationCompleted(
        val summary: OrchestratorSummary,
        val bestSolution: SolverStateSnapshot,
        val iterationHistory: List<SolverStateSnapshot>
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

/**
 *  Return a copy of [this] [RunResult.BatchCompleted] with the snapshot for
 *  [scenarioName] removed, alongside the matching entry in
 *  [RunResult.BatchCompleted.replicationsByItem].  Returns `null` when the
 *  filter empties [RunResult.BatchCompleted.snapshots] — callers treat that
 *  as "the result is now empty; drop it".
 *
 *  Matching is by `experiment.exp_name`, which is the same key
 *  `ScenarioOrchestrator` uses when populating `replicationsByItem`.
 *  Returns the receiver unchanged when no snapshot carries [scenarioName].
 *
 *  [RunResult.BatchCompleted.summary] is intentionally left untouched —
 *  it's an audit record of the batch that actually ran (item counts,
 *  begin/end times, etc.) and should not be rewritten to reflect a
 *  post-hoc deletion of one of its outputs.
 *
 *  Supports the Scenario app's identity-coupled lifecycle: when the user
 *  deletes a scenario from the editable list, the controller calls this
 *  to drop the corresponding completed snapshot from the in-memory
 *  result.  Lives as an extension rather than a member so the data class
 *  itself doesn't grow per-host helpers; the helper is pure and has no
 *  Scenario-app coupling.
 */
fun RunResult.BatchCompleted.withoutScenario(scenarioName: String): RunResult.BatchCompleted? {
    val filtered = snapshots.filter { it.experiment.exp_name != scenarioName }
    if (filtered.size == snapshots.size) return this  // name not present — no-op
    if (filtered.isEmpty()) return null
    val filteredReps = replicationsByItem - scenarioName
    return copy(snapshots = filtered, replicationsByItem = filteredReps)
}
