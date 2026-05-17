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
    val outputDirectory: String? = null
)
