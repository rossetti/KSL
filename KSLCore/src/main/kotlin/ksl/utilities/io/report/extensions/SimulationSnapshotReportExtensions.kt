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

package ksl.utilities.io.report.extensions

import kotlinx.datetime.Instant
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.FrequencyTableData
import ksl.utilities.io.dbutil.HistogramTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.dbutil.TimeSeriesResponseTableData
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for rendering immutable simulation
 * lifecycle snapshots.
 *
 * The completed-experiment snapshot is the in-memory result returned by
 * [ksl.app.session.RunResult.Completed]. These functions make that result reportable
 * without requiring a live [ksl.simulation.Model] reference or a [ksl.utilities.io.dbutil.KSLDatabase].
 *
 * The snapshot stores database DTO rows directly. For data that is already aggregated
 * in those rows, such as across-replication statistics, the report renders DTO fields
 * as [ReportNode.DataTable] content. For histogram and frequency rows, this file
 * delegates to the existing database-row report adapters so snapshot and database
 * reports stay structurally consistent.
 */

/**
 * Appends a run-summary section for the simulation run embedded in an
 * [SimulationSnapshot.ExperimentCompleted] snapshot.
 *
 * The snapshot does not include the full experiment configuration record captured by
 * [SimulationSnapshot.ExperimentStarted]. This table therefore reports only the fields
 * available from [SimulationRunTableData].
 */
fun ReportBuilder.snapshotRunSummary(run: SimulationRunTableData) {
    section("Run Summary") {
        val myCompleted = completedReplicationCount(run)
        dataTable(
            headers = listOf("Property", "Value"),
            rows = listOf(
                listOf("Run ID", run.run_id.toString()),
                listOf("Experiment ID", run.exp_id_fk.toString()),
                listOf("Run Name", run.run_name.ifBlank { MISSING }),
                listOf("Requested Replications", run.num_reps.toString()),
                listOf("Starting Replication ID", run.start_rep_id.toString()),
                listOf("Last Replication ID", run.last_rep_id?.toString() ?: MISSING),
                listOf("Completed Replications", myCompleted?.toString() ?: MISSING),
                listOf("Run Start", fmtTimestamp(run.run_start_time_stamp)),
                listOf("Run End", fmtTimestamp(run.run_end_time_stamp)),
                listOf("Elapsed Wall-Clock", fmtDurationMillis(run.run_start_time_stamp, run.run_end_time_stamp)),
                listOf("Run Error", run.run_error_msg?.takeIf { it.isNotBlank() } ?: "None")
            ),
            caption = "Run Summary"
        )
    }
}

/**
 * Appends a completed-snapshot across-replication statistics section.
 *
 * Unlike live-model and database reports, this function cannot rebuild
 * [ksl.utilities.statistic.Statistic] instances because the completed snapshot holds
 * finalized aggregate rows, not raw per-replication observations. It therefore renders
 * the stored fields directly. If [showDiagnostics] is true, a second table displays
 * diagnostic fields that are normally hidden in compact result summaries.
 */
fun ReportBuilder.snapshotAcrossReplicationStatistics(
    stats: List<AcrossRepStatTableData>,
    showDiagnostics: Boolean = false
) {
    section("Across-Replication Statistics") {
        if (stats.isEmpty()) {
            paragraph("No across-replication statistics available.")
            return@section
        }

        val myRows = stats
            .sortedBy { it.stat_name }
            .map { row ->
                listOf(
                    row.stat_name,
                    fmtCount(row.stat_count),
                    fmtNullableDouble(row.average),
                    fmtNullableDouble(row.std_dev),
                    fmtNullableDouble(row.half_width),
                    fmtConfidenceLimit(row.average, row.half_width, subtract = true),
                    fmtConfidenceLimit(row.average, row.half_width, subtract = false),
                    fmtNullableDouble(row.minimum),
                    fmtNullableDouble(row.maximum)
                )
            }

        dataTable(
            headers = listOf("Name", "Count", "Average", "Std Dev", "Half Width", "CI Lower", "CI Upper", "Min", "Max"),
            rows = myRows,
            caption = "Across-Replication Statistics"
        )

        if (showDiagnostics) {
            val myDiagnosticRows = stats
                .sortedBy { it.stat_name }
                .map { row ->
                    listOf(
                        row.stat_name,
                        fmtNullableDouble(row.std_err),
                        fmtNullableDouble(row.conf_level),
                        fmtNullableDouble(row.sum_of_obs),
                        fmtNullableDouble(row.dev_ssq),
                        fmtNullableDouble(row.last_value),
                        fmtNullableDouble(row.kurtosis),
                        fmtNullableDouble(row.skewness),
                        fmtNullableDouble(row.lag1_cov),
                        fmtNullableDouble(row.lag1_corr),
                        fmtNullableDouble(row.von_neumann_lag1_stat),
                        fmtCount(row.num_missing_obs)
                    )
                }

            dataTable(
                headers = listOf(
                    "Name",
                    "Std Error",
                    "Conf Level",
                    "Sum",
                    "Dev SSQ",
                    "Last",
                    "Kurtosis",
                    "Skewness",
                    "Lag-1 Cov",
                    "Lag-1 Corr",
                    "Von Neumann",
                    "Missing"
                ),
                rows = myDiagnosticRows,
                caption = "Across-Replication Diagnostics"
            )
        }
    }
}

/**
 * Appends a single histogram section from snapshot histogram rows.
 */
fun ReportBuilder.snapshotHistogram(
    responseName: String,
    binRows: List<HistogramTableData>,
    caption: String? = null,
    showPlot: Boolean = true
) {
    dbHistogram(responseName, binRows, caption = caption, showPlot = showPlot)
}

/**
 * Appends a "Histograms" section for a completed snapshot.
 */
fun ReportBuilder.snapshotSimulationHistograms(
    snapshot: SimulationSnapshot.ExperimentCompleted,
    showPlot: Boolean = true
) {
    if (snapshot.histograms.isEmpty()) return
    section("Histograms") {
        val myByResponse = snapshot.histograms
            .groupBy { it.response_name }
            .toSortedMap()
        for ((responseName, binRows) in myByResponse) {
            snapshotHistogram(responseName, binRows, showPlot = showPlot)
        }
    }
}

/**
 * Appends a single integer-frequency section from snapshot frequency rows.
 */
fun ReportBuilder.snapshotIntegerFrequency(
    frequencyName: String,
    cellRows: List<FrequencyTableData>,
    caption: String? = null,
    showPlot: Boolean = true
) {
    dbIntegerFrequency(frequencyName, cellRows, caption = caption, showPlot = showPlot)
}

/**
 * Appends a "Frequencies" section for a completed snapshot.
 */
fun ReportBuilder.snapshotSimulationFrequencies(
    snapshot: SimulationSnapshot.ExperimentCompleted,
    showPlot: Boolean = true
) {
    if (snapshot.frequencies.isEmpty()) return
    section("Frequencies") {
        val myByName = snapshot.frequencies
            .groupBy { it.name }
            .toSortedMap()
        for ((frequencyName, cellRows) in myByName) {
            snapshotIntegerFrequency(frequencyName, cellRows, showPlot = showPlot)
        }
    }
}

/**
 * Appends a "Time-Series Responses" section from completed-snapshot time-series rows.
 *
 * The snapshot stores one row per replication and period. This function reconstructs
 * period-level across-replication summaries by grouping rows by response name and period.
 */
fun ReportBuilder.snapshotSimulationTimeSeries(
    snapshot: SimulationSnapshot.ExperimentCompleted,
    confidenceLevel: Double = 0.95
) {
    if (snapshot.timeSeries.isEmpty()) return
    section("Time-Series Responses") {
        val myByName = snapshot.timeSeries
            .groupBy { it.stat_name }
            .toSortedMap()

        for ((statName, rowsForName) in myByName) {
            section(statName) {
                val myRows = timeSeriesRows(rowsForName, statName, confidenceLevel)
                if (myRows.isEmpty()) {
                    paragraph("No time-series period statistics available for \"$statName\".")
                } else {
                    dataTable(
                        headers = listOf(
                            "Period",
                            "Start",
                            "End",
                            "Count",
                            "Mean",
                            "Std Dev",
                            "Half Width",
                            "CI Lower",
                            "CI Upper",
                            "Min",
                            "Max"
                        ),
                        rows = myRows,
                        caption = "Across-Replication Statistics by Period: $statName"
                    )
                }
            }
        }
    }
}

/**
 * Appends the standard completed-snapshot report sections in canonical order.
 */
fun ReportBuilder.snapshotSimulationResults(
    snapshot: SimulationSnapshot.ExperimentCompleted,
    showPlots: Boolean = true,
    showTimeSeries: Boolean = true,
    showDiagnostics: Boolean = false,
    timeSeriesConfidenceLevel: Double = 0.95
) {
    snapshotRunSummary(snapshot.simulationRun)
    snapshotAcrossReplicationStatistics(snapshot.acrossRepStats, showDiagnostics = showDiagnostics)
    snapshotSimulationHistograms(snapshot, showPlot = showPlots)
    snapshotSimulationFrequencies(snapshot, showPlot = showPlots)
    if (showTimeSeries) {
        snapshotSimulationTimeSeries(snapshot, confidenceLevel = timeSeriesConfidenceLevel)
    }
}

/**
 * Builds a report document for a completed simulation snapshot.
 *
 * The default content is equivalent to [snapshotSimulationResults]. Supplying [block]
 * replaces the default content; call [snapshotSimulationResults] from the block when
 * composing the standard sections with custom commentary.
 *
 * @param outputDirectory optional KSL output directory used as the default target for
 *        convenience render calls such as `writeMarkdown()` and `showInBrowser()`
 */
fun SimulationSnapshot.ExperimentCompleted.toReport(
    title: String = defaultSnapshotTitle(this),
    showPlots: Boolean = true,
    showTimeSeries: Boolean = true,
    showDiagnostics: Boolean = false,
    timeSeriesConfidenceLevel: Double = 0.95,
    outputDirectory: OutputDirectory? = null,
    block: ReportBuilder.() -> Unit = {
        snapshotSimulationResults(
            this@toReport,
            showPlots = showPlots,
            showTimeSeries = showTimeSeries,
            showDiagnostics = showDiagnostics,
            timeSeriesConfidenceLevel = timeSeriesConfidenceLevel
        )
    }
): ReportNode.Document = report(
    title = title,
    outputDir = outputDirectory?.outDir,
    plotDir = outputDirectory?.plotDir,
    block = block
)

private const val MISSING = "\u2014"

private fun defaultSnapshotTitle(snapshot: SimulationSnapshot.ExperimentCompleted): String {
    val myRunName = snapshot.simulationRun.run_name
    return if (myRunName.isBlank()) {
        "Simulation Snapshot Report"
    } else {
        "Simulation Snapshot - $myRunName"
    }
}

private fun completedReplicationCount(run: SimulationRunTableData): Int? {
    val myLast = run.last_rep_id ?: return null
    if (run.start_rep_id < 0 || myLast < run.start_rep_id) return null
    return myLast - run.start_rep_id + 1
}

private fun fmtNullableDouble(value: Double?): String {
    if (value == null || value.isNaN() || value.isInfinite()) return MISSING
    return fmtDouble(value)
}

private fun fmtNullableLimit(value: Double?): String {
    if (value == null || value.isNaN()) return MISSING
    return fmtLimit(value)
}

private fun fmtCount(value: Double?): String {
    if (value == null || value.isNaN() || value.isInfinite()) return MISSING
    return value.toLong().toString()
}

private fun fmtTimestamp(value: Long?): String {
    if (value == null) return MISSING
    return Instant.fromEpochMilliseconds(value).toString()
}

private fun fmtDurationMillis(start: Long?, end: Long?): String {
    if (start == null || end == null) return MISSING
    return "${end - start} ms"
}

private fun fmtConfidenceLimit(average: Double?, halfWidth: Double?, subtract: Boolean): String {
    if (average == null || halfWidth == null) return MISSING
    if (average.isNaN() || average.isInfinite() || halfWidth.isNaN() || halfWidth.isInfinite()) return MISSING
    val myLimit = if (subtract) average - halfWidth else average + halfWidth
    return fmtDouble(myLimit)
}

private fun timeSeriesRows(
    rows: List<TimeSeriesResponseTableData>,
    statName: String,
    confidenceLevel: Double
): List<List<String>> {
    return rows
        .groupBy { it.period }
        .toSortedMap()
        .map { (period, periodRows) ->
            val myValues = periodRows
                .mapNotNull { it.value }
                .filter { !it.isNaN() && !it.isInfinite() }
                .toDoubleArray()

            val myStart = periodRows.firstNotNullOfOrNull { it.start_time }
            val myEnd = periodRows.firstNotNullOfOrNull { it.end_time }

            if (myValues.isEmpty()) {
                listOf(
                    period.toString(),
                    fmtNullableLimit(myStart),
                    fmtNullableLimit(myEnd),
                    "0",
                    MISSING,
                    MISSING,
                    MISSING,
                    MISSING,
                    MISSING,
                    MISSING,
                    MISSING
                )
            } else {
                val myStat = Statistic(statName, myValues)
                myStat.confidenceLevel = confidenceLevel
                val myHalfWidth = myStat.halfWidth
                listOf(
                    period.toString(),
                    fmtNullableLimit(myStart),
                    fmtNullableLimit(myEnd),
                    fmtCount(myStat.count),
                    fmtNullableDouble(myStat.average),
                    fmtNullableDouble(myStat.standardDeviation),
                    fmtNullableDouble(myHalfWidth),
                    fmtNullableDouble(myStat.average - myHalfWidth),
                    fmtNullableDouble(myStat.average + myHalfWidth),
                    fmtNullableDouble(myStat.min),
                    fmtNullableDouble(myStat.max)
                )
            }
        }
}
