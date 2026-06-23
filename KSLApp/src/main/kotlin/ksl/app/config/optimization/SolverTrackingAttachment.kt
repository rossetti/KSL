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

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.trackers.AbstractNestedSolverStateTracker
import ksl.simopt.solvers.trackers.AbstractSolverStateTracker
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.CsvSolverStateTracker
import ksl.simopt.solvers.trackers.NestedCsvSolverStateTracker
import java.nio.file.Path

/**
 * Handle to every tracker attached to a single solver run.
 *
 * Returned by `SolverTrackingSpec.attachTo`.  The caller retains the
 * handle for the lifetime of the run and invokes [stopAll] from a
 * terminal-event handler (`RunCompleted` / `RunFailed` /
 * `RunCancelled`) so the trackers release their file locks and detach
 * their emitter connections.
 *
 * Two internal lists are kept because the substrate's tracker base
 * classes — `ksl.simopt.solvers.trackers.AbstractSolverStateTracker`
 * and `ksl.simopt.solvers.trackers.AbstractNestedSolverStateTracker`
 * — do not share a common ancestor.  Both classes expose an
 * independent `stopTracking()` method which [stopAll] invokes
 * uniformly.
 *
 * @property size total number of attached trackers; useful for tests
 *           and diagnostics
 */
class SolverTrackerHandles internal constructor(
    private val singleTrackers: List<AbstractSolverStateTracker>,
    private val nestedTrackers: List<AbstractNestedSolverStateTracker>
) {
    /**
     *  Detach every attached tracker.  Idempotent — calling [stopAll]
     *  on a tracker that has already been stopped is a safe no-op
     *  (handled by the substrate's own `stopTracking()` implementation).
     */
    fun stopAll() {
        singleTrackers.forEach { it.stopTracking() }
        nestedTrackers.forEach { it.stopTracking() }
    }

    /** Total count of attached trackers. */
    val size: Int get() = singleTrackers.size + nestedTrackers.size
}

/**
 * Attach every tracker requested by this [SolverTrackingSpec] to
 * [solver].
 *
 * Dispatch summary:
 *
 * - When [SolverTrackingSpec.enableCsvTrace] is `true`, one CSV
 *   tracker is attached:
 *   - a [CsvSolverStateTracker] when [solver] is a plain
 *     [ksl.simopt.solvers.Solver];
 *   - a [NestedCsvSolverStateTracker] when [solver] is a
 *     [RandomRestartSolver] — its `restartingSolver` becomes the
 *     micro solver so the CSV interleaves MACRO + MICRO rows.
 *   The single-vs-nested choice is made at attachment time and is
 *   **not** persisted in the spec.
 * - When [SolverTrackingSpec.enableConsoleTrace] is `true`, a
 *   [ConsoleSolverStateTracker] is additionally attached.  The
 *   substrate has no nested-console variant, so the console tracker
 *   listens to the outer (macro) solver in the restart case — its
 *   per-iteration output is still useful as a heartbeat, just less
 *   granular than the CSV.
 *
 * Every attached tracker has its `experimentName` set to
 * [SolverTrackingSpec.experimentLabel] before `startTracking()` is
 * called, so the value is stamped on every emitted row in the
 * tracker's `ExperimentName` column.
 *
 * The CSV file name is `<stem>.csv` under [optimizationDir], where
 * `stem` is [SolverTrackingSpec.csvFileName] when non-null and the
 * result of [defaultFileName] otherwise.  Typical callers pass
 * `{ "${solver.name}_trace" }` for the fallback.
 *
 * [optimizationDir] must exist when this function is called; the
 * caller is responsible for `Files.createDirectories(...)` upstream.
 *
 * @param solver the live solver instance to attach trackers to
 * @param optimizationDir directory under which the CSV trace file
 *        is written (must exist)
 * @param defaultFileName invoked only when
 *        [SolverTrackingSpec.csvFileName] is `null`; should return a
 *        bare file stem (no extension, no path)
 * @return a [SolverTrackerHandles] holding references to every
 *         attached tracker so the caller can detach them via
 *         [SolverTrackerHandles.stopAll] at the end of the run
 */
fun SolverTrackingSpec.attachTo(
    solver: Solver,
    optimizationDir: Path,
    defaultFileName: () -> String
): SolverTrackerHandles {
    val single = mutableListOf<AbstractSolverStateTracker>()
    val nested = mutableListOf<AbstractNestedSolverStateTracker>()

    if (enableCsvTrace) {
        val stem = csvFileName ?: defaultFileName()
        val file = optimizationDir.resolve("$stem.csv").toFile()
        if (solver is RandomRestartSolver) {
            val tracker = NestedCsvSolverStateTracker(
                macroSolver = solver,
                microSolver = solver.restartingSolver,
                outputFile = file
            )
            tracker.experimentName = experimentLabel
            tracker.startTracking()
            nested += tracker
        } else {
            val tracker = CsvSolverStateTracker(
                solver = solver,
                outputFile = file
            )
            tracker.experimentName = experimentLabel
            tracker.startTracking()
            single += tracker
        }
    }

    if (enableConsoleTrace) {
        val tracker = ConsoleSolverStateTracker(solver)
        tracker.experimentName = experimentLabel
        tracker.startTracking()
        single += tracker
    }

    return SolverTrackerHandles(single, nested)
}
