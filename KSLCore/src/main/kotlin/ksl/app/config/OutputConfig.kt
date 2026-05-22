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

package ksl.app.config

import kotlinx.serialization.Serializable

/**
 * Per-run output choices: which side-effects the framework wires before the
 * run starts and which reports it materializes after the run completes.
 *
 * Lives at document scope on `ksl.app.config.RunConfiguration` (not
 * per-scenario) so that every scenario in a run shares one output-directory
 * layout under the active workspace.
 *
 * **Substrate-prep only.**  The field is stored on `RunConfiguration` and
 * round-trips through both codecs; the orchestrators / Single-framework
 * controller do not yet consult it.  Phase 6D wires the per-flag attachment
 * assembly and the post-run report materialization that these flags govern.
 *
 * Defaults: HTML report only; no database; no CSV.  An analyst running with
 * the defaults gets something openable without opting in to anything; a CI
 * or production user can suppress all output by passing
 * `OutputConfig(reports = emptySet())`.
 *
 * @property enableKSLDatabase when true, the orchestrator wires a
 *   `ksl.utilities.io.dbutil.KSLDatabaseObserver` (SQLite backend) so
 *   the run's data lands in a `KSLDatabase` under the workspace's
 *   output directory.
 * @property enableReplicationCSV when true, the orchestrator sets
 *   `ksl.simulation.Model.autoReplicationCSVReports = true` so the
 *   per-replication CSV (one row per response per replication) is
 *   written to the workspace's `csvDir`.
 * @property enableExperimentCSV when true, the orchestrator sets
 *   `ksl.simulation.Model.autoExperimentCSVReports = true` so the
 *   across-replication summary CSV is written to the workspace's
 *   `csvDir`.  Independent of [enableReplicationCSV] — analysts who
 *   want only summary data (and not the larger per-replication file)
 *   can opt in here without enabling [enableReplicationCSV].
 * @property reports the set of report formats to materialize after the
 *   run completes.  Empty set means no reports.
 *
 *   **Read-by-app variance.**  The Single app's auto-render workflow
 *   honors this set verbatim — every Simulate emits one file per format.
 *   The Scenario app **does not** read this field for its on-demand
 *   reports; that workflow's *Scenario Reports* dialog makes its own
 *   per-Generate format choice (single format, HTML default,
 *   in-session only).  The two workflows have different mental models
 *   — pre-run auto-emit vs. post-run viewing — and a shared persistent
 *   field caused user confusion when the Scenario dialog tried to honor
 *   a multi-format set.
 * @property outputDirectory absolute path where the framework places the
 *   model's runtime output (the equivalent of
 *   `ksl.simulation.Model.outputDirectory`).  When `null` (default), each
 *   `Model` keeps its constructor-supplied default —
 *   `<programLaunchDirectory>/kslOutput/<modelName>_OutputDir/` — which
 *   for GUI consumers typically lands inside the JVM working directory
 *   and pollutes the launch tree.  Hosts that own a workspace should set
 *   this to a workspace-relative path; the orchestrator replaces the
 *   model's `outputDirectory` with `OutputDirectory(path, "kslOutput.txt")`
 *   before the run starts so KSL framework files (kslOutput.txt, csvDir,
 *   dbDir, plotDir, etc.) land under the workspace instead of the launch
 *   directory.
 */
@Serializable
data class OutputConfig(
    val enableKSLDatabase: Boolean = false,
    val enableReplicationCSV: Boolean = false,
    val enableExperimentCSV: Boolean = false,
    val reports: Set<ReportFormat> = setOf(ReportFormat.HTML),
    val outputDirectory: String? = null,
    /**
     *  Display name for this analysis — the user's label for the set
     *  of scenarios in this document.  Used by hosts as:
     *
     *  - the subdirectory under `<workspace>/output/` where every
     *    artifact of a Simulate (the `<analysisName>.db`, reports,
     *    CSVs, kslOutput.txt) lands;
     *  - the stem of the SQLite database file produced by the
     *    `ScenarioOrchestrator` when [enableKSLDatabase] is on;
     *  - a stable identity for the document so re-running the same
     *    scenarios overwrites the same artifacts instead of
     *    accumulating new ones.
     *
     *  Defaults to `"Untitled"` for fresh documents.  The Scenario
     *  app auto-fills from the TOML filename stem on the first Save
     *  while the field is still at the default; thereafter the user
     *  owns it.  Sanitised at write time via [sanitizeAnalysisName]
     *  before it touches the filesystem; the stored value is the
     *  user-typed form so the UI shows what they typed.
     */
    val analysisName: String = "Untitled",
    /**
     *  Policy for what to do with `<analysisName>.db` when it
     *  already exists on disk at the start of a Simulate.
     *
     *  KSL's database schema rejects re-inserting `SimulationRun`
     *  rows whose experiment names collide with rows already present
     *  (and a re-run of the same document has identical experiment
     *  names by construction).  So there is no "append to existing
     *  database" option — re-running means *replace* or *side-by-
     *  side*.  See [DatabasePolicy] for the two outcomes.
     */
    val databasePolicy: DatabasePolicy = DatabasePolicy.OVERWRITE
)

/**
 *  How the `ScenarioOrchestrator` should handle an existing
 *  `<analysisName>.db` file at the start of a Simulate.  Stored on
 *  [OutputConfig.databasePolicy].
 */
@Serializable
enum class DatabasePolicy {
    /**
     *  Delete the existing `<analysisName>.db` (if present) before
     *  the run, then create a fresh database.  Re-running the same
     *  analysis replaces the prior database file in-place; only the
     *  most recent run's data is kept on disk.  The default — matches
     *  the most common workflow where the user iterates on a
     *  configuration and only cares about the latest results.
     */
    OVERWRITE,

    /**
     *  Keep any existing `<analysisName>.db` untouched and open a
     *  new database at `<analysisName>_<yyyy-MM-dd_HHmmss>.db`
     *  alongside it.  Both files survive on disk — the user can
     *  compare runs across time.  Disk usage accumulates; periodic
     *  cleanup is the user's responsibility.
     */
    NEW
}

/**
 *  Coerce [raw] into a filesystem-safe form suitable for both a
 *  directory name and a database file stem.  Replaces any character
 *  outside `[A-Za-z0-9_-]` with `_`, trims the result to at most 64
 *  characters, and returns `"Untitled"` when the coerced form is
 *  empty (for example, when the user typed only whitespace).
 *
 *  Stable and idempotent: a value that already satisfies the rules
 *  is returned unchanged.
 */
fun sanitizeAnalysisName(raw: String): String {
    // Trim first so a purely-whitespace input collapses to empty
    // (and then to "Untitled"); otherwise each space would map to
    // '_' and produce a meaningless "___" identifier.
    val trimmed = raw.trim()
    val cleaned = trimmed.map { c ->
        if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
    }.joinToString("").take(64)
    return cleaned.ifEmpty { "Untitled" }
}
