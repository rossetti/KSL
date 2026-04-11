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

import ksl.utilities.io.report.dsl.ReportBuilder
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
 * [ksl.utilities.io.report.ast.ReportNode.StatTable] is produced for that one statistic.
 * When [detail] is `true` the table is extended with skewness, kurtosis, lag-1
 * correlation, Von Neumann test statistic, Von Neumann p-value, and missing count.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Service Analysis") {
 *     statistic(serviceTime, detail = true)
 * }
 * ```
 *
 * @param stat            the statistic to report
 * @param confidenceLevel confidence level for the half-width and CI columns; must be in (0, 1)
 * @param detail          false (default) = compact half-width summary;
 *                        true = compact summary + full diagnostic table
 */
fun ReportBuilder.statistic(
    stat: StatisticIfc,
    confidenceLevel: Double = 0.95,
    detail: Boolean = false
) {
    section(stat.name) {
        statTable(listOf(stat), confidenceLevel = confidenceLevel, detail = detail)
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
