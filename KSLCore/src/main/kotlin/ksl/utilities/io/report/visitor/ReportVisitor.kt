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

package ksl.utilities.io.report.visitor

import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.statistic.StatisticIfc

/**
 * Visitor interface for the KSL report AST. Each method corresponds to exactly one
 * [ReportNode] subtype, providing compile-time assurance that every renderer handles
 * every node type.
 *
 * Structural nodes ([ReportNode.Document], [ReportNode.Section]) have paired
 * `enter`/`exit` methods so renderers can emit opening and closing markup around
 * their children. Leaf nodes have a single `visit` method.
 *
 * Implement this interface directly for renderers that must handle all node types
 * explicitly. Extend [AbstractReportVisitor] for renderers that only need to handle
 * a subset — all methods default to no-ops in the abstract base.
 */
interface ReportVisitor {
    fun enterDocument(node: ReportNode.Document)
    fun exitDocument(node: ReportNode.Document)
    fun enterSection(node: ReportNode.Section)
    fun exitSection(node: ReportNode.Section)
    fun visit(node: ReportNode.Heading)
    fun visit(node: ReportNode.Paragraph)
    fun visit(node: ReportNode.StatPropertyTable)
    fun visit(node: ReportNode.StatTable)
    fun visit(node: ReportNode.WeightedStatPropertyTable)
    fun visit(node: ReportNode.WeightedStatTable)
    fun visit(node: ReportNode.DataTable)
    fun visit(node: ReportNode.PlotNode)
    fun visit(node: ReportNode.RawText)
    fun visit(node: ReportNode.PageBreak)
}

/**
 * Convenience base class with no-op implementations of all [ReportVisitor] methods.
 * Extend this when only a subset of node types needs custom handling.
 *
 * The [companion object][Companion] exposes format-agnostic helpers for building
 * [ReportNode.StatTable] row data directly from [StatisticIfc] — avoiding any
 * DataFrame pipeline in the renderer layer and ensuring all four renderers produce
 * an identical, consistently-columned compact and diagnostic view.
 */
abstract class AbstractReportVisitor : ReportVisitor {
    override fun enterDocument(node: ReportNode.Document)              {}
    override fun exitDocument(node: ReportNode.Document)               {}
    override fun enterSection(node: ReportNode.Section)                {}
    override fun exitSection(node: ReportNode.Section)                 {}
    override fun visit(node: ReportNode.Heading)                       {}
    override fun visit(node: ReportNode.Paragraph)                     {}
    override fun visit(node: ReportNode.StatPropertyTable)             {}
    override fun visit(node: ReportNode.StatTable)                     {}
    override fun visit(node: ReportNode.WeightedStatPropertyTable)     {}
    override fun visit(node: ReportNode.WeightedStatTable)             {}
    override fun visit(node: ReportNode.DataTable)                     {}
    override fun visit(node: ReportNode.PlotNode)                      {}
    override fun visit(node: ReportNode.RawText)                       {}
    override fun visit(node: ReportNode.PageBreak)                     {}

    companion object {

        /**
         * Column headers for the compact [ReportNode.StatTable] view.
         *
         * Columns: Name, Count, Average, Std Dev, Std Error, Half-Width,
         * Conf Level, CI Lower, CI Upper, Min, Max.
         */
        val COMPACT_STAT_HEADERS: List<String> = listOf(
            "Name", "Count", "Average", "Std Dev", "Std Error",
            "Half-Width", "Conf Level", "CI Lower", "CI Upper", "Min", "Max"
        )

        /**
         * Column headers for the diagnostic extension table produced when
         * [ReportNode.StatTable.detail] is `true`.
         *
         * Columns: Name, Skewness, Kurtosis, Lag-1 Corr, VN Statistic, VN p-value, Missing.
         */
        val DIAGNOSTIC_STAT_HEADERS: List<String> = listOf(
            "Name", "Skewness", "Kurtosis", "Lag-1 Corr",
            "VN Statistic", "VN p-value", "Missing"
        )

        /**
         * Builds the compact row data for a [ReportNode.StatTable].
         *
         * Each element of the returned list corresponds to one statistic and aligns
         * column-for-column with [COMPACT_STAT_HEADERS].  The [confidenceLevel] parameter
         * is used to compute [StatisticIfc.halfWidth] and [StatisticIfc.confidenceInterval]
         * without mutating any state on the statistic object.
         *
         * @param stats           statistics to tabulate
         * @param confidenceLevel confidence level for half-width and CI columns
         * @param fmt             renderer-supplied formatting function for [Double] values
         *                        (typically [ksl.utilities.io.report.renderer.RenderContext.fmt])
         * @return one row per statistic; each row aligns with [COMPACT_STAT_HEADERS]
         */
        fun compactStatRows(
            stats: List<StatisticIfc>,
            confidenceLevel: Double,
            fmt: (Double) -> String
        ): List<List<String>> = stats.map { stat ->
            val myCI = stat.confidenceInterval(confidenceLevel)
            val myHW = stat.halfWidth(confidenceLevel)
            listOf(
                stat.name,
                fmt(stat.count),
                fmt(stat.average),
                fmt(stat.standardDeviation),
                fmt(stat.standardError),
                fmt(myHW),
                fmt(confidenceLevel),
                fmt(myCI.lowerLimit),
                fmt(myCI.upperLimit),
                fmt(stat.min),
                fmt(stat.max)
            )
        }

        /**
         * Builds the diagnostic row data for the extended [ReportNode.StatTable] view
         * (appended when [ReportNode.StatTable.detail] is `true`).
         *
         * Each element aligns column-for-column with [DIAGNOSTIC_STAT_HEADERS].
         *
         * @param stats statistics to tabulate
         * @param fmt   renderer-supplied formatting function for [Double] values
         * @return one row per statistic; each row aligns with [DIAGNOSTIC_STAT_HEADERS]
         */
        fun diagnosticStatRows(
            stats: List<StatisticIfc>,
            fmt: (Double) -> String
        ): List<List<String>> = stats.map { stat ->
            listOf(
                stat.name,
                fmt(stat.skewness),
                fmt(stat.kurtosis),
                fmt(stat.lag1Correlation),
                fmt(stat.vonNeumannLag1TestStatistic),
                fmt(stat.vonNeumannLag1TestStatisticPValue),
                fmt(stat.numberMissing)
            )
        }
    }
}
