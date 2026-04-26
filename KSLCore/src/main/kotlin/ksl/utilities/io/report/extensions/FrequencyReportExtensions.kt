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
import ksl.utilities.statistic.IntegerFrequency

/**
 * DSL extension functions on [ReportBuilder] for rendering [IntegerFrequency] instances.
 *
 * [IntegerFrequency] does **not** implement [ksl.utilities.statistic.StatisticIfc]; it
 * owns an internal [ksl.utilities.statistic.Statistic] accessible via
 * [IntegerFrequency.statistic]. The statistics on the observed integer values therefore
 * flow through [ksl.utilities.io.report.ast.ReportNode.StatTable] via that explicit
 * bridge rather than directly from the frequency object.
 */

/**
 * Appends a self-contained section that reports the frequency distribution, summary
 * statistics on the observed integer values, and a frequency bar plot.
 *
 * **Produces (inside a section titled `caption` or [freq.name][IntegerFrequency.name]):**
 * 1. A [ksl.utilities.io.report.ast.ReportNode.Paragraph] summarising the observed
 *    integer range, distinct value count, total observations, and over/underflow counts.
 * 2. A `DataTable` ("Frequency Table") with columns:
 *    Value | Label | Count | Cum Count | % | Cum %
 * 3. A [ksl.utilities.io.report.ast.ReportNode.StatTable] ("Statistics on Observed Values")
 *    built from [IntegerFrequency.statistic] — the explicit bridge from
 *    `IntegerFrequency` (not `StatisticIfc`) to the `StatTable` node.
 * 4. A [ksl.utilities.io.report.ast.ReportNode.PlotNode] for the frequency bar chart.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Number-in-System Analysis") {
 *     integerFrequency(serverCountFrequency)
 * }
 * ```
 *
 * @param freq            the integer frequency tabulation to report
 * @param caption         optional section title; defaults to [freq.name][IntegerFrequency.name]
 * @param confidenceLevel confidence level for the StatTable half-width and CI; must be in (0, 1)
 * @param showStatistics  when `true` (default) a [ksl.utilities.io.report.ast.ReportNode.StatPropertyTable]
 *                        is included summarising the statistics on the observed integer values.
 *                        Set to `false` when the statistics on the raw integer values have no
 *                        meaningful interpretation — e.g. rank-frequency distributions where
 *                        the frequency table and plot already tell the complete story.
 */
fun ReportBuilder.integerFrequency(
    freq: IntegerFrequency,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    showStatistics: Boolean = true
) {
    val myTitle = caption ?: freq.name
    section(myTitle) {
        // ── Overview paragraph ────────────────────────────────────────────────
        val myRange = if (freq.numberOfValues > 0) "${freq.min}–${freq.max}" else "—"
        paragraph(
            "Values: $myRange  |  " +
            "Distinct: ${freq.numberOfValues}  |  " +
            "Total: ${freq.totalCount.toInt()}  |  " +
            "Under: ${freq.underFlowCount}  |  " +
            "Over: ${freq.overFlowCount}"
        )

        // ── Frequency table ───────────────────────────────────────────────────
        val myHeaders = listOf("Value", "Label", "Count", "Cum Count", "%", "Cum %")
        val myRows = freq.frequencyData().map { f ->
            listOf(
                f.value.toString(),
                f.cellLabel,
                f.count.toInt().toString(),
                f.cum_count.toInt().toString(),
                fmtPct(f.proportion),
                fmtPct(f.cumProportion)
            )
        }
        dataTable(myHeaders, myRows, caption = "Frequency Table")

        // ── Statistics on observed integer values (explicit StatisticIfc bridge) ──
        if (showStatistics) {
            statPropertyTable(
                stat = freq.statistic(),
                caption = "Statistics on Observed Values",
                confidenceLevel = confidenceLevel
            )
        }

        // ── Frequency plot ────────────────────────────────────────────────────
        plot(freq.frequencyPlot(), caption = myTitle)
    }
}

// ── toReport() ───────────────────────────────────────────────────────────────

/**
 * Builds a [ReportNode.Document] whose default content is the full integer-frequency
 * section (overview paragraph, frequency table, statistics on observed values, and plot).
 *
 * Zero-code path:
 * ```kotlin
 * serverCount.toReport().showInBrowser()
 * serverCount.toReport().writeMarkdown()
 * ```
 *
 * Custom block replaces the default:
 * ```kotlin
 * serverCount.toReport("Number in System") {
 *     integerFrequency(this@toReport)       // standard section
 *     paragraph("Mode is ${this@toReport.mode}.")
 * }
 * ```
 *
 * @param title           document title; defaults to [IntegerFrequency.name]
 * @param confidenceLevel confidence level for the StatTable CI; must be in (0, 1)
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun IntegerFrequency.toReport(
    title: String = name,
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        integerFrequency(this@toReport, confidenceLevel = confidenceLevel)
    }
): ReportNode.Document = report(title, block)

