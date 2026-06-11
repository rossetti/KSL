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
 * - [ReportNode.Heading]           — mapped to `\section`…`\subsubsection` by `level`
 * - [ReportNode.Paragraph]         — verbatim text + blank line
 * - [ReportNode.StatTable]         — `StatisticReporter.halfWidthSummaryReportAsLaTeXTables` wrapped in a
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

    override fun visit(node: ReportNode.StatPropertyTable) {
        val myCaption = node.caption ?: node.stat.name
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
        myOutput.appendLine(buildLaTeXTable(listOf("Property", "Value"), myRows, caption = myCaption))
    }

    override fun visit(node: ReportNode.StatTable) {
        if (node.stats.isEmpty()) return
        val myRows = compactStatRows(node.stats, node.confidenceLevel, ctx::fmt)
        myOutput.appendLine(buildLaTeXTable(COMPACT_STAT_HEADERS, myRows, caption = node.caption))
        if (node.detail) {
            myOutput.appendLine()
            val myDiagRows = diagnosticStatRows(node.stats, ctx::fmt)
            myOutput.appendLine(buildLaTeXTable(DIAGNOSTIC_STAT_HEADERS, myDiagRows, caption = "Diagnostic Statistics"))
        }
    }

    override fun visit(node: ReportNode.WeightedStatPropertyTable) {
        val myCaption = node.caption ?: node.stat.name
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
        myOutput.appendLine(buildLaTeXTable(listOf("Property", "Value"), myRows, caption = myCaption))
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
