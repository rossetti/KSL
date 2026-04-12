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
import ksl.utilities.statistic.WeightedStatistic

/**
 * DSL extension functions on [ReportBuilder] for rendering [WeightedStatistic] instances.
 *
 * [WeightedStatistic] implements [ksl.utilities.statistic.WeightedStatisticIfc], **not**
 * [ksl.utilities.statistic.StatisticIfc]. It therefore cannot flow through
 * [ksl.utilities.io.report.ast.ReportNode.StatTable] and must use the dedicated
 * [ksl.utilities.io.report.ast.ReportNode.WeightedStatTable] node instead.
 *
 * Columns reported for each weighted statistic:
 * Name | Count | Wtd Avg | Unwtd Avg | Wtd Sum | Sum Wts | Wtd SS | Min | Max | Missing
 */

/**
 * Appends a [ksl.utilities.io.report.ast.ReportNode.WeightedStatPropertyTable] — a
 * vertical `Property | Value` table for a **single** [WeightedStatistic].
 *
 * Properties displayed: Count, Weighted Average, Unweighted Average, Weighted Sum,
 * Sum of Weights, Weighted Sum of Squares, Min, Max, Missing.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Resource Utilization") {
 *     weightedStatistic(serverUtilization)
 * }
 * ```
 *
 * @param ws      the weighted statistic to report
 * @param caption optional table caption; defaults to [ws.name][WeightedStatistic.name]
 */
fun ReportBuilder.weightedStatistic(
    ws: WeightedStatistic,
    caption: String? = null
) {
    section(ws.name) {
        weightedStatPropertyTable(ws, caption = caption)
    }
}

/**
 * Appends a single [ksl.utilities.io.report.ast.ReportNode.WeightedStatTable] for a
 * list of [WeightedStatistic] instances.
 *
 * All statistics are combined into one table, making it easy to compare time-weighted
 * utilisation across multiple resources side-by-side.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Resource Utilization Summary") {
 *     section("Time-Weighted Statistics") {
 *         weightedStatistics(model.simulationReporter.acrossReplicationWeightedStatisticsList())
 *     }
 * }
 * ```
 *
 * @param stats   weighted statistics to tabulate; empty list is silently ignored
 * @param caption optional caption displayed above the table
 */
fun ReportBuilder.weightedStatistics(
    stats: List<WeightedStatistic>,
    caption: String? = null
) {
    if (stats.isEmpty()) return
    weightedStatTable(stats, caption = caption)
}
