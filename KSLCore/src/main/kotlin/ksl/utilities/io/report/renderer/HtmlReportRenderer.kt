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

import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.visitor.ReportVisitor
import ksl.utilities.io.toHtmlTable
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A [ReportVisitor] that renders a [ReportNode] tree to a self-contained HTML file.
 *
 * **Structure:**
 * - `<html><head>` — inline [REPORT_CSS] + Lets-Plot CDN `<script>` (emitted once)
 * - `<body>` — content; `<h1>` document title
 * - `<details open><summary>` — collapsible sections (nested sections indent)
 * - `<footer>` — generated timestamp
 *
 * **Node rendering:**
 * - [ReportNode.Document]          — `<h1>` title; footer timestamp on exit
 * - [ReportNode.Section]           — `<details open><summary>title</summary>`
 * - [ReportNode.Heading]           — `<h{level}>text</h{level}>`
 * - [ReportNode.Paragraph]         — `<p>text</p>`
 * - [ReportNode.StatTable]         — [StatisticReporter.asDataFrame] → [toHtmlTable];
 *                                    detail=true appends an extended diagnostic table
 * - [ReportNode.WeightedStatTable] — HTML `<table>` with all weighted-statistic fields
 * - [ReportNode.DataTable]         — HTML `<table>`; numeric columns right-aligned
 * - [ReportNode.PlotNode]          — `<div class="plot-container">` +
 *                                    [ksl.utilities.io.plotting.PlotIfc.toEmbeddedHTML]
 * - [ReportNode.RawText]           — `<pre>content</pre>`
 * - [ReportNode.PageBreak]         — `<hr/>`
 *
 * The Lets-Plot CDN `<script>` is emitted exactly once inside `<head>` so all
 * embedded plot fragments can share it. Plots beyond [RenderContext.maxPlotsPerSection]
 * per section are replaced with a `<p class="plot-omitted">` notice.
 *
 * All mutable state uses the `my` prefix per KSL coding conventions.
 *
 * @param ctx the render context supplying output paths and formatting preferences
 */
class HtmlReportRenderer(private val ctx: RenderContext = RenderContext()) : ReportVisitor {

    private val myOutput: StringBuilder = StringBuilder()
    private var myDepth: Int = 0
    private var mySectionPlotCount: Int = 0

    // ── ReportVisitor implementation ────────────────────────────────────────────

    override fun enterDocument(node: ReportNode.Document) {
        val myScriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
        myOutput.appendLine(
            """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>${node.title.escapeHtml()}</title>
<script src="$myScriptUrl"></script>
<style>
$REPORT_CSS
</style>
</head>
<body>
<h1>${node.title.escapeHtml()}</h1>"""
        )
    }

    override fun exitDocument(node: ReportNode.Document) {
        val myTimestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        myOutput.appendLine("""<footer>Generated: $myTimestamp</footer>""")
        myOutput.appendLine("</body>")
        myOutput.append("</html>")
    }

    override fun enterSection(node: ReportNode.Section) {
        myDepth++
        mySectionPlotCount = 0
        val mySummary = node.title?.escapeHtml() ?: "Section"
        myOutput.appendLine("""<details open><summary>$mySummary</summary>""")
        myOutput.appendLine("""<div class="section-body">""")
    }

    override fun exitSection(node: ReportNode.Section) {
        myOutput.appendLine("</div></details>")
        myDepth--
        mySectionPlotCount = 0
    }

    override fun visit(node: ReportNode.Heading) {
        val myLevel = node.level.coerceIn(1, 6)
        myOutput.appendLine("<h$myLevel>${node.text.escapeHtml()}</h$myLevel>")
    }

    override fun visit(node: ReportNode.Paragraph) {
        myOutput.appendLine("<p>${node.text.escapeHtml()}</p>")
    }

    override fun visit(node: ReportNode.StatPropertyTable) {
        val myCaption = node.caption ?: node.stat.name
        myOutput.appendLine("<p class=\"table-caption\">${myCaption.escapeHtml()}</p>")
        val myCI = node.stat.confidenceInterval(node.confidenceLevel)
        val myHW = node.stat.halfWidth(node.confidenceLevel)
        val myRows = listOf(
            listOf("Count",              ctx.fmt(node.stat.count)),
            listOf("Average",            ctx.fmt(node.stat.average)),
            listOf("Std Dev",            ctx.fmt(node.stat.standardDeviation)),
            listOf("Std Error",          ctx.fmt(node.stat.standardError)),
            listOf("Half-width",         ctx.fmt(myHW)),
            listOf("Confidence Level",   ctx.fmt(node.confidenceLevel)),
            listOf("CI Lower",           ctx.fmt(myCI.lowerLimit)),
            listOf("CI Upper",           ctx.fmt(myCI.upperLimit)),
            listOf("Min",                ctx.fmt(node.stat.min)),
            listOf("Max",                ctx.fmt(node.stat.max)),
            listOf("Sum",                ctx.fmt(node.stat.sum)),
            listOf("Variance",           ctx.fmt(node.stat.variance)),
            listOf("Dev Sum of Sq",      ctx.fmt(node.stat.deviationSumOfSquares)),
            listOf("Kurtosis",           ctx.fmt(node.stat.kurtosis)),
            listOf("Skewness",           ctx.fmt(node.stat.skewness)),
            listOf("Lag-1 Covariance",   ctx.fmt(node.stat.lag1Covariance)),
            listOf("Lag-1 Correlation",  ctx.fmt(node.stat.lag1Correlation)),
            listOf("Von Neumann Stat",   ctx.fmt(node.stat.vonNeumannLag1TestStatistic)),
            listOf("Missing",            ctx.fmt(node.stat.numberMissing))
        )
        myOutput.appendLine(buildHtmlTable(listOf("Property", "Value"), myRows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.StatTable) {
        if (node.stats.isEmpty()) return
        val myReporter = StatisticReporter(node.stats.toMutableList())
        myReporter.reportLabelFlag = false
        if (node.caption != null) {
            myOutput.appendLine("<p class=\"table-caption\">${node.caption.escapeHtml()}</p>")
        }
        // Compact half-width summary via DataFrame → toHtmlTable
        val myDf = myReporter.asDataFrame(level = node.confidenceLevel)
        myOutput.appendLine(myDf.toHtmlTable(precision = ctx.numericPrecision))
        myOutput.appendLine()

        if (node.detail) {
            myOutput.appendLine("<p class=\"table-caption\"><strong>Diagnostic Statistics</strong></p>")
            // Build a diagnostic table manually from StatisticIfc fields
            val myHeaders = listOf(
                "Name", "Skewness", "Kurtosis",
                "Lag-1 Corr", "VN Statistic", "VN p-value", "Missing"
            )
            val myRows = node.stats.map { stat ->
                listOf(
                    stat.name,
                    ctx.fmt(stat.skewness),
                    ctx.fmt(stat.kurtosis),
                    ctx.fmt(stat.lag1Correlation),
                    ctx.fmt(stat.vonNeumannLag1TestStatistic),
                    ctx.fmt(stat.vonNeumannLag1TestStatisticPValue),
                    ctx.fmt(stat.numberMissing)
                )
            }
            myOutput.appendLine(buildHtmlTable(myHeaders, myRows))
            myOutput.appendLine()
        }
    }

    override fun visit(node: ReportNode.WeightedStatPropertyTable) {
        val myCaption = node.caption ?: node.stat.name
        myOutput.appendLine("<p class=\"table-caption\">${myCaption.escapeHtml()}</p>")
        val myRows = listOf(
            listOf("Count",                   ctx.fmt(node.stat.count)),
            listOf("Weighted Average",         ctx.fmt(node.stat.weightedAverage)),
            listOf("Unweighted Average",       ctx.fmt(node.stat.unWeightedAverage)),
            listOf("Weighted Sum",             ctx.fmt(node.stat.weightedSum)),
            listOf("Sum of Weights",           ctx.fmt(node.stat.sumOfWeights)),
            listOf("Weighted Sum of Squares",  ctx.fmt(node.stat.weightedSumOfSquares)),
            listOf("Min",                      ctx.fmt(node.stat.min)),
            listOf("Max",                      ctx.fmt(node.stat.max)),
            listOf("Missing",                  ctx.fmt(node.stat.numberMissing))
        )
        myOutput.appendLine(buildHtmlTable(listOf("Property", "Value"), myRows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.WeightedStatTable) {
        if (node.stats.isEmpty()) return
        if (node.caption != null) {
            myOutput.appendLine("<p class=\"table-caption\">${node.caption.escapeHtml()}</p>")
        }
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
        myOutput.appendLine(buildHtmlTable(myHeaders, myRows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.DataTable) {
        if (node.rows.isEmpty()) return
        if (node.caption != null) {
            myOutput.appendLine("<p class=\"table-caption\">${node.caption.escapeHtml()}</p>")
        }
        myOutput.appendLine(buildHtmlTable(node.headers, node.rows))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.PlotNode) {
        if (mySectionPlotCount >= ctx.maxPlotsPerSection) {
            myOutput.appendLine(
                """<p class="plot-omitted">⚠️ Plot omitted: section plot limit (${ctx.maxPlotsPerSection}) reached.</p>"""
            )
            return
        }
        val myCaption = node.caption ?: node.plot.title.ifBlank { null }
        myOutput.appendLine("""<div class="plot-container">""")
        myOutput.appendLine(node.plot.toEmbeddedHTML())
        if (myCaption != null) {
            myOutput.appendLine("""<p class="plot-caption">${myCaption.escapeHtml()}</p>""")
        }
        myOutput.appendLine("</div>")
        myOutput.appendLine()
        mySectionPlotCount++
    }

    override fun visit(node: ReportNode.RawText) {
        myOutput.appendLine("<pre>${node.content.escapeHtml()}</pre>")
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.PageBreak) {
        myOutput.appendLine("<hr/>")
    }

    // ── Output ──────────────────────────────────────────────────────────────────

    /**
     * Returns the accumulated HTML report as a [String].
     */
    fun result(): String = myOutput.toString()

    /**
     * Writes the accumulated report to a file at [path] and returns a [File] reference.
     */
    fun writeToFile(path: Path): File {
        val myFile = path.toFile()
        myFile.parentFile?.mkdirs()
        myFile.writeText(myOutput.toString())
        return myFile
    }

    /**
     * Writes the accumulated HTML to a temporary file in [RenderContext.outputDir] and
     * opens it in the system default browser. Returns the [File] that was created.
     */
    fun showInBrowser(): File {
        return KSLFileUtil.openInBrowser("ksl_report_", myOutput.toString(), ctx.outputDir)
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a plain HTML `<table>` from [headers] and [rows]. Columns whose every
     * data cell is parseable as [Double] (or is `"—"`) are right-aligned; all others
     * are left-aligned.
     */
    private fun buildHtmlTable(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty()) return ""
        // Determine alignment per column
        val myRightAlign = headers.indices.map { col ->
            rows.all { row ->
                val myCell = if (col < row.size) row[col] else ""
                myCell == "—" || myCell.toDoubleOrNull() != null
            }
        }
        val mySb = StringBuilder()
        mySb.appendLine("<table>")
        mySb.append("  <thead><tr>")
        headers.forEachIndexed { i, h ->
            val myAlign = if (myRightAlign[i]) " style=\"text-align:right\"" else ""
            mySb.append("<th$myAlign>${h.escapeHtml()}</th>")
        }
        mySb.appendLine("</tr></thead>")
        mySb.appendLine("  <tbody>")
        rows.forEach { row ->
            mySb.append("    <tr>")
            headers.indices.forEach { i ->
                val myCell = if (i < row.size) row[i] else ""
                val myAlign = if (myRightAlign[i]) " style=\"text-align:right\"" else ""
                mySb.append("<td$myAlign>${myCell.escapeHtml()}</td>")
            }
            mySb.appendLine("</tr>")
        }
        mySb.appendLine("  </tbody>")
        mySb.append("</table>")
        return mySb.toString()
    }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    // ── CSS ──────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * Inline CSS for KSL HTML reports. Covers tables, collapsible sections,
         * plot containers, and general typography.
         */
        const val REPORT_CSS: String = """
/* ── Base ─────────────────────────────────────────────────────────────── */
body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    font-size: 14px;
    line-height: 1.5;
    color: #222;
    max-width: 1200px;
    margin: 0 auto;
    padding: 1rem 2rem;
    background: #fff;
}
h1 { font-size: 1.8rem; border-bottom: 2px solid #4a6fa5; padding-bottom: 0.3rem; }
h2 { font-size: 1.4rem; }
h3 { font-size: 1.2rem; }
h4, h5, h6 { font-size: 1rem; }
p { margin: 0.5rem 0 1rem; }
footer {
    margin-top: 2rem;
    padding-top: 0.5rem;
    border-top: 1px solid #ccc;
    font-size: 0.8rem;
    color: #888;
}

/* ── Tables ────────────────────────────────────────────────────────────── */
table {
    border-collapse: collapse;
    width: auto;
    max-width: 100%;
    margin: 0.5rem 0 1.5rem;
    font-size: 0.9rem;
}
th, td {
    border: 1px solid #d0d0d0;
    padding: 0.35rem 0.7rem;
    white-space: nowrap;
}
th {
    background: #4a6fa5;
    color: #fff;
    font-weight: 600;
}
tr:nth-child(even) td { background: #f4f7fb; }
tr:hover td { background: #e8eef7; }
td:first-child { word-break: break-word; white-space: normal; max-width: 280px; }
caption, p.table-caption {
    caption-side: top;
    text-align: left;
    font-weight: 600;
    margin-bottom: 0.3rem;
    color: #333;
}

/* ── Collapsible sections ──────────────────────────────────────────────── */
details {
    border: 1px solid #d8e2f0;
    border-radius: 4px;
    margin: 0.75rem 0;
    padding: 0;
}
summary {
    cursor: pointer;
    padding: 0.5rem 0.75rem;
    background: #e8eef7;
    font-weight: 600;
    font-size: 1rem;
    border-radius: 4px;
    list-style: none;
    user-select: none;
}
summary::before { content: "▶ "; font-size: 0.75em; }
details[open] > summary::before { content: "▼ "; }
.section-body {
    padding: 0.75rem 1rem;
}

/* ── Plots ─────────────────────────────────────────────────────────────── */
.plot-container {
    max-width: 900px;
    margin: 1rem 0 1.5rem;
}
.plot-caption {
    text-align: center;
    font-style: italic;
    color: #555;
    margin-top: 0.25rem;
    font-size: 0.85rem;
}
.plot-omitted {
    color: #b94a48;
    background: #fdf3f3;
    border: 1px solid #f5c6cb;
    border-radius: 4px;
    padding: 0.4rem 0.75rem;
    font-size: 0.875rem;
}

/* ── Raw text ───────────────────────────────────────────────────────────── */
pre {
    background: #f6f8fa;
    border: 1px solid #ddd;
    border-radius: 4px;
    padding: 0.75rem 1rem;
    overflow-x: auto;
    font-size: 0.85rem;
    line-height: 1.4;
}
"""
    }
}
