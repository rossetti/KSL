/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.modeling.variable.TimeSeriesResponse
import ksl.utilities.io.report.dsl.ReportBuilder

/**
 * DSL extension functions on [ReportBuilder] for rendering [TimeSeriesResponse] instances.
 *
 * [TimeSeriesResponse] is a [ksl.simulation.ModelElement] that divides the simulation
 * horizon into sequential periods and records per-period observations for a set of
 * [ksl.modeling.variable.ResponseCIfc] and [ksl.modeling.variable.CounterCIfc]
 * instances. It is independent of the simulation [ksl.simulation.Model] and may be
 * used in any context where time-series period reporting is needed.
 *
 * **Across-replication statistics** are only available when
 * [TimeSeriesResponse.acrossRepStatisticsOption] was set to `true` before the
 * experiment ran. If it was `false`, a `Paragraph` notice is emitted in place of
 * the per-period table for each response/counter.
 */

/**
 * Appends a self-contained section that reports the configuration and per-period
 * across-replication statistics for a [TimeSeriesResponse].
 *
 * **Produces (inside a section titled [ts.name][TimeSeriesResponse.name]):**
 * 1. A [ksl.utilities.io.report.ast.ReportNode.Paragraph] summarising periods,
 *    period length, start time, response count, and counter count.
 * 2. For each tracked [ksl.modeling.variable.ResponseCIfc], a sub-section containing:
 *    - A `DataTable` ("Across-Replication Statistics by Period") with columns
 *      `Period | Start | End | Count | Mean | Std Dev | Half Width | CI Lower | CI Upper | Min | Max`
 *      sorted by period number.
 *    - A notice `Paragraph` if `acrossRepStatisticsOption` was `false` or no data
 *      was collected.
 * 3. For each tracked [ksl.modeling.variable.CounterCIfc], the same sub-section
 *    structure as for responses.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Hourly Throughput") {
 *     timeSeriesResponse(hourlyTs, confidenceLevel = 0.90)
 * }
 * ```
 *
 * @param ts              the time series response to report
 * @param confidenceLevel confidence level for the per-period half-width and CI columns;
 *                        must be in (0, 1)
 */
fun ReportBuilder.timeSeriesResponse(
    ts: TimeSeriesResponse,
    confidenceLevel: Double = 0.95
) {
    section(ts.name) {
        // ── Configuration overview ────────────────────────────────────────────
        paragraph(
            "Periods: ${ts.numPeriodsToCollect}  |  " +
            "Period length: ${ts.periodLength}  |  " +
            "Start time: ${ts.defaultStartTime}  |  " +
            "Responses: ${ts.responses.size}  |  " +
            "Counters: ${ts.counters.size}  |  " +
            "Across-rep stats: ${ts.acrossRepStatisticsOption}"
        )

        // ── Per-response sub-sections ─────────────────────────────────────────
        for (response in ts.responses) {
            section(response.name) {
                val myStats = ts.acrossReplicationStatisticsByPeriod(response)
                if (myStats.isEmpty()) {
                    paragraph(
                        "No across-replication statistics available for \"${response.name}\". " +
                        "Set acrossRepStatisticsOption = true before running the experiment."
                    )
                } else {
                    val myHeaders = listOf(
                        "Period", "Start", "End",
                        "Count", "Mean", "Std Dev",
                        "Half Width", "CI Lower", "CI Upper",
                        "Min", "Max"
                    )
                    val myRows = myStats.entries
                        .sortedBy { it.key }
                        .map { (period, stat) ->
                            stat.confidenceLevel = confidenceLevel
                            val myStart = ts.defaultStartTime + (period - 1) * ts.periodLength
                            val myEnd   = myStart + ts.periodLength
                            listOf(
                                period.toString(),
                                "%.4f".format(myStart),
                                "%.4f".format(myEnd),
                                stat.count.toInt().toString(),
                                fmtDouble(stat.average),
                                fmtDouble(stat.standardDeviation),
                                fmtDouble(stat.halfWidth),
                                fmtDouble(stat.average - stat.halfWidth),
                                fmtDouble(stat.average + stat.halfWidth),
                                fmtDouble(stat.min),
                                fmtDouble(stat.max)
                            )
                        }
                    dataTable(
                        myHeaders,
                        myRows,
                        caption = "Across-Replication Statistics by Period: ${response.name}"
                    )
                }
            }
        }

        // ── Per-counter sub-sections ──────────────────────────────────────────
        for (counter in ts.counters) {
            section(counter.name) {
                val myStats = ts.acrossReplicationStatisticsByPeriod(counter)
                if (myStats.isEmpty()) {
                    paragraph(
                        "No across-replication statistics available for \"${counter.name}\". " +
                        "Set acrossRepStatisticsOption = true before running the experiment."
                    )
                } else {
                    val myHeaders = listOf(
                        "Period", "Start", "End",
                        "Count", "Mean", "Std Dev",
                        "Half Width", "CI Lower", "CI Upper",
                        "Min", "Max"
                    )
                    val myRows = myStats.entries
                        .sortedBy { it.key }
                        .map { (period, stat) ->
                            stat.confidenceLevel = confidenceLevel
                            val myStart = ts.defaultStartTime + (period - 1) * ts.periodLength
                            val myEnd   = myStart + ts.periodLength
                            listOf(
                                period.toString(),
                                "%.4f".format(myStart),
                                "%.4f".format(myEnd),
                                stat.count.toInt().toString(),
                                fmtDouble(stat.average),
                                fmtDouble(stat.standardDeviation),
                                fmtDouble(stat.halfWidth),
                                fmtDouble(stat.average - stat.halfWidth),
                                fmtDouble(stat.average + stat.halfWidth),
                                fmtDouble(stat.min),
                                fmtDouble(stat.max)
                            )
                        }
                    dataTable(
                        myHeaders,
                        myRows,
                        caption = "Across-Replication Statistics by Period: ${counter.name}"
                    )
                }
            }
        }
    }
}

