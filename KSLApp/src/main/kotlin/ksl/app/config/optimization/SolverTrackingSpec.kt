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
import net.peanuuutz.tomlkt.TomlComment

/**
 * Optional CSV / console trace settings for a simulation-optimization
 * run.
 *
 * Drives the attachment of one of the autonomous trackers in
 * `ksl.simopt.solvers.trackers` at submit time:
 *
 * - When [enableCsvTrace] is `true`, the host attaches a
 *   `ksl.simopt.solvers.trackers.CsvSolverStateTracker` for a plain
 *   solver, or a
 *   `ksl.simopt.solvers.trackers.NestedCsvSolverStateTracker` when the
 *   built solver is wrapped by
 *   `ksl.simopt.solvers.algorithms.RandomRestartSolver`.  The
 *   single-vs-nested choice is made at attachment time based on the
 *   live `ksl.simopt.solvers.Solver` instance and is **not** persisted
 *   in this spec.
 * - When [enableConsoleTrace] is `true`, the host additionally
 *   attaches a `ksl.simopt.solvers.trackers.ConsoleSolverStateTracker`.
 *
 * [experimentLabel] is propagated to the tracker's
 * `experimentName` field (which becomes the
 * `ksl.simopt.solvers.trackers.TrackingContext.experimentName` value
 * stamped on every emitted row); users can vary it between runs to
 * semantically group tracker output.
 *
 * [csvFileName] is the file stem only (no extension, no path).  The
 * host resolves it under `<workspace>/output/<analysisName>/optimization/`
 * at submit time.  When `null`, the host substitutes a default
 * derived from the solver's name (e.g. `<solverName>_trace`).
 *
 * Domain invariants are enforced in `init` so a malformed spec cannot
 * be constructed:
 *
 * - [experimentLabel] must be non-blank.
 * - [csvFileName] must be non-blank when non-null.
 *
 * @property enableCsvTrace when true, attach a CSV tracker for the
 *           duration of the run
 * @property csvFileName file stem under
 *           `<workspace>/output/<analysisName>/optimization/`; `null`
 *           lets the host pick a default
 * @property enableConsoleTrace when true, additionally attach a
 *           console tracker that mirrors each iteration snapshot to
 *           standard output
 * @property experimentLabel value written into the tracker's
 *           `experimentName` field; tags every emitted row so multiple
 *           runs can be distinguished in one combined trace file
 */
@Serializable
data class SolverTrackingSpec(
    @TomlComment(
        "Boolean. When true, attach a CSV tracker for the duration of\n" +
        "the run.  Trace lands at\n" +
        "<workspace>/output/<analysisName>/optimization/<csvFileName>.csv.\n" +
        "Default: false."
    )
    val enableCsvTrace: Boolean = false,

    @TomlComment(
        "String or omitted. File stem (no extension, no path) used\n" +
        "when enableCsvTrace = true.  When omitted, the host substitutes\n" +
        "a default derived from the solver's name.  Must be non-blank\n" +
        "when present."
    )
    val csvFileName: String? = null,

    @TomlComment(
        "Boolean. When true, additionally attach a console tracker\n" +
        "that mirrors each iteration snapshot to standard output.\n" +
        "Default: false."
    )
    val enableConsoleTrace: Boolean = false,

    @TomlComment(
        "String. Tags every emitted tracker row so multiple runs that\n" +
        "share one trace file can be distinguished.  Must be non-blank.\n" +
        "Default: 'Run1'."
    )
    val experimentLabel: String = "Run1"
) {
    init {
        require(experimentLabel.isNotBlank()) {
            "experimentLabel must be non-blank"
        }
        require(csvFileName == null || csvFileName.isNotBlank()) {
            "csvFileName must be non-blank when non-null"
        }
    }
}
