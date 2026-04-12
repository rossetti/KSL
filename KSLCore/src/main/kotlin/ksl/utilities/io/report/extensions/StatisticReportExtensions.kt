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

import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.StatisticIfc

/**
 * DSL extension functions on [ReportBuilder] for rendering [StatisticIfc] instances.
 *
 * These cover any class that implements [StatisticIfc] — including
 * [ksl.utilities.statistic.Statistic], [ksl.utilities.statistic.BatchStatistic], and
 * [ksl.utilities.statistic.Histogram] — without requiring a specific subtype.
 *
 * For domain-specific content beyond [StatisticIfc] (e.g., batch configuration parameters
 * or histogram bin tables), see the dedicated extension files:
 * - [BatchStatisticReportExtensions]
 * - [HistogramReportExtensions]
 */

/**
 * Appends a self-contained section for a single [StatisticIfc] instance.
 *
 * The section title is [stat.name][StatisticIfc.name]. Inside the section a
 * [ksl.utilities.io.report.ast.ReportNode.StatPropertyTable] is produced — a vertical
 * `Property | Value` table showing **all 18 statistical properties** of this variable:
 * Count, Average, Std Dev, Std Error, Half-width, Confidence Level, CI Lower, CI Upper,
 * Min, Max, Sum, Variance, Dev Sum of Sq, Kurtosis, Skewness, Lag-1 Cov, Lag-1 Corr,
 * Von Neumann Test Statistic, and Missing.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Service Analysis") {
 *     statistic(serviceTime)
 * }
 * ```
 *
 * @param stat            the statistic to report
 * @param confidenceLevel confidence level for the half-width and CI rows; must be in (0, 1)
 */
fun ReportBuilder.statistic(
    stat: StatisticIfc,
    confidenceLevel: Double = 0.95
) {
    section(stat.name) {
        statPropertyTable(stat, confidenceLevel = confidenceLevel)
    }
}

/**
 * Appends a single [ksl.utilities.io.report.ast.ReportNode.StatTable] for a list of
 * [StatisticIfc] instances — the most common reporting case for across-replication
 * summary statistics.
 *
 * Unlike [statistic], this does **not** wrap the table in a section; call it directly
 * inside a `section {}` block when grouping is already handled by the caller.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Simulation Results") {
 *     section("Across-Replication Statistics") {
 *         statistics(model.simulationReporter.acrossReplicationStatisticsList())
 *     }
 * }
 * ```
 *
 * @param stats           statistics to tabulate; empty list is silently ignored
 * @param caption         optional caption displayed above the table
 * @param confidenceLevel confidence level for half-width and CI columns; must be in (0, 1)
 * @param detail          false (default) = compact summary; true = compact + diagnostic table
 */
fun ReportBuilder.statistics(
    stats: List<StatisticIfc>,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    detail: Boolean = false
) {
    if (stats.isEmpty()) return
    statTable(stats, caption = caption, confidenceLevel = confidenceLevel, detail = detail)
}

/**
 * Appends a compact single-row [ksl.utilities.io.report.ast.ReportNode.StatTable] for
 * [stat] without wrapping it in a section.
 *
 * Unlike [statistic], this produces a horizontal one-row summary table (count, mean,
 * std dev, std error, half-width, CI lower/upper, min, max) rather than a full
 * vertical property sheet. It is the preferred form when:
 * - you want one row per statistic inside an existing section, or
 * - you want to show a single statistic alongside other compact tables.
 *
 * Usage:
 * ```kotlin
 * section("Response Times") {
 *     statisticCompact(serviceTime)
 *     statisticCompact(queueWait)
 * }
 * ```
 *
 * @param stat            the statistic to show
 * @param caption         optional caption above the table
 * @param confidenceLevel confidence level for half-width and CI columns; must be in (0, 1)
 * @param detail          true = also append a diagnostic table (skewness, kurtosis, etc.)
 */
fun ReportBuilder.statisticCompact(
    stat: StatisticIfc,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    detail: Boolean = false
) {
    statTable(listOf(stat), caption = caption, confidenceLevel = confidenceLevel, detail = detail)
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] whose default content is a full property-sheet
 * section for this statistic (identical to calling [statistic] inside a `report {}` block).
 *
 * The [block] parameter is optional; omitting it produces the canonical report.
 * Supplying a block **replaces** the default content entirely — call [statistic]
 * inside the block to include the standard section alongside custom content:
 * ```kotlin
 * myStat.toReport {
 *     statistic(this@toReport)          // standard section
 *     paragraph("Custom commentary.")  // appended after
 * }
 * ```
 *
 * @param title           document title; defaults to [StatisticIfc.name]
 * @param confidenceLevel confidence level for half-width and CI rows; must be in (0, 1)
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun StatisticIfc.toReport(
    title: String = name,
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = { statistic(this@toReport, confidenceLevel) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] whose default content is a compact half-width
 * summary [ksl.utilities.io.report.ast.ReportNode.StatTable] for all statistics
 * held by this reporter.
 *
 * The [block] parameter is optional; omitting it produces the canonical compact table.
 * Supplying a block replaces the default:
 * ```kotlin
 * myReporter.toReport("Service Time Analysis") {
 *     statistics(this@toReport.statistics)   // standard compact table
 *     paragraph("Additional notes.")
 * }
 * ```
 *
 * @param title           document title; defaults to [StatisticReporter.reportTitle]
 *                        when set, otherwise `"Statistical Report"`
 * @param confidenceLevel confidence level for half-width and CI columns; must be in (0, 1)
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun StatisticReporter.toReport(
    title: String = reportTitle ?: "Statistical Report",
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        statistics(this@toReport.statistics, confidenceLevel = confidenceLevel)
    }
): ReportNode.Document = report(title, block)
