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
import net.peanuuutz.tomlkt.TomlComment
import java.nio.file.Path

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
    @TomlComment(
        "Boolean. When true, capture each scenario's results in the\n" +
        "shared KSL SQLite database under\n" +
        "<workspace>/output/<analysisName>/<analysisName>.db.\n" +
        "Required for the Comparison Analyzer tab.  Default: false."
    )
    val enableKSLDatabase: Boolean = false,

    @TomlComment(
        "Boolean. When true, write per-replication CSV files (one row\n" +
        "per response per replication) to\n" +
        "<workspace>/output/<analysisName>/csvDir/.  Document-wide\n" +
        "default; per-scenario CSV toggles on [[scenarios]] entries\n" +
        "override this.  Default: false."
    )
    val enableReplicationCSV: Boolean = false,

    @TomlComment(
        "Boolean. When true, write across-replication summary CSVs.\n" +
        "Independent of enableReplicationCSV — opt in here if you want\n" +
        "only the summary file without the larger per-replication one.\n" +
        "Default: false."
    )
    val enableExperimentCSV: Boolean = false,

    @TomlComment(
        "List of report formats produced by the Single app's pre-run\n" +
        "auto-render workflow.  Allowed elements (any subset, order\n" +
        "preserved): 'HTML', 'MARKDOWN', 'TEXT'.  Default: ['HTML'].\n" +
        "The Scenario app's on-demand Reports tab picks its own format\n" +
        "per Generate click and DOES NOT read this list."
    )
    val reports: Set<ReportFormat> = setOf(ReportFormat.HTML),

    @TomlComment(
        "Absolute filesystem path the runtime uses for the model's\n" +
        "outputDirectory.  Set at submit time by the hosting app from\n" +
        "the workspace plus analysisName; DO NOT edit by hand.  null =\n" +
        "framework default ('kslOutput' under the JVM working dir)."
    )
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
    @TomlComment(
        "String. Identity for this analysis.  Names the subdirectory\n" +
        "<workspace>/output/<analysisName>/ where all artifacts land,\n" +
        "and the SQLite database file stem.  Sanitised at write time\n" +
        "(letters/digits/_/-, max 64 chars; anything else replaced\n" +
        "with _).  Default: 'Untitled'.  The Scenario app auto-fills\n" +
        "this from the saved file's stem the first time you save."
    )
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
    @TomlComment(
        "Policy for what to do when <analysisName>.db already exists\n" +
        "at the start of a Simulate.  Allowed values:\n" +
        "  'OVERWRITE' — delete the existing file and create fresh (default).\n" +
        "  'NEW'       — write <analysisName>_<yyyy-MM-dd_HHmmss>.db beside\n" +
        "                  the existing file, leaving it untouched.\n" +
        "There is no 'APPEND' option: KSL's schema rejects duplicate\n" +
        "experiment names, which a same-document re-run always produces."
    )
    val databasePolicy: DatabasePolicy = DatabasePolicy.OVERWRITE,

    @TomlComment(
        "Boolean. When true, attach a WelchFileObserver to each selected\n" +
        "response before the run so warm-up (initialization-bias) data is\n" +
        "streamed to <workspace>/output/<responseName>_Welch/ during the\n" +
        "run.  Required to produce a Welch report afterward — it cannot be\n" +
        "reconstructed from a finished run.  Default: false."
    )
    val enableWelchAnalysis: Boolean = false,

    @TomlComment(
        "List of responses to capture for Welch analysis, each paired with\n" +
        "its discretizing interval.  Tally responses use the value as a\n" +
        "batch size (e.g. 1.0); time-weighted responses use it as a delta-t\n" +
        "interval (e.g. 10.0).  Only consulted when enableWelchAnalysis is\n" +
        "true.  Default: empty (no responses captured)."
    )
    val welchResponses: List<WelchResponseSpec> = emptyList(),

    @TomlComment(
        "Boolean. Include the partial-sums plot section in the Welch\n" +
        "report.  Default: true."
    )
    val welchIncludePartialSums: Boolean = true,

    @TomlComment(
        "Boolean. Include the Schruben initialization-bias test section in\n" +
        "the Welch report.  Default: false."
    )
    val welchIncludeBiasTest: Boolean = false,

    @TomlComment(
        "Boolean. Include the post-deletion batch-means analysis section in\n" +
        "the Welch report.  Default: false."
    )
    val welchIncludeBatchMeans: Boolean = false,

    @TomlComment(
        "Int. Warm-up deletion point for the report's batch-means and\n" +
        "bias-test sections.  -1 selects the MSER recommendation; any value\n" +
        ">= 0 is used as a fixed deletion point.  Default: -1."
    )
    val welchDeletionPoint: Int = -1,

    @TomlComment(
        "Boolean. When true, the Single app materializes the Welch report\n" +
        "automatically after each Simulate, reusing the `reports` format\n" +
        "set for the output mix.  Default: false."
    )
    val welchAutoRender: Boolean = false
)

/**
 *  One response selected for Welch warm-up analysis, paired with the
 *  discretizing interval used when the response's observations are
 *  streamed to disk during the run.
 *
 *  Stored in [OutputConfig.welchResponses].  The orchestrator turns each
 *  entry into a `WelchFileObserver` attached to the named response on
 *  `ksl.simulation.Model` before the run starts.
 *
 *  @property responseName the model response to capture.  Must match a
 *    response name on the built model at attach time; unknown names are
 *    skipped defensively by the orchestrator.
 *  @property interval the batching/discretizing interval — a batch size
 *    for tally responses (e.g. 1.0) or a delta-t for time-weighted
 *    responses (e.g. 10.0).
 */
@Serializable
data class WelchResponseSpec(
    val responseName: String,
    val interval: Double
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

/**
 *  Once-at-default auto-fill helper for `markSaved` paths in
 *  configuration-shaped apps: derive a fresh analysis name from
 *  a just-saved file's stem, but only when the current
 *  `analysisName` is still at its default sentinel.
 *
 *  Pre-decomposition, Scenario / Experiment / Simopt each carried a
 *  near-identical 6-line block inside `markSaved`:
 *
 *      if (myOutputConfig.value.analysisName == "Untitled") {
 *          val stem = path.fileName.toString().substringBeforeLast('.')
 *          if (stem.isNotBlank()) {
 *              myOutputConfig.value =
 *                  myOutputConfig.value.copy(analysisName = stem)
 *          }
 *      }
 *
 *  Simopt additionally piped the stem through
 *  [sanitizeAnalysisName].  This helper captures the shared shape
 *  so each host's `markSaved` collapses to a single call.  Returns
 *  the new name to apply, or `null` when no change is warranted
 *  — caller is responsible for the `copy(analysisName = …)` mutation.
 *
 *  Semantics:
 *  - Returns `null` when [currentName] is not [sentinel].  Once the
 *    user has set a non-default name, save-as to a different file
 *    must NOT silently rename their analysis.
 *  - Returns `null` when the file's stem (the name without the
 *    final `.ext`) is blank — happens for hidden files like
 *    `.config` or paths whose final segment has no characters
 *    before the dot.
 *  - Otherwise returns [sanitizer] applied to the stem.  The
 *    default sanitizer is the identity function, so Scenario /
 *    Experiment get the raw stem; Simopt passes
 *    [sanitizeAnalysisName] to coerce the stem into the same shape
 *    used elsewhere in the Simopt pipeline.
 *
 *  @param path the file the document was just persisted to —
 *  only `path.fileName` is read; the parent path is ignored
 *  @param currentName the document's current `analysisName`
 *  @param sentinel the "still at default" marker; `"Untitled"` by default
 *  @param sanitizer applied to the file stem before returning;
 *  identity by default
 *  @return the new name to assign, or `null` to leave the current
 *  name alone
 */
fun analysisNameFromFileStem(
    path: Path,
    currentName: String,
    sentinel: String = "Untitled",
    sanitizer: (String) -> String = { it }
): String? {
    if (currentName != sentinel) return null
    val stem = path.fileName.toString().substringBeforeLast('.')
    if (stem.isBlank()) return null
    return sanitizer(stem)
}
