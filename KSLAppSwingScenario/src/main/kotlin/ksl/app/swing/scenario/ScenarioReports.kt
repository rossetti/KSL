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

package ksl.app.swing.scenario

import ksl.app.config.ReportFormat
import ksl.app.session.RunResult
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Reporting layer for the Scenario app.  Round 1 surfaces a single
 *  *per-scenario summary* report built directly from
 *  `SimulationSnapshot.ExperimentCompleted` via the existing
 *  `ksl.utilities.io.report.extensions.snapshotSimulationResults`
 *  pipeline — no substrate change needed.
 *
 *  Round 2 will add cross-scenario reports (box plots, MCA,
 *  per-replication trace) once the substrate exposes per-scenario
 *  `ReplicationCompleted` snapshots on `RunResult.BatchCompleted`.
 *  Those report types need per-replication observations that
 *  `ExperimentCompleted` (the only data the app sees today) does
 *  not carry.
 */
object ScenarioReports {

    /** Result of a [renderPerScenarioSummary] (or future render)
     *  call.  Lets the caller display per-format successes and
     *  errors in one notification pass. */
    data class WriteOutcome(
        val written: List<Path>,
        val errors: List<String>
    )

    /** Scenario names available for report generation — those that
     *  produced a completed snapshot.  Drives the GUI picker. */
    fun availableScenarioNames(result: RunResult.BatchCompleted): List<String> =
        result.snapshots.map { it.simulationRun.run_name }

    /**
     *  Render a per-scenario summary for [scenarioName] in every
     *  format in [formats], writing the files under [outputDir].
     *  Returns the list of files actually written and any per-format
     *  errors.
     *
     *  Internally delegates to the substrate's
     *  `ExperimentCompleted.toReport(...)` extension, which produces
     *  the full snapshot report: run summary, across-replication
     *  statistics (count, mean, std-dev, half-width, CI, min/max),
     *  histograms grouped by response, integer frequencies, and
     *  per-period time-series statistics — every section that the
     *  collected snapshot supports.
     */
    fun renderPerScenarioSummary(
        result: RunResult.BatchCompleted,
        scenarioName: String,
        outputDir: Path,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        val snapshot = result.snapshots.firstOrNull {
            it.simulationRun.run_name == scenarioName
        } ?: return WriteOutcome(
            written = emptyList(),
            errors = listOf("Scenario '$scenarioName' has no completed snapshot.")
        )
        if (formats.isEmpty()) {
            return WriteOutcome(
                written = emptyList(),
                errors = listOf("No report formats selected.")
            )
        }
        val doc = snapshot.toReport(
            title = "Scenario Summary — $scenarioName"
        )
        return writeAll(doc, outputDir, fileStem("scenario-summary", scenarioName), formats)
    }

    // ── File writing ──────────────────────────────────────────────────────

    private fun writeAll(
        doc: ReportNode.Document,
        outputDir: Path,
        stem: String,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        Files.createDirectories(outputDir)
        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        for (fmt in formats) {
            try {
                val ext = when (fmt) {
                    ReportFormat.HTML -> "html"
                    ReportFormat.MARKDOWN -> "md"
                    ReportFormat.TEXT -> "txt"
                }
                val path = outputDir.resolve("$stem.$ext")
                when (fmt) {
                    ReportFormat.HTML -> doc.writeHtml(path = path)
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path)
                    ReportFormat.TEXT -> doc.writeText(path = path)
                }
                written.add(path)
            } catch (t: Throwable) {
                errors.add("${fmt.name}: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        return WriteOutcome(written, errors)
    }

    /** Stable filesystem-safe filename stem so re-renders overwrite
     *  cleanly instead of accumulating versioned files.  Sanitises
     *  the scenario name (drops anything outside `[A-Za-z0-9._-]`)
     *  and caps the length so unusual scenario names don't blow
     *  past path limits on Windows. */
    private fun fileStem(prefix: String, key: String): String {
        val sanitised = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return "$prefix-$sanitised"
    }
}
