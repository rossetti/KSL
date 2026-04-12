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
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc

/**
 * DSL extension functions on [ReportBuilder] for rendering [Histogram] instances.
 *
 * [Histogram] implements [ksl.utilities.statistic.StatisticIfc] via
 * [ksl.utilities.statistic.AbstractStatistic], so the statistics on its binned observations
 * flow naturally through [ksl.utilities.io.report.ast.ReportNode.StatTable]. This extension
 * adds the bin-frequency table and contextual over/underflow metadata that
 * [ksl.utilities.statistic.StatisticIfc] does not expose.
 */

/**
 * Appends a self-contained section that reports the bin structure, over/underflow summary,
 * bin frequency table, statistics on binned observations, and a histogram plot.
 *
 * **Produces (inside a section titled [caption] or [h.name][Histogram.name]):**
 * 1. A [ksl.utilities.io.report.ast.ReportNode.Paragraph] summarising bin count, range,
 *    underflow count, overflow count, and total count.
 * 2. A `DataTable` ("Bin Frequencies") with columns:
 *    Bin | Label | Lower | Upper | Count | Cum Count | % | Cum %
 * 3. A [ksl.utilities.io.report.ast.ReportNode.StatTable] ("Statistics on Binned Data")
 *    for the observations that fell within the defined bins (excludes over/underflow).
 * 4. A [ksl.utilities.io.report.ast.ReportNode.PlotNode] for the histogram plot.
 *
 * **Numeric formatting:** bin limits that are ±∞ are rendered as `"−∞"` / `"+∞"`.
 * Proportions are shown as percentages rounded to two decimal places. All other
 * floating-point values use four decimal places.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Wait Time Analysis") {
 *     histogram(waitTimeHistogram, confidenceLevel = 0.90)
 * }
 * ```
 *
 * @param h               the histogram to report
 * @param caption         optional section title; defaults to [h.name][Histogram.name]
 * @param confidenceLevel confidence level for the StatTable half-width and CI; must be in (0, 1)
 */
fun ReportBuilder.histogram(
    h: Histogram,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: h.name
    section(myTitle) {
        // ── Overview paragraph ────────────────────────────────────────────────
        val myLower = formatLimit(h.firstBinLowerLimit)
        val myUpper = formatLimit(h.lastBinUpperLimit)
        paragraph(
            "Bins: ${h.numberBins}  |  " +
            "Range: [$myLower, $myUpper]  |  " +
            "Under: ${h.underFlowCount.toInt()}  |  " +
            "Over: ${h.overFlowCount.toInt()}  |  " +
            "Total: ${h.totalCount.toInt()}  |  " +
            "In Bins: ${h.count.toInt()}  |  " +
            "Missing: ${h.numberMissing.toInt()}"
        )

        // ── Bin frequency table ───────────────────────────────────────────────
        val myHeaders = listOf("Bin", "Label", "Lower", "Upper", "Count", "Cum Count", "%", "Cum %")
        val myRows = h.histogramData().map { b ->
            listOf(
                b.binNum.toString(),
                b.binLabel,
                formatLimit(b.binLowerLimit),
                formatLimit(b.binUpperLimit),
                b.binCount.toInt().toString(),
                b.cumCount.toInt().toString(),
                formatPct(b.proportion),
                formatPct(b.cumProportion)
            )
        }
        dataTable(myHeaders, myRows, caption = "Bin Frequencies")

        // ── Statistics on binned observations ─────────────────────────────────
        statPropertyTable(
            stat = h,
            caption = "Statistics on Binned Data",
            confidenceLevel = confidenceLevel
        )

        // ── Histogram plot ────────────────────────────────────────────────────
        plot(h.histogramPlot(), caption = myTitle)
    }
}

// ── Private formatting helpers ────────────────────────────────────────────────

/**
 * Formats a bin limit as a string. Infinite limits are rendered as `"−∞"` or `"+∞"`;
 * all other values use four decimal places.
 */
private fun formatLimit(value: Double): String = when {
    value == Double.NEGATIVE_INFINITY -> "−∞"
    value == Double.POSITIVE_INFINITY -> "+∞"
    value.isNaN() -> "—"
    else -> "%.4f".format(value)
}

/**
 * Formats a proportion (value in [0, 1]) as a percentage string with two decimal
 * places, e.g. `"12.34%"`. Returns `"—"` for NaN or infinite values.
 */
private fun formatPct(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.2f%%".format(value * 100.0)
}

/**
 * Overload of [histogram] that accepts a [HistogramIfc] reference.
 *
 * This is the variant used by [SimulationReportExtensions] when reporting histograms
 * obtained from a [ksl.simulation.Model] via
 * [ksl.modeling.variable.HistogramResponseCIfc.histogram], which returns
 * [ksl.utilities.statistic.CachedHistogram] (implements [HistogramIfc], not [Histogram]).
 *
 * All content is identical to the [Histogram] overload: overview paragraph, bin
 * frequency table, statistics on binned data, and histogram plot.
 *
 * @param h               the histogram interface to report
 * @param caption         optional section title; defaults to [h.name][HistogramIfc.name]
 * @param confidenceLevel confidence level for the StatTable half-width and CI; must be in (0, 1)
 */
fun ReportBuilder.histogram(
    h: HistogramIfc,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: h.name
    section(myTitle) {
        val myLower = formatLimit(h.firstBinLowerLimit)
        val myUpper = formatLimit(h.lastBinUpperLimit)
        paragraph(
            "Bins: ${h.numberBins}  |  " +
            "Range: [$myLower, $myUpper]  |  " +
            "Under: ${h.underFlowCount.toInt()}  |  " +
            "Over: ${h.overFlowCount.toInt()}  |  " +
            "Total: ${h.totalCount.toInt()}  |  " +
            "In Bins: ${h.count.toInt()}  |  " +
            "Missing: ${h.numberMissing.toInt()}"
        )
        val myHeaders = listOf("Bin", "Label", "Lower", "Upper", "Count", "Cum Count", "%", "Cum %")
        val myRows = h.histogramData().map { b ->
            listOf(
                b.binNum.toString(),
                b.binLabel,
                formatLimit(b.binLowerLimit),
                formatLimit(b.binUpperLimit),
                b.binCount.toInt().toString(),
                b.cumCount.toInt().toString(),
                formatPct(b.proportion),
                formatPct(b.cumProportion)
            )
        }
        dataTable(myHeaders, myRows, caption = "Bin Frequencies")
        statPropertyTable(
            stat = h,
            caption = "Statistics on Binned Data",
            confidenceLevel = confidenceLevel
        )
        plot(h.histogramPlot(), caption = myTitle)
    }
}
