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

import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.plotting.PlotIfc
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.visitor.AbstractReportVisitor
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * A [ksl.utilities.io.report.visitor.ReportVisitor] that renders a [ReportNode] tree to
 * a LaTeX source file (`.tex`).
 *
 * Extends [AbstractReportVisitor] and overrides only the nodes it handles; unrecognised
 * nodes are silently skipped (no-op from the base class).
 *
 * **Preamble (emitted in [enterDocument]):**
 * ```latex
 * \documentclass{article}
 * \usepackage[utf8]{inputenc}
 * \usepackage[T1]{fontenc}
 * \usepackage{booktabs}
 * \usepackage{longtable}
 * \usepackage{graphicx}
 * \usepackage{hyperref}
 * \usepackage{geometry}
 * \geometry{margin=1in}
 * \title{…}
 * \date{\today}
 * \begin{document}
 * \maketitle
 * ```
 *
 * **Node rendering:**
 * - [ReportNode.Document]          — full LaTeX preamble + `\maketitle`; `\end{document}` on exit
 * - [ReportNode.Section]           — `\section`, `\subsection`, `\subsubsection` (depth-promoted)
 * - [ReportNode.Heading]           — mapped to `\section`…`\subsubsection` by [level]
 * - [ReportNode.Paragraph]         — verbatim text + blank line
 * - [ReportNode.StatTable]         — [StatisticReporter.halfWidthSummaryReportAsLaTeXTables] wrapped in a
 *                                    `table` + `caption` environment; detail=true appends a second tabular
 *                                    for diagnostic fields
 * - [ReportNode.WeightedStatTable] — manually constructed `longtable` with all weighted-statistic columns
 * - [ReportNode.DataTable]         — `longtable` with first column `l`, remaining columns `r`
 * - [ReportNode.PlotNode]          — saves PNG to [RenderContext.plotDir]; emits `figure` environment
 *                                    with `\includegraphics` and `\caption`
 * - [ReportNode.RawText]           — `verbatim` environment
 * - [ReportNode.PageBreak]         — `\newpage`
 *
 * **LaTeX escaping:** all user-supplied strings are passed through [escapeLaTeX] which
 * escapes the ten special LaTeX characters (`\ { } $ & # ^ _ % ~`).
 *
 * All mutable state uses the `my` prefix per KSL coding conventions.
 *
 * @param ctx the render context supplying output paths and formatting preferences
 */
class LaTeXReportRenderer(private val ctx: RenderContext = RenderContext()) : AbstractReportVisitor() {

    private val myOutput: StringBuilder = StringBuilder()
    private var myDepth: Int = 0
    private var mySectionPlotCount: Int = 0

    // ── ReportVisitor overrides ─────────────────────────────────────────────────

    override fun enterDocument(node: ReportNode.Document) {
        myOutput.appendLine(
            """\documentclass{article}
\usepackage[utf8]{inputenc}
\usepackage[T1]{fontenc}
\usepackage{booktabs}
\usepackage{longtable}
\usepackage{graphicx}
\usepackage{hyperref}
\usepackage{geometry}
\geometry{margin=1in}
\title{${escapeLaTeX(node.title)}}
\date{\today}
\begin{document}
\maketitle"""
        )
    }

    override fun exitDocument(node: ReportNode.Document) {
        myOutput.appendLine()
        myOutput.append("""\end{document}""")
    }

    override fun enterSection(node: ReportNode.Section) {
        myDepth++
        mySectionPlotCount = 0
        if (!node.title.isNullOrBlank()) {
            val myCmd = sectionCommand(myDepth)
            myOutput.appendLine()
            myOutput.appendLine("$myCmd{${escapeLaTeX(node.title)}}")
        }
    }

    override fun exitSection(node: ReportNode.Section) {
        myDepth--
        mySectionPlotCount = 0
    }

    override fun visit(node: ReportNode.Heading) {
        // Map heading level 1–6 → \section … \subparagraph
        val myCmd = when (node.level) {
            1 -> """\section*"""
            2 -> """\subsection*"""
            3 -> """\subsubsection*"""
            4 -> """\paragraph"""
            5 -> """\subparagraph"""
            else -> """\subparagraph"""
        }
        myOutput.appendLine()
        myOutput.appendLine("$myCmd{${escapeLaTeX(node.text)}}")
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.Paragraph) {
        myOutput.appendLine()
        myOutput.appendLine(escapeLaTeX(node.text))
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.StatTable) {
        if (node.stats.isEmpty()) return
        val myReporter = StatisticReporter(node.stats.toMutableList())
        myReporter.reportLabelFlag = false

        if (node.caption != null) {
            myOutput.appendLine()
            myOutput.appendLine("""\textbf{${escapeLaTeX(node.caption)}}""")
            myOutput.appendLine()
        }

        // Compact half-width summary — uses StatisticReporter's built-in LaTeX method
        // which already wraps each chunk in \begin{table}…\end{table}
        val myTables = myReporter.halfWidthSummaryReportAsLaTeXTables(confLevel = node.confidenceLevel)
        for (myTable in myTables) {
            myOutput.appendLine(myTable.toString())
        }

        if (node.detail) {
            myOutput.appendLine()
            myOutput.appendLine("""\textbf{Diagnostic Statistics}""")
            myOutput.appendLine()
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
            myOutput.appendLine(buildLaTeXTable(myHeaders, myRows, caption = "Diagnostic Statistics"))
        }
    }

    override fun visit(node: ReportNode.WeightedStatTable) {
        if (node.stats.isEmpty()) return
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
        myOutput.appendLine(buildLaTeXTable(myHeaders, myRows, caption = node.caption))
    }

    override fun visit(node: ReportNode.DataTable) {
        if (node.rows.isEmpty()) return
        myOutput.appendLine(buildLaTeXTable(node.headers, node.rows, caption = node.caption))
    }

    override fun visit(node: ReportNode.PlotNode) {
        if (mySectionPlotCount >= ctx.maxPlotsPerSection) {
            myOutput.appendLine()
            myOutput.appendLine("""\textit{Plot omitted: section plot limit (${ctx.maxPlotsPerSection}) reached.}""")
            myOutput.appendLine()
            return
        }
        val myCaption = node.caption ?: node.plot.title.ifBlank { "plot" }
        val mySafeName = myCaption.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(60)
        val myFile = node.plot.saveToFile(mySafeName, ctx.plotDir, extType = PlotIfc.ExtType.PNG)
        // Use absolute path; LaTeX \includegraphics handles forward slashes on all platforms
        val myPath = myFile.absolutePath.replace("\\", "/")
        myOutput.appendLine()
        myOutput.appendLine(
            """\begin{figure}[ht]
\centering
\includegraphics[width=0.85\textwidth]{$myPath}
\caption{${escapeLaTeX(myCaption)}}
\end{figure}"""
        )
        myOutput.appendLine()
        mySectionPlotCount++
    }

    override fun visit(node: ReportNode.RawText) {
        myOutput.appendLine()
        myOutput.appendLine("""\begin{verbatim}""")
        myOutput.appendLine(node.content)
        myOutput.appendLine("""\end{verbatim}""")
        myOutput.appendLine()
    }

    override fun visit(node: ReportNode.PageBreak) {
        myOutput.appendLine()
        myOutput.appendLine("""\newpage""")
        myOutput.appendLine()
    }

    // ── Output ──────────────────────────────────────────────────────────────────

    /**
     * Returns the accumulated LaTeX source as a [String].
     */
    fun result(): String = myOutput.toString()

    /**
     * Writes the accumulated LaTeX source to the given [PrintWriter].
     */
    fun writeTo(out: PrintWriter) {
        out.print(myOutput.toString())
        out.flush()
    }

    /**
     * Writes the accumulated LaTeX source to a file at [path] and returns a [File] reference.
     */
    fun writeTo(path: Path): File {
        val myFile = path.toFile()
        myFile.parentFile?.mkdirs()
        myFile.writeText(myOutput.toString())
        return myFile
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the LaTeX sectioning command appropriate for the current [depth].
     * Depth 1 → `\section`, 2 → `\subsection`, 3+ → `\subsubsection`.
     */
    private fun sectionCommand(depth: Int): String = when (depth) {
        1 -> """\section"""
        2 -> """\subsection"""
        else -> """\subsubsection"""
    }

    /**
     * Builds a `longtable` environment from [headers] and [rows].
     *
     * The first column is left-aligned (`l`); all remaining columns are right-aligned (`r`).
     * An optional [caption] is inserted immediately after `\begin{longtable}`.
     */
    private fun buildLaTeXTable(
        headers: List<String>,
        rows: List<List<String>>,
        caption: String? = null
    ): String {
        if (headers.isEmpty()) return ""
        val myColSpec = "l" + "r".repeat((headers.size - 1).coerceAtLeast(0))
        val mySb = StringBuilder()
        mySb.appendLine("""\begin{longtable}{$myColSpec}""")
        if (caption != null) {
            mySb.appendLine("""\caption{${escapeLaTeX(caption)}} \\""")
        }
        mySb.appendLine("""\toprule""")
        mySb.appendLine(headers.joinToString(" & ") { escapeLaTeX(it) } + """ \\""")
        mySb.appendLine("""\midrule""")
        mySb.appendLine("""\endfirsthead""")
        // Continuation header
        mySb.appendLine("""\toprule""")
        mySb.appendLine(headers.joinToString(" & ") { escapeLaTeX(it) } + """ \\""")
        mySb.appendLine("""\midrule""")
        mySb.appendLine("""\endhead""")
        mySb.appendLine("""\midrule \multicolumn{${headers.size}}{r}{\textit{Continued\ldots}} \\""")
        mySb.appendLine("""\endfoot""")
        mySb.appendLine("""\bottomrule""")
        mySb.appendLine("""\endlastfoot""")
        for (myRow in rows) {
            mySb.appendLine(
                headers.indices.joinToString(" & ") { i ->
                    val myCell = if (i < myRow.size) myRow[i] else ""
                    escapeLaTeX(myCell)
                } + """ \\"""
            )
        }
        mySb.append("""\end{longtable}""")
        return mySb.toString()
    }

    /**
     * Escapes the ten special LaTeX characters in [text] so that user-supplied strings
     * render as literal text rather than being interpreted as LaTeX commands.
     *
     * Escape order matters: `\` must be replaced first (as `\textbackslash{}`),
     * then the remaining nine characters.
     */
    private fun escapeLaTeX(text: String): String = text
        .replace("\\", """\textbackslash{}""")
        .replace("{", """\{""")
        .replace("}", """\}""")
        .replace("\$", """\$""")
        .replace("&", """\&""")
        .replace("#", """\#""")
        .replace("^", """\^{}""")
        .replace("_", """\_""")
        .replace("%", """\%""")
        .replace("~", """\textasciitilde{}""")
}
