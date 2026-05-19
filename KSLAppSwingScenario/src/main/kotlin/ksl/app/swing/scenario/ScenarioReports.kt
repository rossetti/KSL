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
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.multiBoxPlot
import ksl.utilities.io.report.extensions.multipleComparison
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.statistic.MultipleComparisonAnalyzer
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
     *  Response names that appear in every scenario's replication
     *  snapshots — the candidates for cross-scenario comparisons.
     *  Returned in sorted order.  Empty when there are fewer than two
     *  scenarios or any scenario has no replication snapshots.
     */
    fun responsesCommonAcrossScenarios(result: RunResult.BatchCompleted): List<String> {
        if (result.replicationsByItem.size < 2) return emptyList()
        val perScenario = result.replicationsByItem.values.map { repsForScenario ->
            repsForScenario.flatMap { rep -> rep.withinRepStats.map { it.stat_name } }.toSet()
        }
        if (perScenario.any { it.isEmpty() }) return emptyList()
        return perScenario.reduce { acc, set -> acc intersect set }.sorted()
    }

    /**
     *  Reconstructs `Map<scenarioName, DoubleArray>` for a single
     *  response by reading `WithinRepStatTableData.average` per
     *  replication, in `rep_id` order, from each scenario's
     *  replication snapshots.  Scenarios that have no observations
     *  for [responseName] are omitted; the returned map keeps
     *  insertion order matching [RunResult.BatchCompleted.replicationsByItem]
     *  iteration.
     *
     *  Direct input to [multiBoxPlot] and the
     *  [MultipleComparisonAnalyzer] constructor.
     */
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

    /**
     *  Response names recorded as within-replication statistics for
     *  [scenarioName].  Drives the second combo of the per-replication
     *  trace picker.  Returned in sorted order.
     */
    fun responsesFor(
        result: RunResult.BatchCompleted,
        scenarioName: String
    ): List<String> {
        val reps = result.replicationsByItem[scenarioName] ?: return emptyList()
        return reps.flatMap { rep -> rep.withinRepStats.map { it.stat_name } }
            .distinct()
            .sorted()
    }

    /**
     *  Render a per-replication trace for one (scenario, response)
     *  pair in every selected format.  For each replication in
     *  `rep_id` order, lists the within-replication average, min,
     *  max, and last_value pulled from the matching
     *  `WithinRepStatTableData` row.  Includes the across-replication
     *  summary as a footer when a matching aggregate exists in the
     *  scenario's [SimulationSnapshot.ExperimentCompleted].
     */
    fun renderReplicationTrace(
        result: RunResult.BatchCompleted,
        scenarioName: String,
        responseName: String,
        outputDir: Path,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        val reps = result.replicationsByItem[scenarioName]
            ?: return WriteOutcome(
                written = emptyList(),
                errors = listOf("No per-replication data captured for scenario '$scenarioName'.")
            )
        val rows = reps
            .sortedBy { it.repId }
            .flatMap { rep -> rep.withinRepStats.filter { it.stat_name == responseName } }
        if (rows.isEmpty()) {
            return WriteOutcome(
                written = emptyList(),
                errors = listOf(
                    "Response '$responseName' was not recorded as a within-replication " +
                        "statistic for scenario '$scenarioName'."
                )
            )
        }
        val summary = result.snapshots
            .firstOrNull { it.simulationRun.run_name == scenarioName }
            ?.acrossRepStats
            ?.firstOrNull { it.stat_name == responseName }

        val doc = report("Replication Trace — $scenarioName / $responseName") {
            paragraph(
                "Per-replication values of response '$responseName' across " +
                    "${rows.size} replication(s) of scenario '$scenarioName'."
            )
            section("Trace") {
                dataTable(
                    headers = listOf("Rep #", "Average", "Min", "Max", "Last Value"),
                    rows = rows.map { stat ->
                        listOf(
                            stat.rep_id.toString(),
                            fmt(stat.average),
                            fmt(stat.minimum),
                            fmt(stat.maximum),
                            fmt(stat.last_value)
                        )
                    }
                )
            }
            if (summary != null) {
                section("Across-Replication Summary") {
                    dataTable(
                        headers = listOf("Statistic", "Value"),
                        rows = listOf(
                            listOf("Count", fmtInt(summary.stat_count)),
                            listOf("Mean", fmt(summary.average)),
                            listOf("Std Dev", fmt(summary.std_dev)),
                            listOf("Half-width", fmt(summary.half_width)),
                            listOf("Min", fmt(summary.minimum)),
                            listOf("Max", fmt(summary.maximum))
                        )
                    )
                }
            }
        }
        return writeAll(
            doc,
            outputDir,
            fileStem("replication-trace", "$scenarioName-$responseName"),
            formats
        )
    }

    private fun fmt(v: Double?, digits: Int = 4): String =
        v?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { "%.${digits}f".format(it) } ?: ""

    private fun fmtInt(v: Double?): String =
        v?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()?.toString() ?: ""

    /**
     *  Render a cross-scenario box plot for [responseName] in every
     *  selected format.  Uses
     *  `ksl.utilities.io.report.extensions.multiBoxPlot(...)` against
     *  the observation map reconstructed from
     *  `result.replicationsByItem`.  Empty observation map (no
     *  scenario has values for the response) yields an error in the
     *  outcome rather than an empty file.
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
     *  [responseName] in every selected format.  Constructs a
     *  [MultipleComparisonAnalyzer] from
     *  `observationsAsMap(result, responseName)` and renders via the
     *  substrate's existing
     *  `ksl.utilities.io.report.extensions.multipleComparison(...)`
     *  extension with alternative-CI plot and response-distributions
     *  box plot enabled.
     *
     *  MCA requires at least two alternatives and at least two
     *  observations per alternative; pre-validates and surfaces a
     *  clear error message when the data doesn't qualify.
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
