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

package ksl.utilities.io.report.renderer

import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.visitor.AbstractReportVisitor
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * A [ReportVisitor] that renders a [ReportNode] tree to plain text.
 *
 * This is the simplest renderer and serves as the end-to-end validation of the
 * visitor traversal pattern. It delegates all statistical table formatting to
 * [StatisticReporter], preserving the existing KSL console output experience.
 *
 * **Node rendering:**
 * - [ReportNode.Document]       — title underlined with `=`
 * - [ReportNode.Section]        — section title underlined with `-`, indented by depth
 * - [ReportNode.Heading]        — heading text prefixed with `#` × level
 * - [ReportNode.Paragraph]      — text followed by a blank line
 * - [ReportNode.StatTable]      — delegates to [StatisticReporter.halfWidthSummaryReport]
 *                                 (detail=false) or [StatisticReporter.summaryReport]
 *                                 followed by the full CSV statistics (detail=true)
 * - [ReportNode.WeightedStatTable] — key/value table of all [ksl.utilities.statistic.WeightedStatistic] properties
 * - [ReportNode.DataTable]      — column-padded text table
 * - [ReportNode.PlotNode]       — saves the plot to [RenderContext.plotDir] and writes
 *                                 a `[Plot saved: path]` line
 * - [ReportNode.RawText]        — verbatim content, indented two spaces
 * - [ReportNode.PageBreak]      — a line of 80 `─` characters
 *
 * All mutable state uses the `my` prefix per KSL coding conventions.
 *
 * @param ctx the render context supplying output paths and formatting preferences
 */
class TextReportRenderer(private val ctx: RenderContext = RenderContext()) : AbstractReportVisitor() {

    private val myOutput: StringBuilder = StringBuilder()
    private var myDepth: Int = 0
    private var mySectionPlotCount: Int = 0

    // ── ReportVisitor implementation ────────────────────────────────────────────

    override fun enterDocument(node: ReportNode.Document) {
        val myUnderline = "=".repeat(node.title.length.coerceAtMost(120))
        myOutput.appendLine(node.title)
        myOutput.appendLine(myUnderline)
        myOutput.appendLine()
    }

    override fun exitDocument(node: ReportNode.Document) {
        myOutput.appendLine()
    }

    override fun enterSection(node: ReportNode.Section) {
        myDepth++
        mySectionPlotCount = 0
        if (!node.title.isNullOrBlank()) {
            val myIndent = "  ".repeat((myDepth - 1).coerceAtLeast(0))
            val myUnderline = myIndent + "-".repeat(node.title.length.coerceAtMost(100))
            myOutput.appendLine()
            myOutput.appendLine("$myIndent${node.title}")
            myOutput.appendLine(myUnderline)
        }
    }

    override fun exitSection(node: ReportNode.Section) {
        myDepth--
        mySectionPlotCount = 0
    }

    override fun visit(node: ReportNode.Heading) {
        val myPrefix = "#".repeat(node.level)
        myOutput.appendLine()
        myOutput.appendLine("$myPrefix ${node.text}")
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.Paragraph) {
        myOutput.appendLine(node.text)
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.StatPropertyTable) {
        val myCaption = node.caption ?: node.stat.name
        myOutput.appendLine(myCaption)
        val myCI = node.stat.confidenceInterval(node.confidenceLevel)
        val myHW = node.stat.halfWidth(node.confidenceLevel)
        val myRows = listOf(
            listOf("Count",              ctx.fmtProperty(node.stat.count)),
            listOf("Average",            ctx.fmtProperty(node.stat.average)),
            listOf("Std Dev",            ctx.fmtProperty(node.stat.standardDeviation)),
            listOf("Std Error",          ctx.fmtProperty(node.stat.standardError)),
            listOf("Half-width",         ctx.fmtProperty(myHW)),
            listOf("Confidence Level",   ctx.fmtProperty(node.confidenceLevel)),
            listOf("CI Lower",           ctx.fmtProperty(myCI.lowerLimit)),
            listOf("CI Upper",           ctx.fmtProperty(myCI.upperLimit)),
            listOf("Min",                ctx.fmtProperty(node.stat.min)),
            listOf("Max",                ctx.fmtProperty(node.stat.max)),
            listOf("Sum",                ctx.fmtProperty(node.stat.sum)),
            listOf("Variance",           ctx.fmtProperty(node.stat.variance)),
            listOf("Dev Sum of Sq",      ctx.fmtProperty(node.stat.deviationSumOfSquares)),
            listOf("Kurtosis",           ctx.fmtProperty(node.stat.kurtosis)),
            listOf("Skewness",           ctx.fmtProperty(node.stat.skewness)),
            listOf("Lag-1 Covariance",   ctx.fmtProperty(node.stat.lag1Covariance)),
            listOf("Lag-1 Correlation",  ctx.fmtProperty(node.stat.lag1Correlation)),
            listOf("Von Neumann Stat",   ctx.fmtProperty(node.stat.vonNeumannLag1TestStatistic)),
            listOf("Missing",            ctx.fmtProperty(node.stat.numberMissing))
        )
        myOutput.append(formatTextTable(listOf("Property", "Value"), myRows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.StatTable) {
        if (node.stats.isEmpty()) return
        if (node.caption != null) {
            myOutput.appendLine(node.caption)
        }
        val myRows = compactStatRows(node.stats, node.confidenceLevel, ctx::fmt)
        myOutput.append(formatTextTable(COMPACT_STAT_HEADERS, myRows))
        myOutput.appendLine()
        if (node.detail) {
            myOutput.appendLine("Diagnostic Statistics")
            val myDiagRows = diagnosticStatRows(node.stats, ctx::fmt)
            myOutput.append(formatTextTable(DIAGNOSTIC_STAT_HEADERS, myDiagRows))
            myOutput.appendLine()
        }
    }

    override fun visit(node: ReportNode.WeightedStatPropertyTable) {
        val myCaption = node.caption ?: node.stat.name
        myOutput.appendLine(myCaption)
        val myRows = listOf(
            listOf("Count",                   ctx.fmtProperty(node.stat.count)),
            listOf("Weighted Average",         ctx.fmtProperty(node.stat.weightedAverage)),
            listOf("Unweighted Average",       ctx.fmtProperty(node.stat.unWeightedAverage)),
            listOf("Weighted Sum",             ctx.fmtProperty(node.stat.weightedSum)),
            listOf("Sum of Weights",           ctx.fmtProperty(node.stat.sumOfWeights)),
            listOf("Weighted Sum of Squares",  ctx.fmtProperty(node.stat.weightedSumOfSquares)),
            listOf("Min",                      ctx.fmtProperty(node.stat.min)),
            listOf("Max",                      ctx.fmtProperty(node.stat.max)),
            listOf("Missing",                  ctx.fmtProperty(node.stat.numberMissing))
        )
        myOutput.append(formatTextTable(listOf("Property", "Value"), myRows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.WeightedStatTable) {
        if (node.stats.isEmpty()) return
        if (node.caption != null) {
            myOutput.appendLine(node.caption)
        }
        // Header
        val myHeaders = listOf(
            "Name", "Count", "Wtd Avg", "Unwtd Avg",
            "Wtd Sum", "Sum Wts", "Wtd SS", "Min", "Max", "Missing"
        )
        val myRows = node.stats.map { ws ->
            listOf(
                ws.name,
                ctx.fmt(ws.count),
                ctx.fmt(ws.weightedAverage),
                ctx.fmt(ws.unWeightedAverage),
                ctx.fmt(ws.weightedSum),
                ctx.fmt(ws.sumOfWeights),
                ctx.fmt(ws.weightedSumOfSquares),
                ctx.fmt(ws.min),
                ctx.fmt(ws.max),
                ctx.fmt(ws.numberMissing)
            )
        }
        myOutput.append(formatTextTable(myHeaders, myRows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.DataTable) {
        if (node.caption != null) {
            myOutput.appendLine(node.caption)
        }
        myOutput.append(formatTextTable(node.headers, node.rows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.PlotNode) {
        if (mySectionPlotCount >= ctx.maxPlotsPerSection) {
            myOutput.appendLine("[Plot omitted: section plot limit (${ctx.maxPlotsPerSection}) reached]")
            return
        }
        val myCaption = node.caption ?: node.plot.title.ifBlank { "plot" }
        val mySafeName = myCaption.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(60)
        val myFile = node.plot.saveToFile(mySafeName, ctx.plotDir)
        myOutput.appendLine("[Plot saved: ${myFile.absolutePath}]")
        if (node.caption != null) {
            myOutput.appendLine("  Caption: ${node.caption}")
        }
        myOutput.appendLine()
        mySectionPlotCount++
    }

    override fun visit(node: ReportNode.RawText) {
        // Indent each line by two spaces for visual separation
        node.content.lines().forEach { myOutput.appendLine("  $it") }
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.PageBreak) {
        myOutput.appendLine()
        myOutput.appendLine("─".repeat(80))
        myOutput.appendLine()
    }

    // ── Output ──────────────────────────────────────────────────────────────────

    /**
     * Returns the accumulated plain-text report as a [String].
     */
    fun result(): String = myOutput.toString()

    /**
     * Writes the accumulated report to the given [PrintWriter].
     */
    fun writeTo(out: PrintWriter) {
        out.print(myOutput.toString())
        out.flush()
    }

    /**
     * Writes the accumulated report to a file at [path] and returns a [File] reference.
     */
    fun writeTo(path: Path): File {
        val myFile = path.toFile()
        myFile.parentFile?.mkdirs()
        myFile.writeText(myOutput.toString())
        return myFile
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Formats a table as padded plain text. Column widths are determined by the
     * maximum string length in each column (header included).
     */
    private fun formatTextTable(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty()) return ""
        val myColCount = headers.size
        val myWidths = IntArray(myColCount) { col ->
            val myMaxData = rows.maxOfOrNull { row ->
                if (col < row.size) row[col].length else 0
            } ?: 0
            maxOf(headers[col].length, myMaxData)
        }
        val mySb = StringBuilder()
        val myHline = myWidths.joinToString("-+-", prefix = "-", postfix = "-") {
            "-".repeat(it)
        }
        // Header row
        val myHeaderRow = headers.mapIndexed { i, h -> h.padEnd(myWidths[i]) }
            .joinToString(" | ", prefix = " ", postfix = " ")
        mySb.appendLine(myHline)
        mySb.appendLine(myHeaderRow)
        mySb.appendLine(myHline)
        // Data rows
        for (myRow in rows) {
            val myFormattedRow = myRow.mapIndexed { i, cell ->
                val myWidth = if (i < myWidths.size) myWidths[i] else cell.length
                // Right-align if the cell looks numeric, left-align otherwise
                if (cell.toDoubleOrNull() != null || cell == "—") {
                    cell.padStart(myWidth)
                } else {
                    cell.padEnd(myWidth)
                }
            }.joinToString(" | ", prefix = " ", postfix = " ")
            mySb.appendLine(myFormattedRow)
        }
        mySb.appendLine(myHline)
        return mySb.toString()
    }
}
