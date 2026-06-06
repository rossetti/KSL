/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.TimeSeriesResponseTableData
import ksl.utilities.io.plotting.MultiSeriesObservationsPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for rendering **time-series period
 * data** stored in a [KSLDatabase].
 *
 * A `ksl.modeling.variable.TimeSeriesResponse` divides the simulation horizon
 * into sequential periods and records one value per (replication, period). When
 * those records are persisted, the database holds the raw per-period values in
 * `tblTimeSeriesResponse`.  These extensions render that stored data two ways:
 *
 * - **Per replication** — one line per replication overlaid on a single chart,
 *   showing run-to-run variability of the response over the periods.
 * - **Across replications** — a per-period statistics table (mean, half-width,
 *   CI, min/max) plus the mean trajectory.
 *
 * All per-period statistics are computed directly from the raw rows returned by
 * `KSLDatabase.timeSeriesResponseDataFor`, so these extensions do not depend on
 * whether the across-replication time-series view was populated when the
 * experiment ran.  Plotting reuses the existing [MultiSeriesObservationsPlot]
 * (a connected line-and-point plot); no new plot code is introduced and no HTML
 * is hand-built.
 *
 * This complements the live-object [timeSeriesResponse] extension, which renders
 * the same kind of content directly from an in-memory
 * `ksl.modeling.variable.TimeSeriesResponse` rather than from the database.
 *
 * **Typical usage:**
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toTimeSeriesReport("Experiment 1", "HourlyThroughput").showInBrowser()
 * ```
 */

// ── Per-replication overlay ─────────────────────────────────────────────────────

/**
 * Appends a "Time Series by Replication — [responseName]" section overlaying one
 * line per replication, with the period index on the x-axis and the response
 * value on the y-axis.
 *
 * @param db           the database to query
 * @param expName      the experiment whose time-series rows are used
 * @param responseName the time-series response name to plot
 * @param repIds       optional subset of replication ids to include; `null`
 *                     (default) plots every recorded replication
 * @param showPlot     when `true` (default) include the overlay plot
 */
fun ReportBuilder.dbTimeSeriesPerReplication(
    db: KSLDatabase,
    expName: String,
    responseName: String,
    repIds: Set<Int>? = null,
    showPlot: Boolean = true
) {
    val rows = timeSeriesRowsFor(db, expName, responseName)
    section("Time Series by Replication — $responseName") {
        if (rows.isEmpty()) {
            paragraph(noTimeSeriesDataMessage(expName, responseName))
            return@section
        }
        val byRep = rows.groupBy { it.rep_id }
            .let { grouped -> if (repIds == null) grouped else grouped.filterKeys { it in repIds } }
        if (byRep.isEmpty()) {
            paragraph("No replications matched the requested replication ids for \"$responseName\".")
            return@section
        }
        val seriesMap = byRep.toSortedMap()
            .map { (rep, repRows) ->
                "Rep $rep" to repRows.sortedBy { it.period }.map { it.value ?: Double.NaN }.toDoubleArray()
            }
            .toMap()
        val periods = seriesMap.values.firstOrNull()?.size ?: 0
        paragraph("Replications: ${byRep.size}  |  Periods: $periods")
        if (showPlot) {
            val tsPlot = MultiSeriesObservationsPlot(seriesMap, responseName)
            tsPlot.title = "Time Series by Replication — $responseName"
            tsPlot.xLabel = "Period"
            tsPlot.yLabel = responseName
            plot(tsPlot, caption = "One line per replication — $responseName")
        }
    }
}

// ── Across-replication summary ───────────────────────────────────────────────────

/**
 * Appends a "Time Series Across Replications — [responseName]" section containing
 * a per-period statistics table (Period, Start, End, Count, Mean, Std Dev, Half
 * Width, CI Lower, CI Upper, Min, Max) and a plot of the mean trajectory.
 *
 * The statistics are computed across replications for each period from the raw
 * stored values.
 *
 * @param db              the database to query
 * @param expName         the experiment whose time-series rows are used
 * @param responseName    the time-series response name
 * @param confidenceLevel confidence level for the half-width and CI columns; default 0.95
 * @param showPlot        when `true` (default) include the mean-trajectory plot
 */
fun ReportBuilder.dbTimeSeriesAcrossReplication(
    db: KSLDatabase,
    expName: String,
    responseName: String,
    confidenceLevel: Double = 0.95,
    showPlot: Boolean = true
) {
    val rows = timeSeriesRowsFor(db, expName, responseName)
    section("Time Series Across Replications — $responseName") {
        if (rows.isEmpty()) {
            paragraph(noTimeSeriesDataMessage(expName, responseName))
            return@section
        }
        val byPeriod = rows.groupBy { it.period }.toSortedMap()
        val headers = listOf(
            "Period", "Start", "End", "Count", "Mean", "Std Dev",
            "Half Width", "CI Lower", "CI Upper", "Min", "Max"
        )
        val meanByPeriod = mutableListOf<Double>()
        val tableRows = byPeriod.map { (period, periodRows) ->
            val values = periodRows.mapNotNull { it.value }.toDoubleArray()
            val stat = Statistic("period_$period", values)
            stat.confidenceLevel = confidenceLevel
            meanByPeriod.add(stat.average)
            val halfWidth = stat.halfWidth
            listOf(
                period.toString(),
                fmtTime(periodRows.firstOrNull()?.start_time),
                fmtTime(periodRows.firstOrNull()?.end_time),
                stat.count.toInt().toString(),
                fmtDouble(stat.average),
                fmtDouble(stat.standardDeviation),
                fmtDouble(halfWidth),
                fmtDouble(stat.average - halfWidth),
                fmtDouble(stat.average + halfWidth),
                fmtDouble(stat.min),
                fmtDouble(stat.max)
            )
        }
        dataTable(headers, tableRows, caption = "Across-Replication Statistics by Period: $responseName")
        if (showPlot && meanByPeriod.isNotEmpty()) {
            val avgPlot = MultiSeriesObservationsPlot(mapOf("Average" to meanByPeriod.toDoubleArray()), responseName)
            avgPlot.title = "Mean Trajectory — $responseName"
            avgPlot.xLabel = "Period"
            avgPlot.yLabel = responseName
            plot(avgPlot, caption = "Mean across replications by period — $responseName")
        }
    }
}

// ── All responses for an experiment ──────────────────────────────────────────────

/**
 * Appends one across-replication time-series sub-section per distinct
 * time-series response recorded for [expName].  Emits nothing when the
 * experiment has no stored time-series data.
 *
 * @param db              the database to query
 * @param expName         the experiment whose time-series responses are reported
 * @param confidenceLevel confidence level for the per-period tables; default 0.95
 * @param showPlots       when `true` (default) include the mean-trajectory plots
 */
fun ReportBuilder.dbTimeSeriesResponses(
    db: KSLDatabase,
    expName: String,
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true
) {
    val names = db.timeSeriesResponseDataFor(expName).map { it.stat_name }.distinct()
    if (names.isEmpty()) return
    for (responseName in names) {
        dbTimeSeriesAcrossReplication(db, expName, responseName, confidenceLevel, showPlots)
    }
}

// ── KSLDatabase.toTimeSeriesReport() — zero-code entry point ─────────────────────

/**
 * Builds a [ReportNode.Document] of the stored time-series data for one
 * experiment.
 *
 * When [responseName] is supplied, the document contains the per-replication
 * overlay and the across-replication summary for that response.  When it is
 * `null` (the default), the document contains one across-replication summary per
 * distinct time-series response recorded for the experiment.
 *
 * Zero-code path:
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toTimeSeriesReport("Experiment 1", "HourlyThroughput").showInBrowser()
 * db.toTimeSeriesReport("Experiment 1").writeMarkdown()   // every time-series response
 * ```
 *
 * @param expName         the experiment to report
 * @param responseName    a single time-series response, or `null` for all of them
 * @param title           document title; defaults to "Time Series — <expName>"
 *                        (or "Time Series — <responseName> (<expName>)" for a single response)
 * @param confidenceLevel confidence level for the per-period tables; default 0.95
 * @param showPlots       when `true` (default) include the plots
 * @return the assembled [ReportNode.Document]
 */
fun KSLDatabase.toTimeSeriesReport(
    expName: String,
    responseName: String? = null,
    title: String = if (responseName == null) "Time Series — $expName"
                    else "Time Series — $responseName ($expName)",
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true
): ReportNode.Document = report(title) {
    if (responseName != null) {
        dbTimeSeriesPerReplication(this@toTimeSeriesReport, expName, responseName, showPlot = showPlots)
        dbTimeSeriesAcrossReplication(this@toTimeSeriesReport, expName, responseName, confidenceLevel, showPlots)
    } else {
        dbTimeSeriesResponses(this@toTimeSeriesReport, expName, confidenceLevel, showPlots)
    }
}

// ── Private helpers ──────────────────────────────────────────────────────────────

private fun timeSeriesRowsFor(
    db: KSLDatabase,
    expName: String,
    responseName: String
): List<TimeSeriesResponseTableData> =
    db.timeSeriesResponseDataFor(expName).filter { it.stat_name == responseName }

private fun fmtTime(time: Double?): String =
    if (time == null || time.isNaN()) "—" else "%.4f".format(time)

private fun noTimeSeriesDataMessage(expName: String, responseName: String): String =
    "No time-series data for \"$responseName\" in experiment \"$expName\"."
