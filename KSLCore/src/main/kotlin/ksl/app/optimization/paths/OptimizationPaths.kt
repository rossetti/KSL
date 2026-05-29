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

package ksl.app.optimization.paths

import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.config.sanitizeAnalysisName
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Filesystem-path conventions for optimization runs.
 *
 *  Lays out a stable directory shape under any host application's
 *  workspace:
 *
 *  ```
 *  <appWorkspace>/
 *    output/
 *      <sanitizedAnalysisName>/        ← analysis output directory
 *        run-001/                      ← one run-NNN per submit
 *          summary.toml
 *          iteration_history.csv
 *          …
 *          <trace>.csv                 ← when tracking enabled
 *        run-002/
 *        …
 *  ```
 *
 *  These helpers are pure functions over [Path] / [SolverSpec] /
 *  [SolverTrackingSpec] — no Swing dependency, no live engine
 *  state.  Any host application that wants to honour the standard
 *  layout (so users can navigate between runs in a file manager
 *  with stable expectations) calls these.
 *
 *  Substrate-level API — usable by any UI shell.
 */
object OptimizationPaths {

    /** Resolve `<appWorkspace>/output/<sanitizedAnalysisName>/`.
     *  Caller supplies the host's appWorkspace. */
    fun outputDir(appWorkspace: Path, analysisName: String): Path {
        val sanitized = sanitizeAnalysisName(analysisName)
        return appWorkspace.resolve("output").resolve(sanitized)
    }

    /**
     *  Find the next unused `run-NNN` subdirectory under [analysisDir].
     *
     *  Pattern: `run-001`, `run-002`, … Numbers are zero-padded to 3
     *  digits. Returns `run-001` when [analysisDir] doesn't exist yet
     *  or has no matching subdirectories; otherwise returns
     *  `run-<max+1>` so a fresh run never overwrites a prior one.
     *
     *  Numbers ≥ 1000 keep growing — the format-string still produces
     *  a unique name, just one digit longer than the typical
     *  three-digit form.
     *
     *  The directory is **not created** by this helper.  Callers
     *  create it at submit time after the path is committed.
     */
    fun nextRunSubdir(analysisDir: Path): Path {
        val pattern = Regex("""run-(\d{3,})""")
        var maxN = 0
        if (Files.isDirectory(analysisDir)) {
            Files.newDirectoryStream(analysisDir).use { stream ->
                for (entry in stream) {
                    if (!Files.isDirectory(entry)) continue
                    val match = pattern.matchEntire(entry.fileName.toString()) ?: continue
                    val n = match.groupValues[1].toIntOrNull() ?: continue
                    if (n > maxN) maxN = n
                }
            }
        }
        return analysisDir.resolve("run-%03d".format(maxN + 1))
    }

    /**
     *  Compute the CSV trace file path inside a specific run directory.
     *
     *  Falls back to a solver-derived stem when
     *  [SolverTrackingSpec.csvFileName] is null.  The fallback uses
     *  `solverSpec.name` when present, else the algorithm-kind serial
     *  name (e.g. `"stochasticHillClimbing"`), else `"solver"`.
     *
     *  Returns `null` when [SolverTrackingSpec.enableCsvTrace] is
     *  false.
     */
    fun traceFilePath(
        runOutputDir: Path,
        trackingSpec: SolverTrackingSpec,
        solverSpec: SolverSpec?
    ): Path? {
        if (!trackingSpec.enableCsvTrace) return null
        val stem = trackingSpec.csvFileName ?: defaultTraceStem(solverSpec)
        return runOutputDir.resolve("$stem.csv")
    }

    /** Default CSV-trace file stem when the user hasn't set one.
     *  Format: `<solverName>_trace` when the solver has a name;
     *  `<kindLabel>_trace` otherwise. */
    fun defaultTraceStem(solverSpec: SolverSpec?): String {
        val nameOrKind = solverSpec?.name?.takeIf { it.isNotBlank() }
            ?: solverSpec?.kindLabel()
            ?: "solver"
        return "${nameOrKind}_trace"
    }

    private fun SolverSpec.kindLabel(): String = when (this) {
        is SolverSpec.StochasticHillClimbing -> "stochasticHillClimbing"
        is SolverSpec.SimulatedAnnealing -> "simulatedAnnealing"
        is SolverSpec.CrossEntropy -> "crossEntropy"
        is SolverSpec.RSpline -> "rSpline"
    }
}
