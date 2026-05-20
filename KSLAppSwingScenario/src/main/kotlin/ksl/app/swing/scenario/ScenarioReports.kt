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
import ksl.utilities.io.report.extensions.multiBoxPlot
import ksl.utilities.io.report.extensions.multipleComparison
import ksl.utilities.io.report.extensions.snapshotSimulationResults
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.statistic.MultipleComparisonAnalyzer
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

    /** Response names that appear in every scenario's replication
     *  snapshots — the candidates for cross-scenario comparisons.
     *  Returned in sorted order.  Empty when there are fewer than
     *  two scenarios or any scenario has no replication snapshots. */
    fun responsesCommonAcrossScenarios(result: RunResult.BatchCompleted): List<String> {
        if (result.replicationsByItem.size < 2) return emptyList()
        val perScenario = result.replicationsByItem.values.map { repsForScenario ->
            repsForScenario.flatMap { rep -> rep.withinRepStats.map { it.stat_name } }.toSet()
        }
        if (perScenario.any { it.isEmpty() }) return emptyList()
        return perScenario.reduce { acc, set -> acc intersect set }.sorted()
    }

    /** Reconstructs `Map<scenarioName, DoubleArray>` for a single
     *  response by reading `WithinRepStatTableData.average` per
     *  replication, in `rep_id` order, from each scenario's
     *  replication snapshots.  Direct input to [multiBoxPlot] and
     *  the [MultipleComparisonAnalyzer] constructor. */
    fun observationsAsMap(
        result: RunResult.BatchCompleted,
        responseName: String
    ): Map<String, DoubleArray> {
        val out = linkedMapOf<String, DoubleArray>()
        for ((scenarioName, reps) in result.replicationsByItem) {
            val values = reps
                .sortedBy { it.repId }
                .flatMap { rep -> rep.withinRepStats.filter { it.stat_name == responseName } }
                .mapNotNull { it.average }
            if (values.isNotEmpty()) out[scenarioName] = values.toDoubleArray()
        }
        return out
    }

    // ── Render entry points ───────────────────────────────────────────────

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
    fun renderSweepSummary(
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
        val doc = report("Scenario Sweep Summary — ${result.summary.orchestratorName}") {
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
        return writeAll(doc, outputDir, "sweep-summary", formats)
    }

    /**
     *  Render a full per-scenario deep-dive report for [scenarioName]
     *  using the substrate's existing `snapshotSimulationResults`
     *  pipeline.  Includes everything the snapshot supports: run
     *  summary, across-replication statistics, histograms,
     *  frequencies, time-series period statistics.
     */
    fun renderPerScenarioDeepDive(
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
        val doc = report(title = "Scenario Deep Dive — $scenarioName") {
            snapshotSimulationResults(snapshot)
        }
        return writeAll(doc, outputDir, fileStem("scenario-deepdive", scenarioName), formats)
    }

    /**
     *  Render a cross-scenario box plot for [responseName] in every
     *  selected format.  Uses the substrate's
     *  `multiBoxPlot(dataMap, …)` extension against the observation
     *  map reconstructed from `result.replicationsByItem`.
     */
    fun renderCrossScenarioBoxPlot(
        result: RunResult.BatchCompleted,
        responseName: String,
        outputDir: Path,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        val data = observationsAsMap(result, responseName)
        if (data.isEmpty()) {
            return WriteOutcome(
                written = emptyList(),
                errors = listOf("No scenarios recorded values for response '$responseName'.")
            )
        }
        val doc = report("Cross-Scenario Distributions — $responseName") {
            paragraph(
                "Per-replication distributions of response '$responseName' across " +
                    "${data.size} scenario${if (data.size == 1) "" else "s"}, " +
                    "drawn from per-scenario WithinRepStat averages."
            )
            multiBoxPlot(
                dataMap = data,
                caption = "Cross-Scenario Distributions — $responseName"
            )
        }
        return writeAll(doc, outputDir, fileStem("cross-scenario-boxplot", responseName), formats)
    }

    /**
     *  Render a full Multiple Comparison Analysis report for
     *  [responseName] in every selected format.  Pre-validates the
     *  data (≥2 scenarios, equal rep counts, ≥2 reps) and surfaces
     *  a clear error when it doesn't qualify.
     */
    fun renderMultipleComparison(
        result: RunResult.BatchCompleted,
        responseName: String,
        outputDir: Path,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        val data = observationsAsMap(result, responseName)
        if (data.size < 2) {
            return WriteOutcome(
                written = emptyList(),
                errors = listOf(
                    "Multiple comparison requires at least 2 scenarios with data for " +
                        "response '$responseName' (found ${data.size})."
                )
            )
        }
        val lengths = data.values.map { it.size }.distinct()
        if (lengths.size != 1) {
            return WriteOutcome(
                written = emptyList(),
                errors = listOf(
                    "Multiple comparison requires every scenario to have the same number " +
                        "of replications for response '$responseName' (found counts: ${lengths.sorted()})."
                )
            )
        }
        if (lengths.single() < 2) {
            return WriteOutcome(
                written = emptyList(),
                errors = listOf(
                    "Multiple comparison requires at least 2 replications per scenario " +
                        "(found ${lengths.single()})."
                )
            )
        }
        val mca = MultipleComparisonAnalyzer(data, responseName)
        val doc = report("Multiple Comparison Analysis — $responseName") {
            paragraph(
                "Pairwise comparison of ${data.size} scenarios on response " +
                    "'$responseName' with ${lengths.single()} replications each."
            )
            multipleComparison(
                mca = mca,
                showAltCIPlot = true,
                showBoxPlot = true
            )
        }
        return writeAll(doc, outputDir, fileStem("multiple-comparison", responseName), formats)
    }

    // ── DSL helpers ───────────────────────────────────────────────────────

    /** Across-rep stats table used by the consolidated sweep summary.
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
