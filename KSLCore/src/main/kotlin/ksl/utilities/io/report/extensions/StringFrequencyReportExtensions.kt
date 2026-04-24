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

import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.StringFrequency

/**
 * DSL extension functions on [ReportBuilder] for rendering [StringFrequency] instances.
 *
 * [StringFrequency] tabulates categorical string values and therefore has no numeric
 * statistics (no mean, variance, or confidence interval). Consequently this extension
 * produces no [ksl.utilities.io.report.ast.ReportNode.StatPropertyTable]; the frequency
 * table and optional bar chart are the complete story.
 */

/**
 * Appends a self-contained section that reports the frequency distribution and an
 * optional frequency bar plot for a [StringFrequency] tabulation.
 *
 * **Produces (inside a section titled `caption` or [freq.name][StringFrequency.name]):**
 * 1. A [ksl.utilities.io.report.ast.ReportNode.Paragraph] summarising total observations,
 *    distinct string count, and — when non-zero — the count of observations that fell
 *    outside the limit set ("other" count).
 * 2. A `DataTable` ("Frequency Table") with columns:
 *    String | Count | Cum Count | % | Cum %
 * 3. A [ksl.utilities.io.report.ast.ReportNode.PlotNode] for the frequency bar chart
 *    (omitted when `showPlot` is `false`).
 *
 * No [ksl.utilities.io.report.ast.ReportNode.StatPropertyTable] is produced because
 * string categories carry no numeric statistics.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Classification Results") {
 *     stringFrequency(classificationFrequency)
 * }
 * ```
 *
 * @param freq        the string frequency tabulation to report
 * @param caption     optional section title; defaults to [freq.name][StringFrequency.name]
 * @param showPlot    when `true` (default) a bar chart is appended after the frequency table.
 *                    Set to `false` to omit the plot.
 * @param proportions when `true` the bar chart y-axis shows proportions; when `false`
 *                    (default) it shows counts. Has no effect when [showPlot] is `false`.
 */
fun ReportBuilder.stringFrequency(
    freq: StringFrequency,
    caption: String? = null,
    showPlot: Boolean = true,
    proportions: Boolean = false
) {
    val myTitle = caption ?: freq.name
    section(myTitle) {
        // ── Overview paragraph ────────────────────────────────────────────────
        val myOther = if (freq.otherCount > 0) "  |  Other: ${freq.otherCount}" else ""
        paragraph(
            "Distinct strings: ${freq.numberOfStrings}  |  " +
            "Total: ${freq.totalCount}" +
            myOther
        )

        // ── Frequency table ───────────────────────────────────────────────────
        val myHeaders = listOf("String", "Count", "Cum Count", "%", "Cum %")
        val myRows = freq.frequencyData().map { f ->
            listOf(
                f.string,
                f.count.toInt().toString(),
                f.cum_count.toInt().toString(),
                formatPct(f.proportion),
                formatPct(f.cum_proportion)
            )
        }
        dataTable(myHeaders, myRows, caption = "Frequency Table")

        // ── Frequency plot ────────────────────────────────────────────────────
        if (showPlot) {
            plot(freq.frequencyPlot(proportions), caption = myTitle)
        }
    }
}

// ── toReport() ───────────────────────────────────────────────────────────────

/**
 * Builds a [ReportNode.Document] whose default content is the full string-frequency
 * section (overview paragraph, frequency table, and plot).
 *
 * Zero-code path:
 * ```kotlin
 * classificationFrequency.toReport().showInBrowser()
 * classificationFrequency.toReport().writeMarkdown()
 * ```
 *
 * Custom block replaces the default:
 * ```kotlin
 * classificationFrequency.toReport("Classification Results") {
 *     stringFrequency(this@toReport)
 *     paragraph("See also the confusion matrix above.")
 * }
 * ```
 *
 * @param title       document title; defaults to [StringFrequency.name]
 * @param showPlot    when `true` (default) a bar chart is included in the section
 * @param proportions when `true` the bar chart y-axis shows proportions instead of counts
 * @param block       optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun StringFrequency.toReport(
    title: String = name,
    showPlot: Boolean = true,
    proportions: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        stringFrequency(this@toReport, showPlot = showPlot, proportions = proportions)
    }
): ReportNode.Document = report(title, block)

// ── Private formatting helper ─────────────────────────────────────────────────

/**
 * Formats a proportion (value in [0, 1]) as a percentage string with two decimal
 * places, e.g. `"12.34%"`. Returns `"—"` for NaN or infinite values.
 */
private fun formatPct(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.2f%%".format(value * 100.0)
}
