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

import ksl.utilities.io.MarkDown
import ksl.utilities.io.plotting.PlotIfc
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.visitor.AbstractReportVisitor
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * A `ReportVisitor` that renders a [ReportNode] tree to GitHub-flavoured Markdown.
 *
 * Delegates all statistical table formatting to `StatisticReporter` (reusing the
 * existing half-width and summary report methods). Uses [MarkDown.Table] for
 * [ReportNode.DataTable] and [ReportNode.WeightedStatTable] nodes.
 *
 * **Section depth tracking:** [ReportNode.Section] nodes increment an internal depth
 * counter on enter and decrement on exit. Heading levels are promoted automatically:
 * - Document title → `#`
 * - Depth 1 sections → `##`
 * - Depth 2 sections → `###`
 * - Depth 3+ sections → `####` (capped at `######`)
 *
 * **Node rendering:**
 * - [ReportNode.Document]          — `# title` + blank line
 * - [ReportNode.Section]           — `## title` (depth-promoted)
 * - [ReportNode.Heading]           — `#` × level + text
 * - [ReportNode.Paragraph]         — text + blank line
 * - [ReportNode.StatTable]         — `StatisticReporter.halfWidthSummaryReportAsMarkDown`
 *                                    (detail=false); extended table appended for detail=true
 * - [ReportNode.WeightedStatTable] — [MarkDown.Table] (two-column key/value)
 * - [ReportNode.DataTable]         — [MarkDown.Table] with LEFT alignment
 * - [ReportNode.PlotNode]          — saves PNG to [RenderContext.plotDir]; emits `![caption](path)`
 * - [ReportNode.RawText]           — fenced code block (` ``` `)
 * - [ReportNode.PageBreak]         — `---`
 *
 * All mutable state uses the `my` prefix per KSL coding conventions.
 *
 * @param ctx the render context supplying output paths and formatting preferences
 */
class MarkdownReportRenderer(private val ctx: RenderContext = RenderContext()) : AbstractReportVisitor() {

    private val myOutput: StringBuilder = StringBuilder()
    private var myDepth: Int = 0
    private var mySectionPlotCount: Int = 0

    // ── ReportVisitor implementation ────────────────────────────────────────────

    override fun enterDocument(node: ReportNode.Document) {
        myOutput.appendLine("# ${node.title}")
        myOutput.appendLine()
    }

    override fun exitDocument(node: ReportNode.Document) {
        myOutput.appendLine()
    }

    override fun enterSection(node: ReportNode.Section) {
        myDepth++
        mySectionPlotCount = 0
        if (!node.title.isNullOrBlank()) {
            // depth 1 → ##, depth 2 → ###, depth 3+ → ####..###### (capped at 6)
            val myLevel = (myDepth + 1).coerceAtMost(6)
            val myPrefix = "#".repeat(myLevel)
            myOutput.appendLine()
            myOutput.appendLine("$myPrefix ${node.title}")
            myOutput.appendLine()
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
        myOutput.appendLine("**${myCaption}**")
        myOutput.appendLine()
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
        val myTable = MarkDown.Table(
            listOf("Property", "Value"),
            listOf(MarkDown.ColFmt.LEFT, MarkDown.ColFmt.RIGHT)
        )
        myRows.forEach { myTable.addRow(it) }
        myOutput.append(myTable.toString())
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.StatTable) {
        if (node.stats.isEmpty()) return
        if (node.caption != null) {
            myOutput.appendLine("**${node.caption}**")
            myOutput.appendLine()
        }
        // Compact summary table
        val myFormats = listOf(MarkDown.ColFmt.LEFT) +
                List(COMPACT_STAT_HEADERS.size - 1) { MarkDown.ColFmt.RIGHT }
        val myTable = MarkDown.Table(COMPACT_STAT_HEADERS, myFormats)
        compactStatRows(node.stats, node.confidenceLevel, ctx::fmt).forEach { myTable.addRow(it) }
        myOutput.append(myTable.toString())
        myOutput.appendLine()
        if (node.detail) {
            myOutput.appendLine("**Diagnostic Statistics**")
            myOutput.appendLine()
            val myDiagFormats = listOf(MarkDown.ColFmt.LEFT) +
                    List(DIAGNOSTIC_STAT_HEADERS.size - 1) { MarkDown.ColFmt.RIGHT }
            val myDiagTable = MarkDown.Table(DIAGNOSTIC_STAT_HEADERS, myDiagFormats)
            diagnosticStatRows(node.stats, ctx::fmt).forEach { myDiagTable.addRow(it) }
            myOutput.append(myDiagTable.toString())
            myOutput.appendLine()
        }
    }

    override fun visit(node: ReportNode.WeightedStatPropertyTable) {
        val myCaption = node.caption ?: node.stat.name
        myOutput.appendLine("**${myCaption}**")
        myOutput.appendLine()
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
        val myTable = MarkDown.Table(
            listOf("Property", "Value"),
            listOf(MarkDown.ColFmt.LEFT, MarkDown.ColFmt.RIGHT)
        )
        myRows.forEach { myTable.addRow(it) }
        myOutput.append(myTable.toString())
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.WeightedStatTable) {
        if (node.stats.isEmpty()) return
        if (node.caption != null) {
            myOutput.appendLine("**${node.caption}**")
            myOutput.appendLine()
        }
        val myHeaders = listOf(
            "Name", "Count", "Wtd Avg", "Unwtd Avg",
            "Wtd Sum", "Sum Wts", "Wtd SS", "Min", "Max", "Missing"
        )
        val myFormats = listOf(MarkDown.ColFmt.LEFT) +
                List(myHeaders.size - 1) { MarkDown.ColFmt.RIGHT }
        val myTable = MarkDown.Table(myHeaders, myFormats)
        for (ws in node.stats) {
            myTable.addRow(
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
            )
        }
        myOutput.append(myTable.toString())
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.DataTable) {
        if (node.rows.isEmpty()) return
        if (node.caption != null) {
            myOutput.appendLine("**${node.caption}**")
            myOutput.appendLine()
        }
        // Detect numeric columns: if every non-header cell is parseable as Double → RIGHT align
        val myFormats = node.headers.indices.map { col ->
            val myIsNumeric = node.rows.all { row ->
                val myCell = row[col]
                myCell == "—" || myCell.toDoubleOrNull() != null
            }
            if (myIsNumeric) MarkDown.ColFmt.RIGHT else MarkDown.ColFmt.LEFT
        }
        val myTable = MarkDown.Table(node.headers, myFormats)
        for (myRow in node.rows) {
            myTable.addRow(myRow)
        }
        myOutput.append(myTable.toString())
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.PlotNode) {
        if (mySectionPlotCount >= ctx.maxPlotsPerSection) {
            myOutput.appendLine(
                "> ⚠️ Plot omitted: section plot limit (${ctx.maxPlotsPerSection}) reached."
            )
            myOutput.appendLine()
            return
        }
        val myCaption = node.caption ?: node.plot.title.ifBlank { "plot" }
        val mySafeName = myCaption.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(60)
        val myFile = node.plot.saveToFile(mySafeName, ctx.plotDir, extType = PlotIfc.ExtType.PNG)
        // Use a relative path from outputDir for portability
        val myRelativePath = try {
            ctx.outputDir.relativize(myFile.toPath()).toString()
        } catch (_: IllegalArgumentException) {
            myFile.absolutePath
        }
        myOutput.appendLine(MarkDown.image(myCaption, myRelativePath))
        myOutput.appendLine()
        mySectionPlotCount++
    }

    override fun visit(node: ReportNode.RawText) {
        myOutput.appendLine("```")
        myOutput.appendLine(node.content)
        myOutput.appendLine("```")
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.PageBreak) {
        myOutput.appendLine()
        myOutput.appendLine("---")
        myOutput.appendLine()
    }

    // ── Output ──────────────────────────────────────────────────────────────────

    /**
     * Returns the accumulated Markdown report as a [String].
     */
    fun result(): String = myOutput.toString()

    /**
     * Returns the accumulated report as a Markdown [String].
     *
     * Useful for console output, logging, or test assertions without creating a file:
     * ```kotlin
     * val md = MarkdownReportRenderer(ctx).also { doc.accept(it) }.output()
     * ```
     */
    fun output(): String = myOutput.toString()

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
}
