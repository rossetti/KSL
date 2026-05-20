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
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.snapshotSimulationResults
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Reporting layer for the Scenario app.
 *
 *  All scenario-name lookups go through the substrate's authoritative
 *  identifier — `ExperimentCompleted.experiment.exp_name` — which the
 *  bridge populates from `model.experimentName`, which the
 *  orchestrator sets from `ScenarioSpec.name`.  Earlier code keyed
 *  on `simulationRun.run_name`; that's a separate (usually-empty)
 *  field from `Model.runName` and is not the scenario identifier.
 *
 *  Every render path writes one file per selected [ReportFormat],
 *  then, when HTML was among the formats, asks the platform to open
 *  the HTML file in the user's default browser via
 *  `ReportNode.Document.showInBrowser()`.
 */
object ScenarioReports {

    /** Result of a render call.  Lets the caller surface per-format
     *  successes and errors in one notification pass. */
    data class WriteOutcome(
        val written: List<Path>,
        val errors: List<String>
    )

    // ── Lookups ───────────────────────────────────────────────────────────

    /** Scenario names available for report generation — those that
     *  produced a completed snapshot, in the order the orchestrator
     *  committed them.  Drives the GUI pickers. */
    fun availableScenarioNames(result: RunResult.BatchCompleted): List<String> =
        result.snapshots.map { it.experiment.exp_name }

    /** Lookup a completed snapshot by scenario name. */
    private fun snapshotFor(
        result: RunResult.BatchCompleted,
        scenarioName: String
    ): SimulationSnapshot.ExperimentCompleted? =
        result.snapshots.firstOrNull { it.experiment.exp_name == scenarioName }

    // ── Render entry points ───────────────────────────────────────────────
    //
    // Cross-scenario reporting (box plot, multiple comparison) used
    // to live here.  Those flows now go through the cross-app
    // Comparison Analyzer ([ksl.app.swing.common.comparison.ComparisonAnalyzerFrame]),
    // which the Reports tab launches via
    // [BatchCompletedComparisonSource].  Only the scenario-summaries
    // and per-scenario summary renderers remain here — they're
    // structurally unrelated to comparisons and have no analyzer
    // equivalent.

    /**
     *  **Primary on-demand report.**  One document covering every
     *  completed scenario.  Sections:
     *  1. Run Overview — table with scenario name, requested reps,
     *     completed reps, run-error flag.
     *  2. Per-scenario across-replication statistics — one
     *     sub-section per scenario containing the response × stat
     *     table (Count, Mean, Std Dev, Half-width, CI bounds,
     *     Min, Max).
     *
     *  Designed to answer "how did all my scenarios stack up?" in one
     *  file, without forcing the analyst to flip between per-scenario
     *  reports.
     */
    fun renderScenarioSummaries(
        result: RunResult.BatchCompleted,
        outputDir: Path,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        if (result.snapshots.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No completed scenarios in the most recent run."))
        }
        val doc = report("Scenario Summaries — ${result.summary.orchestratorName}") {
            paragraph(
                "Run id ${result.summary.runId}.  " +
                    "${result.summary.completedItems} of ${result.summary.totalItems} " +
                    "scenarios completed" +
                    if (result.summary.failedItems > 0) ", ${result.summary.failedItems} failed." else "."
            )
            section("Run Overview") {
                dataTable(
                    headers = listOf("Scenario", "Requested Reps", "Completed Reps", "Run Error"),
                    rows = result.snapshots.map { snap ->
                        val run = snap.simulationRun
                        val completedReps = run.last_rep_id?.let { it - run.start_rep_id + 1 }
                            ?.toString() ?: ""
                        listOf(
                            snap.experiment.exp_name,
                            run.num_reps.toString(),
                            completedReps,
                            if (run.run_error_msg.isNullOrBlank()) "—" else "Yes ⚠"
                        )
                    }
                )
            }
            section("Across-Replication Statistics — Per Scenario") {
                for (snap in result.snapshots) {
                    section(snap.experiment.exp_name) {
                        if (snap.acrossRepStats.isEmpty()) {
                            paragraph("No across-replication statistics recorded for this scenario.")
                        } else {
                            acrossRepStatsTable(snap.acrossRepStats)
                        }
                    }
                }
            }
        }
        return writeAll(doc, outputDir, "scenario-summaries", formats)
    }

    /**
     *  Render a single-scenario summary report for [scenarioName]
     *  using the substrate's existing `snapshotSimulationResults`
     *  pipeline.  Includes everything the snapshot supports: run
     *  summary, across-replication statistics, histograms,
     *  frequencies, time-series period statistics.
     */
    fun renderScenarioSummary(
        result: RunResult.BatchCompleted,
        scenarioName: String,
        outputDir: Path,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        val snapshot = snapshotFor(result, scenarioName) ?: return WriteOutcome(
            written = emptyList(),
            errors = listOf("Scenario '$scenarioName' has no completed snapshot.")
        )
        val doc = report(title = "Scenario Summary — $scenarioName") {
            snapshotSimulationResults(snapshot)
        }
        return writeAll(doc, outputDir, fileStem("scenario-summary", scenarioName), formats)
    }

    // ── DSL helpers ───────────────────────────────────────────────────────

    /** Across-rep stats table used by the consolidated scenario-summaries report.
     *  Mirrors the columns in
     *  [snapshotSimulationResults]'s default rendering but inlined
     *  here so we can drop the per-scenario run-summary section
     *  (the Run Overview table at the top of the document covers
     *  the bookkeeping already). */
    private fun ksl.utilities.io.report.dsl.ReportBuilder.acrossRepStatsTable(
        stats: List<AcrossRepStatTableData>
    ) {
        dataTable(
            headers = listOf(
                "Response", "Count", "Mean", "Std Dev",
                "Half-width", "CI Lower", "CI Upper", "Min", "Max"
            ),
            rows = stats.sortedBy { it.stat_name }.map { row ->
                listOf(
                    row.stat_name,
                    fmtInt(row.stat_count),
                    fmt(row.average),
                    fmt(row.std_dev),
                    fmt(row.half_width),
                    ci(row.average, row.half_width, subtract = true),
                    ci(row.average, row.half_width, subtract = false),
                    fmt(row.minimum),
                    fmt(row.maximum)
                )
            }
        )
    }

    private fun fmt(v: Double?, digits: Int = 4): String =
        v?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { "%.${digits}f".format(it) } ?: ""

    private fun fmtInt(v: Double?): String =
        v?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()?.toString() ?: ""

    private fun ci(mean: Double?, hw: Double?, subtract: Boolean): String {
        if (mean == null || hw == null) return ""
        if (mean.isNaN() || hw.isNaN()) return ""
        val v = if (subtract) mean - hw else mean + hw
        return fmt(v)
    }

    // ── File writing + browser open ──────────────────────────────────────

    /** Writes [doc] in every format in [formats] using [stem] as the
     *  filename base.  When the formats include HTML *and* the HTML
     *  write succeeded, also asks the platform to open the HTML file
     *  in the user's default browser. */
    private fun writeAll(
        doc: ReportNode.Document,
        outputDir: Path,
        stem: String,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        Files.createDirectories(outputDir)
        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        var htmlPath: Path? = null
        for (fmt in formats) {
            try {
                val ext = when (fmt) {
                    ReportFormat.HTML -> "html"
                    ReportFormat.MARKDOWN -> "md"
                    ReportFormat.TEXT -> "txt"
                }
                val path = outputDir.resolve("$stem.$ext")
                when (fmt) {
                    ReportFormat.HTML -> {
                        doc.writeHtml(path = path)
                        htmlPath = path
                    }
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path)
                    ReportFormat.TEXT -> doc.writeText(path = path)
                }
                written.add(path)
            } catch (t: Throwable) {
                errors.add("${fmt.name}: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        if (htmlPath != null) {
            try {
                openInBrowser(htmlPath)
            } catch (t: Throwable) {
                errors.add("Browser open: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        return WriteOutcome(written, errors)
    }

    /** Open [htmlPath] in the user's default browser via
     *  [java.awt.Desktop].  Bypasses the substrate's
     *  `ReportNode.Document.showInBrowser()` because that writes its
     *  own temp file rather than opening the one we already wrote. */
    private fun openInBrowser(htmlPath: Path) {
        if (!java.awt.Desktop.isDesktopSupported()) {
            throw UnsupportedOperationException("Desktop browser open is not supported on this platform.")
        }
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            throw UnsupportedOperationException("Browser action is not supported on this platform.")
        }
        desktop.browse(htmlPath.toUri())
    }

    /** Stable filesystem-safe filename stem so re-renders overwrite
     *  cleanly instead of accumulating versioned files. */
    private fun fileStem(prefix: String, key: String): String {
        val sanitised = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return "$prefix-$sanitised"
    }
}
