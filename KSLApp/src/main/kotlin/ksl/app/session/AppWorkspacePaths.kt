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

import ksl.app.config.sanitizeAnalysisName
import java.nio.file.Path

/**
 *  Filesystem-path conventions for multi-document UI applications
 *  (Scenario, Experiment, Simopt today; any future non-Swing host
 *  tomorrow) that group all their per-app artefacts under a single
 *  subdirectory of the user's active workspace.
 *
 *  Lays out a stable directory shape:
 *
 *  <pre>
 *  &lt;activeWorkspace&gt;/                       ← user-wide root from UserSettingsStore
 *    &lt;sanitizeAppName(appName)&gt;/            ← appWorkspaceDir
 *      output/
 *        &lt;sanitizeAnalysisName(name)&gt;/      ← outputDir
 *          reports/                         ← reportsDir
 *  </pre>
 *
 *  Pure functions over [Path] / [String] — no Swing dependency, no
 *  live engine state.  Any host application that wants to honour
 *  this standard layout (so users can navigate between analyses in
 *  a file manager with stable expectations) calls these.
 *
 *  ## Relationship to other path objects
 *
 *  - `ksl.app.settings.WorkspaceLayout` covers the scenario
 *    workflow §2 runId-keyed layout
 *    (`<workspace>/output/<runId>/`).  Different convention,
 *    different consumers.
 *  - `ksl.app.optimization.paths.OptimizationPaths` adds
 *    optimization-specific helpers (`run-NNN` subdirectories,
 *    trace files) on top of this layout.  Its `outputDir(...)`
 *    delegates here.
 *
 *  Single-document hosts (single-app today) deliberately use a
 *  different convention — nesting by analysisName at the workspace
 *  level rather than by appName — and do not call these helpers.
 *
 *  Substrate-level API — usable by any UI shell.
 */
object AppWorkspacePaths {

    /** Filesystem-segment-safe form of [appName] — spaces become
     *  underscores.  Idempotent on already-safe input.  Used to
     *  derive the per-app subdirectory under the active workspace.
     *
     *  Distinct from [sanitizeAnalysisName] (which also handles
     *  other unsafe characters) because app names are developer-
     *  controlled rather than user-controlled — the rule can be
     *  permissive. */
    fun sanitizeAppName(appName: String): String = appName.replace(" ", "_")

    /** Resolves `<activeWorkspace>/<sanitizeAppName(appName)>/`.
     *  The directory is **not created** by this helper. */
    fun appWorkspaceDir(activeWorkspace: Path, appName: String): Path =
        activeWorkspace.resolve(sanitizeAppName(appName))

    /** Resolves `<appWorkspace>/output/<sanitizeAnalysisName(analysisName)>/`.
     *  Caller supplies the host's appWorkspace (typically computed
     *  via [appWorkspaceDir]).  The directory is **not created** by
     *  this helper. */
    fun outputDir(appWorkspace: Path, analysisName: String): Path =
        appWorkspace.resolve("output").resolve(sanitizeAnalysisName(analysisName))

    /** Resolves `<outputDir(appWorkspace, analysisName)>/reports/`.
     *  Convenience composition for hosts that materialise their
     *  rendered reports under the standard `reports/` subdirectory
     *  of the analysis output.  The directory is **not created** by
     *  this helper. */
    fun reportsDir(appWorkspace: Path, analysisName: String): Path =
        outputDir(appWorkspace, analysisName).resolve("reports")
}
