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

package ksl.app.single.results

import java.nio.file.Path

/**
 *  Filesystem-path conventions for single-document UI applications
 *  (the Single app today; any future non-Swing single-document host
 *  tomorrow).  Single-document hosts deliberately use a flatter
 *  layout than the multi-document hosts (`Scenario`, `Experiment`,
 *  `Simopt`), nesting per-analysis rather than per-app under the
 *  active workspace:
 *
 *  <pre>
 *  &lt;activeWorkspace&gt;/
 *    &lt;analysisFolder&gt;/            ← appWorkspaceDir
 *      reports/                    ← reportsDir
 *  </pre>
 *
 *  Pure functions over [Path] / [String] — no Swing dependency, no
 *  live engine state.
 *
 *  ## Relationship to other path objects
 *
 *  - `ksl.app.session.AppWorkspacePaths` covers the multi-document
 *    app convention
 *    (`<workspace>/<sanitizeAppName(appName)>/output/<sanitizeAnalysisName(name)>/`).
 *    Different convention, different consumers.
 *  - `ksl.app.settings.WorkspaceLayout` covers the scenario workflow
 *    §2 runId-keyed layout (`<workspace>/output/<runId>/`).
 *  - `ksl.app.optimization.paths.OptimizationPaths` adds Simopt's
 *    `run-NNN` subdirectories and trace-file conventions on top of
 *    the multi-document layout.
 *
 *  Substrate-level API — usable by any UI shell.
 */
object SingleAppPaths {

    /**
     *  Canonical "no analysis name yet" sentinel.  Treated by
     *  [appWorkspaceDir] as equivalent to a blank analysis name so
     *  the modelName fallback applies.  Hosts that surface a default
     *  analysis name to the user typically display this string.
     */
    const val UNTITLED: String = "Untitled"

    /**
     *  Filesystem-segment-safe form of [analysisName] for the
     *  single-app layout.  Differs from
     *  `ksl.app.config.sanitizeAnalysisName` in three ways:
     *
     *  1. **Preserves dots.**  Single-app users routinely embed
     *     version-like or path-like tokens in their analysis names
     *     (`baseline.v2`, `mm1.queue-study`); the dot character is
     *     filesystem-safe on every target OS and we keep it intact.
     *  2. **No length truncation.**  Single-app folder names sit
     *     directly under the active workspace (no per-app prefix);
     *     users who type a long analysis name expect to see it on
     *     disk verbatim.
     *  3. **No empty-input fallback.**  Returns an empty string
     *     when the input is empty; callers ([appWorkspaceDir] in
     *     particular) handle the fallback semantically.
     *
     *  Idempotent on already-safe input.  Any character outside
     *  `[A-Za-z0-9._-]` is replaced with `_`.
     */
    fun analysisFolder(analysisName: String): String =
        analysisName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     *  Resolve the single-app's workspace directory for a given
     *  ([analysisName], [modelName]) pair.  Three-tier fallback:
     *
     *  1. When [analysisName] is non-blank AND not the [UNTITLED]
     *     sentinel, returns
     *     `<activeWorkspace>/<analysisFolder(analysisName)>/`.
     *  2. Else when [modelName] is non-empty, returns
     *     `<activeWorkspace>/<modelName>/`.  The caller is
     *     responsible for [modelName] already being a filesystem-safe
     *     segment — KSL's `Model(simulationName)` constructor
     *     replaces spaces with underscores, so models built through
     *     it satisfy this contract automatically.
     *  3. Else returns [activeWorkspace] itself.  This branch fires
     *     when the host's model probe failed and the controller has
     *     no name to derive a folder from; file dialogs still get a
     *     valid starting point.
     *
     *  The directory is **not created** by this helper.
     */
    fun appWorkspaceDir(
        activeWorkspace: Path,
        analysisName: String,
        modelName: String
    ): Path = when {
        analysisName.isNotBlank() && analysisName != UNTITLED ->
            activeWorkspace.resolve(analysisFolder(analysisName))
        modelName.isNotEmpty() ->
            activeWorkspace.resolve(modelName)
        else -> activeWorkspace
    }

    /**
     *  Resolve `<appWorkspace>/reports/` — the canonical reports
     *  directory under a single-app workspace.  Convenience for
     *  hosts that materialise rendered reports into the standard
     *  subdirectory.  Not created by this helper.
     */
    fun reportsDir(appWorkspace: Path): Path =
        appWorkspace.resolve("reports")
}
