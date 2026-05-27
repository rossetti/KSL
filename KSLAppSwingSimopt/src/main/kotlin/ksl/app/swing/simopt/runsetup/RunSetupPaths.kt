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

package ksl.app.swing.simopt.runsetup

import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.config.sanitizeAnalysisName
import java.nio.file.Path

/**
 *  Pure helpers used by `TrackingPanel`, `RunPreviewPanel`, and the
 *  Phase O7b submit path to compute resolved output / trace paths
 *  from the current document.  Kept as top-level functions so they
 *  can be unit-tested without instantiating Swing.
 */
internal object RunSetupPaths {

    /** Resolve `<appWorkspace>/output/<sanitizedAnalysisName>/`.
     *  Caller should ensure [appWorkspace] is the controller's
     *  appWorkspace. */
    fun outputDir(appWorkspace: Path, analysisName: String): Path {
        val sanitized = sanitizeAnalysisName(analysisName)
        return appWorkspace.resolve("output").resolve(sanitized)
    }

    /** Resolve the optimization-specific subdirectory inside the
     *  analysis output dir: `<outputDir>/optimization/`. */
    fun optimizationDir(appWorkspace: Path, analysisName: String): Path =
        outputDir(appWorkspace, analysisName).resolve("optimization")

    /** Compute the CSV trace file path, if tracking is enabled.
     *
     *  Falls back to a solver-derived stem when [SolverTrackingSpec.csvFileName]
     *  is null.  The fallback uses `solverSpec.name` when present,
     *  else the algorithm-kind serial name (e.g. `"stochasticHillClimbing"`),
     *  else "solver".
     *
     *  Returns `null` when [SolverTrackingSpec.enableCsvTrace] is false. */
    fun traceFilePath(
        appWorkspace: Path,
        analysisName: String,
        trackingSpec: SolverTrackingSpec,
        solverSpec: SolverSpec?
    ): Path? {
        if (!trackingSpec.enableCsvTrace) return null
        val stem = trackingSpec.csvFileName ?: defaultTraceStem(solverSpec)
        return optimizationDir(appWorkspace, analysisName).resolve("$stem.csv")
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
