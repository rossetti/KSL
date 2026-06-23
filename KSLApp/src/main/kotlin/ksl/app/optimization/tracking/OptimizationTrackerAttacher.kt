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

package ksl.app.optimization.tracking

import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.optimization.paths.OptimizationPaths
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.CsvSolverStateTracker
import ksl.simopt.solvers.trackers.NestedConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.NestedCsvSolverStateTracker
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Attach CSV / console trackers to a [Solver] per the host's
 *  [SolverTrackingSpec].
 *
 *  Distinguishes plain solvers from [RandomRestartSolver] wrappers
 *  and instantiates the right tracker variant for each
 *  (`CsvSolverStateTracker` vs. `NestedCsvSolverStateTracker`,
 *  `ConsoleSolverStateTracker` vs. `NestedConsoleSolverStateTracker`).
 *  Creates the trace file's parent directory lazily so a tracking-
 *  disabled run leaves no empty `run-NNN/` behind.
 *
 *  Each individual tracker attach is best-effort — a single
 *  failure (e.g. a read-only filesystem at the trace file's parent)
 *  doesn't abort the others or the run itself.  Use
 *  [TrackerAttachResult] flags when you need to know what landed.
 *
 *  Substrate-level API — any UI shell can attach trackers
 *  consistently from a captured [Solver] reference + the host's
 *  current [SolverTrackingSpec].
 */
object OptimizationTrackerAttacher {

    /** Per-tracker success flags.  Both fields are `false` when the
     *  corresponding tracker was not requested by the spec — i.e.
     *  "skipped" and "failed" are indistinguishable in the result.
     *  Use [SolverTrackingSpec.enableCsvTrace] / `enableConsoleTrace`
     *  to disambiguate if needed. */
    data class TrackerAttachResult(
        val csvAttached: Boolean,
        val consoleAttached: Boolean
    )

    /**
     *  Attach the requested trackers to [solver].
     *
     *  @param solver        The live solver instance.  When this is
     *                       a [RandomRestartSolver], nested tracker
     *                       variants are used so both macro and
     *                       micro iterations appear in the trace.
     *  @param trackingSpec  The host's tracking preferences.
     *  @param runDir        The run-output directory.  CSV trace
     *                       lands inside this directory at the
     *                       filename derived from [trackingSpec]
     *                       (via [OptimizationPaths.traceFilePath]).
     *  @param solverSpec    Used by [OptimizationPaths.traceFilePath]
     *                       to derive a default CSV filename when
     *                       [trackingSpec] doesn't specify one.
     *                       Pass `null` when the spec is unknown —
     *                       the helper falls back to a generic stem.
     */
    fun attach(
        solver: Solver,
        trackingSpec: SolverTrackingSpec,
        runDir: Path,
        solverSpec: SolverSpec? = null
    ): TrackerAttachResult {
        if (!trackingSpec.enableCsvTrace && !trackingSpec.enableConsoleTrace) {
            return TrackerAttachResult(csvAttached = false, consoleAttached = false)
        }

        val csvAttached = if (trackingSpec.enableCsvTrace) {
            attachCsv(solver, trackingSpec, runDir, solverSpec)
        } else false

        val consoleAttached = if (trackingSpec.enableConsoleTrace) {
            attachConsole(solver, trackingSpec)
        } else false

        return TrackerAttachResult(csvAttached, consoleAttached)
    }

    private fun attachCsv(
        solver: Solver,
        trackingSpec: SolverTrackingSpec,
        runDir: Path,
        solverSpec: SolverSpec?
    ): Boolean = try {
        val tracePath = OptimizationPaths.traceFilePath(
            runOutputDir = runDir,
            trackingSpec = trackingSpec,
            solverSpec = solverSpec
        ) ?: return false
        Files.createDirectories(tracePath.parent)
        if (solver is RandomRestartSolver) {
            val tracker = NestedCsvSolverStateTracker(
                macroSolver = solver,
                microSolver = solver.restartingSolver,
                outputFile = tracePath.toFile()
            )
            tracker.experimentName = trackingSpec.experimentLabel
            tracker.startTracking()
        } else {
            val tracker = CsvSolverStateTracker(
                solver = solver,
                outputFile = tracePath.toFile()
            )
            tracker.experimentName = trackingSpec.experimentLabel
            tracker.startTracking()
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun attachConsole(
        solver: Solver,
        trackingSpec: SolverTrackingSpec
    ): Boolean = try {
        if (solver is RandomRestartSolver) {
            val tracker = NestedConsoleSolverStateTracker(
                macroSolver = solver,
                microSolver = solver.restartingSolver
            )
            tracker.experimentName = trackingSpec.experimentLabel
            tracker.startTracking()
        } else {
            val tracker = ConsoleSolverStateTracker(solver = solver)
            tracker.experimentName = trackingSpec.experimentLabel
            tracker.startTracking()
        }
        true
    } catch (_: Throwable) {
        false
    }
}
