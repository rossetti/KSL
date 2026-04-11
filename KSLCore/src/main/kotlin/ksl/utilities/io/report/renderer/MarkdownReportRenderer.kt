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
import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.plotting.PlotIfc
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.visitor.ReportVisitor
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * A [ReportVisitor] that renders a [ReportNode] tree to GitHub-flavoured Markdown.
 *
 * Delegates all statistical table formatting to [StatisticReporter] (reusing the
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
 * - [ReportNode.StatTable]         — [StatisticReporter.halfWidthSummaryReportAsMarkDown]
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
class MarkdownReportRenderer(private val ctx: RenderContext = RenderContext()) : ReportVisitor {

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

    override fun visit(node: ReportNode.StatTable) {
        if (node.stats.isEmpty()) return
        val myReporter = StatisticReporter(node.stats.toMutableList())
        myReporter.reportLabelFlag = false
        if (node.caption != null) {
            myOutput.appendLine("**${node.caption}**")
            myOutput.appendLine()
        }
        // Compact half-width summary table
        myOutput.append(myReporter.halfWidthSummaryReportAsMarkDown(level = node.confidenceLevel))
        myOutput.appendLine()
        if (node.detail) {
            // Extended diagnostic table: skewness, kurtosis, lag-1 correlation, VN test, missing
            myOutput.appendLine("**Diagnostic Statistics**")
            myOutput.appendLine()
            val myHeaders = listOf(
                "Name", "Skewness", "Kurtosis",
                "Lag-1 Corr", "VN Statistic", "VN p-value", "Missing"
            )
            val myFormats = listOf(
                MarkDown.ColFmt.LEFT,
                MarkDown.ColFmt.RIGHT, MarkDown.ColFmt.RIGHT,
                MarkDown.ColFmt.RIGHT, MarkDown.ColFmt.RIGHT,
                MarkDown.ColFmt.RIGHT, MarkDown.ColFmt.RIGHT
            )
            val myTable = MarkDown.Table(myHeaders, myFormats)
            for (stat in node.stats) {
                myTable.addRow(
                    listOf(
                        stat.name,
                        ctx.fmt(stat.skewness),
                        ctx.fmt(stat.kurtosis),
                        ctx.fmt(stat.lag1Correlation),
                        ctx.fmt(stat.vonNeumannLag1TestStatistic),
                        ctx.fmt(stat.vonNeumannLag1TestStatisticPValue),
                        ctx.fmt(stat.numberMissing)
                    )
                )
            }
            myOutput.append(myTable.toString())
            myOutput.appendLine()
        }
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
